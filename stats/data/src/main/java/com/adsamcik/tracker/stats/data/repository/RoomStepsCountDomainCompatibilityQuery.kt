package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLineageKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLookupKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerRead
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStoredOwner
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.stats.api.repository.MAX_STEPS_COUNT_DOMAIN_REQUESTS
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityQuery
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityRequest
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityResult
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerEffect
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerIdentity
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerKind
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerReference
import javax.inject.Inject

/**
 * Bounded native receipt lookup.
 *
 * This reader never accepts time, count, zone, or source display names. The AppDatabase schema
 * owner may install the two additive tables later; until then every lookup remains Unproven.
 */
internal class RoomStepsCountDomainCompatibilityQuery @Inject constructor(
	database: AppDatabase,
) : StepsCountDomainCompatibilityQuery {
	private val store = StepsCountDomainStore(database)

	override suspend fun compare(
		requests: List<StepsCountDomainCompatibilityRequest>,
	): List<StepsCountDomainCompatibilityResult> {
		val snapshot = requests.toList()
		if (snapshot.size > MAX_STEPS_COUNT_DOMAIN_REQUESTS) {
			return List(snapshot.size) { StepsCountDomainCompatibilityResult.Unverifiable }
		}
		val keys = snapshot.flatMap { request ->
			request.sessionOwners.map { it.toLookupKey() } +
				request.ambientOwners.map { it.toLookupKey() }
		}.distinct()
		return when (val read = store.readOwners(keys)) {
			StepsCountDomainOwnerRead.SchemaUnavailable ->
				List(snapshot.size) { StepsCountDomainCompatibilityResult.Unproven }
			StepsCountDomainOwnerRead.Overflow,
			StepsCountDomainOwnerRead.Unverifiable,
			-> List(snapshot.size) { StepsCountDomainCompatibilityResult.Unverifiable }
			is StepsCountDomainOwnerRead.Ready ->
				snapshot.map { request -> resolveStepsCountDomainCompatibility(request, read) }
		}
	}
}

internal fun resolveStepsCountDomainCompatibility(
	request: StepsCountDomainCompatibilityRequest,
	read: StepsCountDomainOwnerRead.Ready,
): StepsCountDomainCompatibilityResult {
	if (request.sessionOwners.isEmpty() || request.ambientOwners.isEmpty() ||
		request.sessionOwners.none {
			it.kind == StepsCountDomainOwnerKind.SESSION_FACT
		} ||
		request.sessionOwners.none {
			it.kind == StepsCountDomainOwnerKind.SESSION_COMPLETENESS
		}
	) {
		return StepsCountDomainCompatibilityResult.Unproven
	}
	val references = request.sessionOwners + request.ambientOwners
	if (references.any { reference -> read.isStale(reference) }) {
		return StepsCountDomainCompatibilityResult.Unverifiable
	}
	val session = request.sessionOwners.map { reference ->
		read.ownerFor(reference) ?: return StepsCountDomainCompatibilityResult.Unproven
	}
	val ambient = request.ambientOwners.map { reference ->
		read.ownerFor(reference) ?: return StepsCountDomainCompatibilityResult.Unproven
	}
	val all = session + ambient
	if (all.any { (_, stored) ->
			stored.owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT
		}
	) {
		return StepsCountDomainCompatibilityResult.Deleted
	}
	if (all.any { (_, stored) ->
			stored.owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN
		}
	) {
		return StepsCountDomainCompatibilityResult.Unproven
	}
	if (all.any { (reference, stored) -> !stored.hasAuthenticBinding(reference) }) {
		return StepsCountDomainCompatibilityResult.Unverifiable
	}
	val sessionScopes = session.mapTo(linkedSetOf()) { (_, stored) ->
		stored.owner.scopeIdentity
	}
	if (sessionScopes.size != 1) return StepsCountDomainCompatibilityResult.Unverifiable

	val sessionDomains = session.mapTo(linkedSetOf()) { (_, stored) ->
		requireNotNull(stored.receipt).compatibilityKey()
	}
	val ambientDomains = ambient.mapTo(linkedSetOf()) { (_, stored) ->
		requireNotNull(stored.receipt).compatibilityKey()
	}
	if (sessionDomains.size != 1 || ambientDomains.size != 1) {
		return StepsCountDomainCompatibilityResult.Conflict
	}
	return if (sessionDomains.single() == ambientDomains.single()) {
		StepsCountDomainCompatibilityResult.ExactCompatible
	} else {
		StepsCountDomainCompatibilityResult.Conflict
	}
}

private fun StepsCountDomainOwnerRead.Ready.isStale(
	reference: StepsCountDomainOwnerReference,
): Boolean {
	val key = reference.toLookupKey()
	val latest = latestRevisions[
		StepsCountDomainOwnerLineageKey(key.ownerKind, key.ownerIdentity)
	] ?: return false
	return latest != reference.revision
}

private fun StepsCountDomainOwnerRead.Ready.ownerFor(
	reference: StepsCountDomainOwnerReference,
): Pair<StepsCountDomainOwnerReference, StepsCountDomainStoredOwner>? {
	val key = reference.toLookupKey()
	return owners[key]?.let { reference to it }
}

private fun StepsCountDomainStoredOwner.hasAuthenticBinding(
	reference: StepsCountDomainOwnerReference,
): Boolean {
	val receipt = receipt ?: return false
	return owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_BIND &&
		owner.ownerKind == reference.kind.storedCode &&
		owner.ownerIdentity == reference.identity.encoded &&
		owner.ownerRevision == reference.revision &&
		owner.ownerEffectChecksum == reference.effect.encoded &&
		owner.receiptIdentity == receipt.receiptIdentity &&
		owner.ownerEffectChecksum == receipt.effectChecksum &&
		receipt.ownerKind == owner.ownerKind &&
		receipt.scopeIdentity == owner.scopeIdentity &&
		receipt.ownerIdentity == owner.ownerIdentity &&
		receipt.ownerRevision == owner.ownerRevision &&
		StepsCountDomainReceiptIntegrity.hasValidReceipt(receipt)
}

private data class StepsCountDomainCompatibilityKey(
	val domainIdentity: String,
	val collectedDataEpoch: Long,
	val countDomainVersion: Int,
)

private fun StepsCountDomainReceiptEntity.compatibilityKey() =
	StepsCountDomainCompatibilityKey(
		domainIdentity,
		collectedDataEpoch,
		countDomainVersion,
	)

private fun StepsCountDomainOwnerReference.toLookupKey() = StepsCountDomainOwnerLookupKey(
	ownerKind = kind.storedCode,
	ownerIdentity = identity.encoded,
	ownerRevision = revision,
)

private val StepsCountDomainOwnerKind.storedCode: String
	get() = when (this) {
		StepsCountDomainOwnerKind.SESSION_FACT ->
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT
		StepsCountDomainOwnerKind.SESSION_COMPLETENESS ->
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
		StepsCountDomainOwnerKind.AMBIENT_FACT ->
			StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT
	}

internal fun StepFactRevisionEntity.countDomainOwnerReferenceOrNull():
	StepsCountDomainOwnerReference? = runCatching {
		StepsCountDomainOwnerReference(
			kind = StepsCountDomainOwnerKind.SESSION_FACT,
			identity = StepsCountDomainOwnerIdentity.opaque(
				StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
					writerProjectionId,
					writerProjectionVersion,
					logicalFactId,
				),
			),
			revision = semanticRevision,
			effect = StepsCountDomainOwnerEffect.opaque(effectChecksum),
		)
	}.getOrNull()

internal fun SourceSessionCompletenessEntity.countDomainOwnerReferenceOrNull():
	StepsCountDomainOwnerReference? = runCatching {
		val effectChecksum = StepsCountDomainReceiptIntegrity.completenessEffectChecksum(this)
		StepsCountDomainOwnerReference(
			kind = StepsCountDomainOwnerKind.SESSION_COMPLETENESS,
			identity = StepsCountDomainOwnerIdentity.opaque(
				StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
					logicalTrackingId,
					serviceRunId,
					sourceInstanceId,
					registrationGeneration,
				),
			),
			revision = StepsCountDomainReceiptIntegrity.completenessOwnerRevision(this),
			effect = StepsCountDomainOwnerEffect.opaque(effectChecksum),
		)
	}.getOrNull()

internal fun AmbientStepsFactRevisionEntity.countDomainOwnerReferenceOrNull():
	StepsCountDomainOwnerReference? = runCatching {
		StepsCountDomainOwnerReference(
			kind = StepsCountDomainOwnerKind.AMBIENT_FACT,
			identity = StepsCountDomainOwnerIdentity.opaque(
				StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
					writerId,
					writerVersion,
					logicalFactId,
				),
			),
			revision = semanticRevision,
			effect = StepsCountDomainOwnerEffect.opaque(effectChecksum),
		)
	}.getOrNull()
