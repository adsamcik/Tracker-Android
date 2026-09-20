package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainCompletenessMarkerV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainCompletenessState
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainDigest
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainFormatV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOperation
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerKind
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerRevisionV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainReceiptV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainRootV2

data class PortableCountDomainRootSeed(
	val containerIdentity: String,
	val productIdentity: String,
	val ownerKind: String,
	val ownerIdentity: String,
	val ownerRevision: Long,
	val ownerEffectChecksum: String,
)

sealed interface PortableCountDomainGraphRead {
	data class Ready(val graph: PortableCountDomainGraphV2) : PortableCountDomainGraphRead
	data object Unproven : PortableCountDomainGraphRead
	data object Unverifiable : PortableCountDomainGraphRead
	data object Overflow : PortableCountDomainGraphRead
}

/**
 * Bounded native graph extraction. Callers own the surrounding stable Room read transaction.
 */
class PortableCountDomainGraphReader(private val database: AppDatabase) {
	suspend fun read(roots: List<PortableCountDomainRootSeed>): PortableCountDomainGraphRead {
		if (roots.isEmpty()) return PortableCountDomainGraphRead.Unproven
		if (roots.size > PortableCountDomainFormatV2.MAX_ROOTS) {
			return PortableCountDomainGraphRead.Overflow
		}
		return try {
			readUnchecked(roots)
		} catch (_: IllegalArgumentException) {
			PortableCountDomainGraphRead.Unverifiable
		} catch (_: IllegalStateException) {
			PortableCountDomainGraphRead.Unverifiable
		} catch (_: ArithmeticException) {
			PortableCountDomainGraphRead.Overflow
		}
	}

	private suspend fun readUnchecked(
		seeds: List<PortableCountDomainRootSeed>,
	): PortableCountDomainGraphRead {
		if (StepsCountDomainSchema.inspect(database.openHelper.readableDatabase) !=
			StepsCountDomainSchemaState.ValidV2
		) {
			return PortableCountDomainGraphRead.Unverifiable
		}
		val lineageKeys = seeds.map { it.ownerKind to it.ownerIdentity }.distinct()
		val ownerIdentities = lineageKeys.map { it.second }
		val dao = database.stepsCountDomainReceiptDao()
		val owners = ownerIdentities.chunked(QUERY_BATCH).flatMap { identities ->
			dao.owners(
				identities,
				PortableCountDomainFormatV2.MAX_OWNER_REVISIONS + 1,
			)
		}.filter { owner -> ownerKindKey(owner) in lineageKeys }
		if (owners.size > PortableCountDomainFormatV2.MAX_OWNER_REVISIONS) {
			return PortableCountDomainGraphRead.Overflow
		}
		val ownersByLineage = owners.groupBy(::ownerKindKey)
		if (ownersByLineage.keys != lineageKeys.toSet()) {
			return PortableCountDomainGraphRead.Unproven
		}
		for (seed in seeds) {
			val latest = ownersByLineage[seed.ownerKind to seed.ownerIdentity]?.lastOrNull()
				?: return PortableCountDomainGraphRead.Unproven
			if (latest.ownerRevision != seed.ownerRevision ||
				latest.ownerEffectChecksum != seed.ownerEffectChecksum
			) return PortableCountDomainGraphRead.Unverifiable
		}
		val receiptIdentities = owners.mapNotNull { it.receiptIdentity }.distinct()
		val receipts = receiptIdentities.chunked(QUERY_BATCH).flatMap { identities ->
			dao.receipts(identities, PortableCountDomainFormatV2.MAX_RECEIPTS + 1)
		}
		if (receipts.size > PortableCountDomainFormatV2.MAX_RECEIPTS) {
			return PortableCountDomainGraphRead.Overflow
		}
		if (receipts.map { it.receiptIdentity }.toSet() != receiptIdentities.toSet()) {
			return PortableCountDomainGraphRead.Unverifiable
		}
		val completenessOwnerIds = owners.filter {
			it.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
		}.map { it.ownerIdentity }.distinct()
		val markers = completenessOwnerIds.chunked(QUERY_BATCH).flatMap { identities ->
			dao.completenessMarkers(
				identities,
				PortableCountDomainFormatV2.MAX_COMPLETENESS_MARKERS + 1,
			)
		}
		if (markers.size > PortableCountDomainFormatV2.MAX_COMPLETENESS_MARKERS) {
			return PortableCountDomainGraphRead.Overflow
		}
		val graph = PortableCountDomainGraphV2.create(
			receipts = receipts.map(::portableReceipt),
			ownerRevisions = owners.map(::portableOwner),
			completenessMarkers = markers.map(::portableMarker),
			roots = seeds.map { seed ->
				PortableCountDomainRootV2(
					containerIdentity = PortableCountDomainOpaqueIdentity(seed.containerIdentity),
					productIdentity = PortableCountDomainOpaqueIdentity(seed.productIdentity),
					ownerKind = PortableCountDomainOwnerKind.valueOf(seed.ownerKind),
					ownerIdentity = PortableCountDomainOpaqueIdentity(seed.ownerIdentity),
					ownerRevision = seed.ownerRevision,
				)
			},
		)
		return PortableCountDomainGraphRead.Ready(graph)
	}

	private fun portableReceipt(row: StepsCountDomainReceiptEntity) =
		PortableCountDomainReceiptV2(
			identity = PortableCountDomainOpaqueIdentity(row.receiptIdentity),
			domainIdentity = PortableCountDomainOpaqueIdentity(row.domainIdentity),
			ownerKind = PortableCountDomainOwnerKind.valueOf(row.ownerKind),
			scopeIdentity = PortableCountDomainOpaqueIdentity(row.scopeIdentity),
			ownerIdentity = PortableCountDomainOpaqueIdentity(row.ownerIdentity),
			ownerRevision = row.ownerRevision,
			registrationGeneration = row.registrationGeneration,
			collectedDataEpoch = row.collectedDataEpoch,
			authorityRevision = row.authorityRevision,
			authorityFingerprint = PortableCountDomainDigest(row.authorityFingerprint),
			coverage = PortableCountDomainCoverage.valueOf(row.coverageKind),
			coverageVersion = row.coverageVersion,
			countDomainVersion = row.countDomainVersion,
			effectChecksum = PortableCountDomainDigest(row.effectChecksum),
			completenessEvidenceChecksum = row.completionEvidenceChecksum?.let(
				::PortableCountDomainDigest,
			),
		)

	private fun portableOwner(row: StepsCountDomainOwnerRevisionEntity) =
		PortableCountDomainOwnerRevisionV2(
			ownerKind = PortableCountDomainOwnerKind.valueOf(row.ownerKind),
			scopeIdentity = PortableCountDomainOpaqueIdentity(row.scopeIdentity),
			ownerIdentity = PortableCountDomainOpaqueIdentity(row.ownerIdentity),
			ownerRevision = row.ownerRevision,
			operation = PortableCountDomainOperation.valueOf(row.operation),
			receiptIdentity = row.receiptIdentity?.let(::PortableCountDomainOpaqueIdentity),
			ownerEffectChecksum = PortableCountDomainDigest(row.ownerEffectChecksum),
			linkedAtMs = row.linkedAtMs,
		)

	private fun portableMarker(row: StepsCountDomainCompletenessMarkerEntity) =
		PortableCountDomainCompletenessMarkerV2(
			ownerIdentity = PortableCountDomainOpaqueIdentity(row.ownerIdentity),
			ownerRevision = row.ownerRevision,
			terminalState = PortableCountDomainCompletenessState.valueOf(row.terminalState),
			lastAdmissionOrdinal = row.lastAdmissionOrdinal,
			lastSourceSequence = row.lastSourceSequence,
			providerFlushOutcome = row.providerFlushOutcome,
			registrationRemovalOutcome = row.registrationRemovalOutcome,
			registrationTimelineChecksum =
				PortableCountDomainDigest(row.registrationTimelineChecksum),
			evidenceChecksum = PortableCountDomainDigest(row.evidenceChecksum),
		)

	private fun ownerKindKey(
		owner: StepsCountDomainOwnerRevisionEntity,
	): Pair<String, String> = owner.ownerKind to owner.ownerIdentity

	private companion object {
		const val QUERY_BATCH = 256
	}
}
