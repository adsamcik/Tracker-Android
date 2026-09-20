package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedPortableStepsCountDomainDao
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainRootEntity
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2

data class ImportedPortableStepsCountDomainRows(
	val graph: ImportedPortableStepsCountDomainGraphEntity,
	val receipts: List<ImportedPortableStepsCountDomainReceiptEntity>,
	val owners: List<ImportedPortableStepsCountDomainOwnerRevisionEntity>,
	val markers: List<ImportedPortableStepsCountDomainCompletenessEntity>,
	val roots: List<ImportedPortableStepsCountDomainRootEntity>,
)

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
			ImportedPortableStepsCountDomainReceiptEntity(
				graphIdentity = graphIdentity,
				receiptIdentity = receipt.identity.value,
				domainIdentity = receipt.domainIdentity.value,
				ownerKind = receipt.ownerKind.name,
				scopeIdentity = receipt.scopeIdentity.value,
				ownerIdentity = receipt.ownerIdentity.value,
				ownerRevision = receipt.ownerRevision,
				registrationGeneration = receipt.registrationGeneration,
				sourceCollectedDataEpoch = receipt.collectedDataEpoch,
				authorityRevision = receipt.authorityRevision,
				authorityFingerprint = receipt.authorityFingerprint.value,
				coverage = receipt.coverage.name,
				coverageVersion = receipt.coverageVersion,
				countDomainVersion = receipt.countDomainVersion,
				effectChecksum = receipt.effectChecksum.value,
				completenessEvidenceChecksum = receipt.completenessEvidenceChecksum?.value,
			)
		},
		owners = ownerRevisions.map { owner ->
			ImportedPortableStepsCountDomainOwnerRevisionEntity(
				graphIdentity = graphIdentity,
				ownerKind = owner.ownerKind.name,
				scopeIdentity = owner.scopeIdentity.value,
				ownerIdentity = owner.ownerIdentity.value,
				ownerRevision = owner.ownerRevision,
				operation = owner.operation.name,
				receiptIdentity = owner.receiptIdentity?.value,
				ownerEffectChecksum = owner.ownerEffectChecksum.value,
				sourceLinkedAtMs = owner.linkedAtMs,
			)
		},
		markers = completenessMarkers.map { marker ->
			ImportedPortableStepsCountDomainCompletenessEntity(
				graphIdentity = graphIdentity,
				ownerIdentity = marker.ownerIdentity.value,
				ownerRevision = marker.ownerRevision,
				terminalState = marker.terminalState.name,
				lastAdmissionOrdinal = marker.lastAdmissionOrdinal,
				lastSourceSequence = marker.lastSourceSequence,
				providerFlushOutcome = marker.providerFlushOutcome,
				registrationRemovalOutcome = marker.registrationRemovalOutcome,
				registrationTimelineChecksum = marker.registrationTimelineChecksum.value,
				evidenceChecksum = marker.evidenceChecksum.value,
			)
		},
		roots = roots.map { root ->
			ImportedPortableStepsCountDomainRootEntity(
				graphIdentity = graphIdentity,
				containerIdentity = root.containerIdentity.value,
				productIdentity = root.productIdentity.value,
				ownerKind = root.ownerKind.name,
				ownerIdentity = root.ownerIdentity.value,
				ownerRevision = root.ownerRevision,
			)
		},
	)
}

suspend fun ImportedPortableStepsCountDomainDao.authenticatedGraph(
	graphIdentity: String,
	expectedSourceFormat: String? = null,
): PortableCountDomainGraphV2? {
	val graph = graph(graphIdentity) ?: return null
	if (expectedSourceFormat != null && graph.sourceFormat != expectedSourceFormat) return null
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

suspend fun ImportedPortableStepsCountDomainDao.insertAuthenticatedGraph(
	graph: PortableCountDomainGraphV2,
	sourceFormat: String,
) {
	val rows = graph.toImportedRows(sourceFormat)
	insertGraph(rows.graph)
	if (rows.receipts.isNotEmpty()) insertReceipts(rows.receipts)
	insertOwners(rows.owners)
	if (rows.markers.isNotEmpty()) insertMarkers(rows.markers)
	insertRoots(rows.roots)
}
