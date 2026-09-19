package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainRootEntity
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainCompletenessMarkerV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainCompletenessState
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainDigest
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOperation
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerKind
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerRevisionV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainReceiptV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainRootV2

/** Reconstructs and authenticates imported graph rows without consulting native authority. */
object ImportedPortableStepsCountDomainAuthenticator {
	fun authenticate(
		graph: ImportedPortableStepsCountDomainGraphEntity,
		receipts: List<ImportedPortableStepsCountDomainReceiptEntity>,
		owners: List<ImportedPortableStepsCountDomainOwnerRevisionEntity>,
		markers: List<ImportedPortableStepsCountDomainCompletenessEntity>,
		roots: List<ImportedPortableStepsCountDomainRootEntity>,
	): PortableCountDomainGraphV2? = runCatching {
		require(receipts.size == graph.receiptCount)
		require(owners.size == graph.ownerRevisionCount)
		require(markers.size == graph.completenessMarkerCount)
		require(roots.size == graph.rootCount)
		require((receipts + owners + markers + roots).allGraphIdentity(graph.graphIdentity))
		val portable = PortableCountDomainGraphV2(
			identity = PortableCountDomainOpaqueIdentity(graph.graphIdentity),
			contentChecksum = PortableCountDomainOpaqueIdentity(graph.contentChecksum),
			receipts = receipts.map { row ->
				PortableCountDomainReceiptV2(
					identity = PortableCountDomainOpaqueIdentity(row.receiptIdentity),
					domainIdentity = PortableCountDomainOpaqueIdentity(row.domainIdentity),
					ownerKind = PortableCountDomainOwnerKind.valueOf(row.ownerKind),
					scopeIdentity = PortableCountDomainOpaqueIdentity(row.scopeIdentity),
					ownerIdentity = PortableCountDomainOpaqueIdentity(row.ownerIdentity),
					ownerRevision = row.ownerRevision,
					registrationGeneration = row.registrationGeneration,
					collectedDataEpoch = row.sourceCollectedDataEpoch,
					authorityRevision = row.authorityRevision,
					authorityFingerprint = PortableCountDomainDigest(row.authorityFingerprint),
					coverage = PortableCountDomainCoverage.valueOf(row.coverage),
					coverageVersion = row.coverageVersion,
					countDomainVersion = row.countDomainVersion,
					effectChecksum = PortableCountDomainDigest(row.effectChecksum),
					completenessEvidenceChecksum = row.completenessEvidenceChecksum?.let(
						::PortableCountDomainDigest,
					),
				)
			},
			ownerRevisions = owners.map { row ->
				PortableCountDomainOwnerRevisionV2(
					ownerKind = PortableCountDomainOwnerKind.valueOf(row.ownerKind),
					scopeIdentity = PortableCountDomainOpaqueIdentity(row.scopeIdentity),
					ownerIdentity = PortableCountDomainOpaqueIdentity(row.ownerIdentity),
					ownerRevision = row.ownerRevision,
					operation = PortableCountDomainOperation.valueOf(row.operation),
					receiptIdentity = row.receiptIdentity?.let(::PortableCountDomainOpaqueIdentity),
					ownerEffectChecksum = PortableCountDomainDigest(row.ownerEffectChecksum),
					linkedAtMs = row.sourceLinkedAtMs,
				)
			},
			completenessMarkers = markers.map { row ->
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
			},
			roots = roots.map { row ->
				PortableCountDomainRootV2(
					containerIdentity = PortableCountDomainOpaqueIdentity(row.containerIdentity),
					productIdentity = PortableCountDomainOpaqueIdentity(row.productIdentity),
					ownerKind = PortableCountDomainOwnerKind.valueOf(row.ownerKind),
					ownerIdentity = PortableCountDomainOpaqueIdentity(row.ownerIdentity),
					ownerRevision = row.ownerRevision,
				)
			},
		)
		require(portable.receipts == portable.receipts.sortedWith(
			com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_COUNT_DOMAIN_RECEIPT_ORDER,
		))
		portable
	}.getOrNull()
}

private fun List<Any>.allGraphIdentity(expected: String): Boolean = all { value ->
	when (value) {
		is ImportedPortableStepsCountDomainReceiptEntity -> value.graphIdentity == expected
		is ImportedPortableStepsCountDomainOwnerRevisionEntity -> value.graphIdentity == expected
		is ImportedPortableStepsCountDomainCompletenessEntity -> value.graphIdentity == expected
		is ImportedPortableStepsCountDomainRootEntity -> value.graphIdentity == expected
		else -> false
	}
}
