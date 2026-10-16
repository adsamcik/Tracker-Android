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
	/**
	 * Reconstructs canonical graph content from original imported rows, or returns null for an
	 * invalid graph hierarchy/checksum.
	 */
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
				row.toPortableReceipt()
			}.sortedWith(
				com.adsamcik.tracker.shared.model.steps.portable
					.PORTABLE_COUNT_DOMAIN_RECEIPT_ORDER,
			),
			ownerRevisions = owners.map { row ->
				row.toPortableOwnerRevision()
			}.sortedWith(
				com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_COUNT_DOMAIN_OWNER_ORDER,
			),
			completenessMarkers = markers.map { row ->
				row.toPortableCompletenessMarker()
			}.sortedWith(
				com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_COUNT_DOMAIN_MARKER_ORDER,
			),
			roots = roots.map { row ->
				row.toPortableRoot()
			}.sortedWith(
				com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_COUNT_DOMAIN_ROOT_ORDER,
			),
		)
		require(portable.receipts == portable.receipts.sortedWith(
			com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_COUNT_DOMAIN_RECEIPT_ORDER,
		))
		portable
	}.getOrNull()

	private fun ImportedPortableStepsCountDomainReceiptEntity.toPortableReceipt():
		PortableCountDomainReceiptV2 {
		return PortableCountDomainReceiptV2(
			identity = PortableCountDomainOpaqueIdentity(receiptIdentity),
			domainIdentity = PortableCountDomainOpaqueIdentity(domainIdentity),
			ownerKind = PortableCountDomainOwnerKind.valueOf(ownerKind),
			scopeIdentity = PortableCountDomainOpaqueIdentity(scopeIdentity),
			ownerIdentity = PortableCountDomainOpaqueIdentity(ownerIdentity),
			ownerRevision = ownerRevision,
			registrationGeneration = registrationGeneration,
			collectedDataEpoch = sourceCollectedDataEpoch,
			authorityRevision = authorityRevision,
			authorityFingerprint = PortableCountDomainDigest(authorityFingerprint),
			coverage = PortableCountDomainCoverage.valueOf(coverage),
			coverageVersion = coverageVersion,
			countDomainVersion = countDomainVersion,
			effectChecksum = PortableCountDomainDigest(effectChecksum),
			completenessEvidenceChecksum = completenessEvidenceChecksum?.let(
				::PortableCountDomainDigest,
			),
		)
	}

	private fun ImportedPortableStepsCountDomainOwnerRevisionEntity.toPortableOwnerRevision():
		PortableCountDomainOwnerRevisionV2 {
		return PortableCountDomainOwnerRevisionV2(
			ownerKind = PortableCountDomainOwnerKind.valueOf(ownerKind),
			scopeIdentity = PortableCountDomainOpaqueIdentity(scopeIdentity),
			ownerIdentity = PortableCountDomainOpaqueIdentity(ownerIdentity),
			ownerRevision = ownerRevision,
			operation = PortableCountDomainOperation.valueOf(operation),
			receiptIdentity = receiptIdentity?.let(::PortableCountDomainOpaqueIdentity),
			ownerEffectChecksum = PortableCountDomainDigest(ownerEffectChecksum),
			linkedAtMs = sourceLinkedAtMs,
		)
	}

	private fun ImportedPortableStepsCountDomainCompletenessEntity.toPortableCompletenessMarker():
		PortableCountDomainCompletenessMarkerV2 {
		return PortableCountDomainCompletenessMarkerV2(
			ownerIdentity = PortableCountDomainOpaqueIdentity(ownerIdentity),
			ownerRevision = ownerRevision,
			terminalState = PortableCountDomainCompletenessState.valueOf(terminalState),
			lastAdmissionOrdinal = lastAdmissionOrdinal,
			lastSourceSequence = lastSourceSequence,
			providerFlushOutcome = providerFlushOutcome,
			registrationRemovalOutcome = registrationRemovalOutcome,
			registrationTimelineChecksum =
				PortableCountDomainDigest(registrationTimelineChecksum),
			evidenceChecksum = PortableCountDomainDigest(evidenceChecksum),
		)
	}

	private fun ImportedPortableStepsCountDomainRootEntity.toPortableRoot():
		PortableCountDomainRootV2 {
		return PortableCountDomainRootV2(
			containerIdentity = PortableCountDomainOpaqueIdentity(containerIdentity),
			productIdentity = PortableCountDomainOpaqueIdentity(productIdentity),
			ownerKind = PortableCountDomainOwnerKind.valueOf(ownerKind),
			ownerIdentity = PortableCountDomainOpaqueIdentity(ownerIdentity),
			ownerRevision = ownerRevision,
		)
	}
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
