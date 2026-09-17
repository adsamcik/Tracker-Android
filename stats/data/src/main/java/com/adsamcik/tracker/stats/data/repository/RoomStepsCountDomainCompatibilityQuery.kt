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
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.stats.api.repository.MAX_STEPS_COUNT_DOMAIN_REQUESTS
import com.adsamcik.tracker.stats.api.repository.MAX_STEPS_COUNT_DOMAIN_OWNERS_PER_BATCH
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityQuery
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityRequest
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityResult
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerEffect
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerIdentity
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerKind
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Bounded native receipt lookup.
 *
 * This reader never accepts time, count, zone, or source display names. The AppDatabase schema
 * owner may install the additive tables and sentinel later; until then every lookup is Unproven.
 */
@Singleton
internal class RoomStepsCountDomainCompatibilityQuery @Inject constructor(
	database: AppDatabase,
) : StepsCountDomainCompatibilityQuery {
	private val store = StepsCountDomainStore(database)

	override suspend fun compare(
		requests: List<StepsCountDomainCompatibilityRequest>,
	): List<StepsCountDomainCompatibilityResult> {
		if (requests.size > MAX_STEPS_COUNT_DOMAIN_REQUESTS) {
			return List(requests.size) { StepsCountDomainCompatibilityResult.Unverifiable }
		}
		val snapshot = requests.toList()
		val results = MutableList<StepsCountDomainCompatibilityResult?>(snapshot.size) { null }
		val bounded = snapshot.mapIndexedNotNull { index, request ->
			if (request.exceedsOwnerBounds) {
				results[index] = StepsCountDomainCompatibilityResult.Unverifiable
				null
			} else {
				IndexedRequest(index, request)
			}
		}
		bounded.toOwnerBoundedChunks().forEach { chunk ->
			currentCoroutineContext().ensureActive()
			val keys = chunk.flatMap { indexed ->
				indexed.request.sessionOwners.map { it.toLookupKey() } +
					indexed.request.ambientOwners.map { it.toLookupKey() }
			}.distinct()
			when (val read = store.readOwners(keys)) {
				StepsCountDomainOwnerRead.SchemaUnavailable ->
					chunk.forEach {
						results[it.index] = StepsCountDomainCompatibilityResult.Unproven
					}
				StepsCountDomainOwnerRead.Overflow,
				StepsCountDomainOwnerRead.Unverifiable,
				-> chunk.forEach {
					results[it.index] = StepsCountDomainCompatibilityResult.Unverifiable
				}
				is StepsCountDomainOwnerRead.Ready ->
					chunk.forEach {
						results[it.index] = resolveStepsCountDomainCompatibility(
							it.request,
							read,
						)
					}
			}
		}
		return results.map { it ?: StepsCountDomainCompatibilityResult.Unverifiable }
	}
}

private data class IndexedRequest(
	val index: Int,
	val request: StepsCountDomainCompatibilityRequest,
)

private fun List<IndexedRequest>.toOwnerBoundedChunks(): List<List<IndexedRequest>> {
	if (isEmpty()) return emptyList()
	val chunks = mutableListOf<MutableList<IndexedRequest>>()
	var current = mutableListOf<IndexedRequest>()
	var currentOwnerCount = 0
	for (indexed in this) {
		val ownerCount = indexed.request.sessionOwners.size + indexed.request.ambientOwners.size
		if (current.isNotEmpty() &&
			currentOwnerCount + ownerCount > MAX_STEPS_COUNT_DOMAIN_OWNERS_PER_BATCH
		) {
			chunks += current
			current = mutableListOf()
			currentOwnerCount = 0
		}
		current += indexed
		currentOwnerCount += ownerCount
	}
	if (current.isNotEmpty()) chunks += current
	return chunks
}

internal fun resolveStepsCountDomainCompatibility(
	request: StepsCountDomainCompatibilityRequest,
	read: StepsCountDomainOwnerRead.Ready,
): StepsCountDomainCompatibilityResult {
	if (request.exceedsOwnerBounds) {
		return StepsCountDomainCompatibilityResult.Unverifiable
	}
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
	if (all.any { (reference, stored) ->
			!stored.hasAuthenticSelectedOwner(reference)
		}) {
		return StepsCountDomainCompatibilityResult.Unverifiable
	}
	if (all.any { (_, stored) ->
			stored.owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT
		}) {
		return StepsCountDomainCompatibilityResult.Deleted
	}
	if (all.any { (_, stored) ->
			stored.owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN
		}
	) {
		return StepsCountDomainCompatibilityResult.Unproven
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

private fun StepsCountDomainStoredOwner.hasAuthenticSelectedOwner(
	reference: StepsCountDomainOwnerReference,
): Boolean {
	if (owner.ownerKind != reference.kind.storedCode ||
		owner.ownerIdentity != reference.identity.encoded ||
		owner.ownerRevision != reference.revision ||
		owner.ownerEffectChecksum != reference.effect.encoded
	) return false
	return when (owner.operation) {
		StepsCountDomainOwnerRevisionEntity.OPERATION_BIND -> hasAuthenticBinding()
		StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT ->
			receipt == null && completenessMarker == null
		StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN ->
			receipt == null && if (
				owner.ownerKind ==
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
			) {
				completenessMarker?.terminalState ==
					StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN
			} else {
				completenessMarker == null
			}
		else -> false
	}
}

private fun StepsCountDomainStoredOwner.hasAuthenticBinding(): Boolean {
	val receipt = receipt ?: return false
	return owner.receiptIdentity == receipt.receiptIdentity &&
		owner.ownerEffectChecksum == receipt.effectChecksum &&
		receipt.ownerKind == owner.ownerKind &&
		receipt.scopeIdentity == owner.scopeIdentity &&
		receipt.ownerIdentity == owner.ownerIdentity &&
		receipt.ownerRevision == owner.ownerRevision &&
		StepsCountDomainReceiptIntegrity.hasValidReceipt(receipt) &&
		if (owner.ownerKind ==
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
		) {
			completenessMarker?.let { marker ->
				marker.terminalState ==
					StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE &&
					marker.evidenceChecksum == receipt.completionEvidenceChecksum
			} == true
		} else {
			completenessMarker == null && receipt.completionEvidenceChecksum == null
		}
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
	StepsCountDomainOwnerReference? = try {
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
	} catch (_: IllegalArgumentException) {
		null
	}

internal fun SourceSessionCompletenessEntity.countDomainOwnerReferenceOrNull():
	StepsCountDomainOwnerReference? = try {
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
	} catch (_: IllegalArgumentException) {
		null
	} catch (_: ArithmeticException) {
		null
	}

internal fun AmbientStepsFactRevisionEntity.countDomainOwnerReferenceOrNull():
	StepsCountDomainOwnerReference? = try {
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
	} catch (_: IllegalArgumentException) {
		null
	}
