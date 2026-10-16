package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedPortableStepsCountDomainDao
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainRootEntity
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainCompletenessMarkerV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerRevisionV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainReceiptV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainRootV2

/**
 * Persisted imported graph header and its original receipt, owner, completeness-marker and root
 * rows.
 */
data class ImportedPortableStepsCountDomainRows(
	val graph: ImportedPortableStepsCountDomainGraphEntity,
	val receipts: List<ImportedPortableStepsCountDomainReceiptEntity>,
	val owners: List<ImportedPortableStepsCountDomainOwnerRevisionEntity>,
	val markers: List<ImportedPortableStepsCountDomainCompletenessEntity>,
	val roots: List<ImportedPortableStepsCountDomainRootEntity>,
)

/**
 * Copies the original portable count-domain graph into imported row types under the explicit file
 * format, preserving original IDs and effects.
 */
fun PortableCountDomainGraphV2.toImportedRows(
	sourceFormat: String,
): ImportedPortableStepsCountDomainRows {
	val graphIdentity = identity.value
	return ImportedPortableStepsCountDomainRows(
		graph = ImportedPortableStepsCountDomainGraphEntity(
			graphIdentity = graphIdentity,
			contentChecksum = contentChecksum.value,
			sourceFormat = sourceFormat,
			sourceSchemaVersion = 2,
			receiptCount = receipts.size,
			ownerRevisionCount = ownerRevisions.size,
			completenessMarkerCount = completenessMarkers.size,
			rootCount = roots.size,
		),
		receipts = receipts.map { receipt ->
			receipt.toImportedRow(graphIdentity)
		},
		owners = ownerRevisions.map { owner ->
			owner.toImportedRow(graphIdentity)
		},
		markers = completenessMarkers.map { marker ->
			marker.toImportedRow(graphIdentity)
		},
		roots = roots.map { root ->
			root.toImportedRow(graphIdentity)
		},
	)
}

private fun PortableCountDomainReceiptV2.toImportedRow(graphIdentity: String):
	ImportedPortableStepsCountDomainReceiptEntity {
	return ImportedPortableStepsCountDomainReceiptEntity(
		graphIdentity = graphIdentity,
		receiptIdentity = identity.value,
		domainIdentity = domainIdentity.value,
		ownerKind = ownerKind.name,
		scopeIdentity = scopeIdentity.value,
		ownerIdentity = ownerIdentity.value,
		ownerRevision = ownerRevision,
		registrationGeneration = registrationGeneration,
		sourceCollectedDataEpoch = collectedDataEpoch,
		authorityRevision = authorityRevision,
		authorityFingerprint = authorityFingerprint.value,
		coverage = coverage.name,
		coverageVersion = coverageVersion,
		countDomainVersion = countDomainVersion,
		effectChecksum = effectChecksum.value,
		completenessEvidenceChecksum = completenessEvidenceChecksum?.value,
	)
}

private fun PortableCountDomainOwnerRevisionV2.toImportedRow(graphIdentity: String):
	ImportedPortableStepsCountDomainOwnerRevisionEntity {
	return ImportedPortableStepsCountDomainOwnerRevisionEntity(
		graphIdentity = graphIdentity,
		ownerKind = ownerKind.name,
		scopeIdentity = scopeIdentity.value,
		ownerIdentity = ownerIdentity.value,
		ownerRevision = ownerRevision,
		operation = operation.name,
		receiptIdentity = receiptIdentity?.value,
		ownerEffectChecksum = ownerEffectChecksum.value,
		sourceLinkedAtMs = linkedAtMs,
	)
}

private fun PortableCountDomainCompletenessMarkerV2.toImportedRow(graphIdentity: String):
	ImportedPortableStepsCountDomainCompletenessEntity {
	return ImportedPortableStepsCountDomainCompletenessEntity(
		graphIdentity = graphIdentity,
		ownerIdentity = ownerIdentity.value,
		ownerRevision = ownerRevision,
		terminalState = terminalState.name,
		lastAdmissionOrdinal = lastAdmissionOrdinal,
		lastSourceSequence = lastSourceSequence,
		providerFlushOutcome = providerFlushOutcome,
		registrationRemovalOutcome = registrationRemovalOutcome,
		registrationTimelineChecksum = registrationTimelineChecksum.value,
		evidenceChecksum = evidenceChecksum.value,
	)
}

private fun PortableCountDomainRootV2.toImportedRow(graphIdentity: String):
	ImportedPortableStepsCountDomainRootEntity {
	return ImportedPortableStepsCountDomainRootEntity(
		graphIdentity = graphIdentity,
		containerIdentity = containerIdentity.value,
		productIdentity = productIdentity.value,
		ownerKind = ownerKind.name,
		ownerIdentity = ownerIdentity.value,
		ownerRevision = ownerRevision,
	)
}

/**
 * Loads original imported graph rows and verifies the optional expected source format; returns
 * null for absent or invalid content.
 */
suspend fun ImportedPortableStepsCountDomainDao.authenticatedGraph(
	graphIdentity: String,
	expectedSourceFormat: String? = null,
): PortableCountDomainGraphV2? {
	val graph = graph(graphIdentity) ?: return null
	if (expectedSourceFormat != null && graph.sourceFormat != expectedSourceFormat) {
		return null
	}
	val receipts = receipts(graphIdentity, graph.receiptCount + 1)
	val owners = owners(graphIdentity, graph.ownerRevisionCount + 1)
	val markers = markers(graphIdentity, graph.completenessMarkerCount + 1)
	val roots = roots(graphIdentity, graph.rootCount + 1)
	return ImportedPortableStepsCountDomainAuthenticator.authenticate(
		graph,
		receipts,
		owners,
		markers,
		roots,
	)
}

/**
 * Writes the original graph header and each supplied member row through the existing DAO insert
 * policies; callers own the atomic transaction.
 */
suspend fun ImportedPortableStepsCountDomainDao.insertAuthenticatedGraph(
	graph: PortableCountDomainGraphV2,
	sourceFormat: String,
) {
	val rows = graph.toImportedRows(sourceFormat)
	insertGraph(rows.graph)
	if (rows.receipts.isNotEmpty()) {
		insertReceipts(rows.receipts)
	}
	insertOwners(rows.owners)
	if (rows.markers.isNotEmpty()) {
		insertMarkers(rows.markers)
	}
	insertRoots(rows.roots)
}
