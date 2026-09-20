package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFileReceiptEntity
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_STEPS_RUN_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsEntryV1
import com.adsamcik.tracker.shared.model.steps.portable.legacyUnprovenStepsCountDomainGraph
import com.adsamcik.tracker.shared.model.steps.portable.withExplicitUnprovenCountDomain

data class AuthenticatedImportedAmbientStepsGraphRevision(
	val binding: ImportedPortableStepsCountDomainBindingEntity,
	val productImportRevisions: Set<Long>,
	val graph: PortableCountDomainGraphV2,
) {
	val graphRevision: Long
		get() = binding.productRevision

	val sourceSchemaVersion: Int
		get() = binding.sourceSchemaVersion
}

data class AuthenticatedImportedPortableGraphBinding(
	val binding: ImportedPortableStepsCountDomainBindingEntity,
	val graph: PortableCountDomainGraphV2,
)

suspend fun AppDatabase.loadAuthenticatedImportedAmbientStepsGraphLineage(
	lineage: AuthenticatedImportedAmbientStepsLineage,
): List<AuthenticatedImportedAmbientStepsGraphRevision> {
	val dayIdentity = lineage.revisions.lastOrNull()?.header?.dayIdentity
	val bindings = dayIdentity?.let {
		importedPortableStepsCountDomainDao().bindings(
			ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
			listOf(it),
		)
	}.orEmpty()
	val graphs = linkedMapOf<String, PortableCountDomainGraphV2>()
	bindings.forEach { binding ->
		graphs[binding.graphIdentity] = checkNotNull(
			importedPortableStepsCountDomainDao().authenticatedGraph(
				binding.graphIdentity,
				ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS,
			),
		)
	}
	return authenticateImportedAmbientStepsGraphLineage(lineage, bindings, graphs)
}

fun AppDatabase.loadAuthenticatedImportedAmbientStepsGraphLineageForFullClear(
	lineage: AuthenticatedImportedAmbientStepsLineage,
): List<AuthenticatedImportedAmbientStepsGraphRevision> {
	val dayIdentity = lineage.revisions.lastOrNull()?.header?.dayIdentity
	val graphDao = importedPortableStepsCountDomainDao()
	val bindings = dayIdentity?.let {
		graphDao.bindingsForFullClear(
			ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
			it,
			ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY + 1,
		)
	}.orEmpty()
	val graphs = linkedMapOf<String, PortableCountDomainGraphV2>()
	bindings.forEach { binding ->
		graphs[binding.graphIdentity] = checkNotNull(
			graphDao.authenticatedGraphForFullClear(
				binding.graphIdentity,
				ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS,
			),
		)
	}
	return authenticateImportedAmbientStepsGraphLineage(lineage, bindings, graphs)
}

suspend fun AppDatabase.loadAuthenticatedImportedSessionCountDomainBinding(
	entry: RetainedImportedStepsEntry,
): AuthenticatedImportedPortableGraphBinding? =
	loadAuthenticatedImportedSessionCountDomainBinding(
		entry.metadata.identity,
		entry.legacyUnprovenCountDomainGraph(),
	)

suspend fun AppDatabase.loadAuthenticatedImportedSessionCountDomainBinding(
	entry: PortableStepsEntryV1,
): AuthenticatedImportedPortableGraphBinding? =
	loadAuthenticatedImportedSessionCountDomainBinding(
		entry.identity.value,
		legacyUnprovenStepsCountDomainGraph(entry.contentChecksum, entry.runs),
	)

private suspend fun AppDatabase.loadAuthenticatedImportedSessionCountDomainBinding(
	entryIdentity: String,
	expectedLegacyGraph: PortableCountDomainGraphV2,
): AuthenticatedImportedPortableGraphBinding? {
	val dao = importedPortableStepsCountDomainDao()
	val bindings = dao.bindings(
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
		listOf(entryIdentity),
	)
	if (bindings.isEmpty()) return null
	check(bindings.size == 1)
	val binding = bindings.single()
	check(binding.productRevision == IMPORTED_SESSION_PRODUCT_REVISION)
	val graph = checkNotNull(
		dao.authenticatedGraph(
			binding.graphIdentity,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
		),
	)
	val fileReceipt = binding.sourceReceiptIdentity?.let { receiptIdentity ->
		checkNotNull(dao.fileReceiptByIdentity(receiptIdentity))
	}
	authenticateImportedSessionBinding(binding, graph, fileReceipt, expectedLegacyGraph)
	return AuthenticatedImportedPortableGraphBinding(binding, graph)
}

fun AppDatabase.loadAuthenticatedImportedSessionCountDomainBindingForFullClear(
	binding: ImportedPortableStepsCountDomainBindingEntity,
	entry: RetainedImportedStepsEntry,
): AuthenticatedImportedPortableGraphBinding {
	val dao = importedPortableStepsCountDomainDao()
	val graph = checkNotNull(
		dao.authenticatedGraphForFullClear(
			binding.graphIdentity,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
		),
	)
	val fileReceipt = binding.sourceReceiptIdentity?.let { receiptIdentity ->
		checkNotNull(dao.fileReceiptByIdentityForFullClear(receiptIdentity))
	}
	val expectedLegacyGraph = entry.legacyUnprovenCountDomainGraph()
	authenticateImportedSessionBinding(binding, graph, fileReceipt, expectedLegacyGraph)
	return AuthenticatedImportedPortableGraphBinding(binding, graph)
}

internal fun RetainedImportedStepsEntry.legacyUnprovenCountDomainGraph(): PortableCountDomainGraphV2 =
	legacyUnprovenStepsCountDomainGraph(
		com.adsamcik.tracker.shared.model.steps.portable.PortableStepsDigest(
			metadata.contentChecksum,
		),
		portableRunsById.values.sortedWith(PORTABLE_STEPS_RUN_ORDER),
	)

fun isAuthenticatedAmbientGraphSuccessor(
	previous: PortableCountDomainGraphV2,
	incoming: PortableCountDomainGraphV2,
): Boolean {
	val previousByLineage = previous.ownerRevisions.groupBy { it.ownerKind to it.ownerIdentity }
	val incomingByLineage = incoming.ownerRevisions.groupBy { it.ownerKind to it.ownerIdentity }
	if (previousByLineage.keys != incomingByLineage.keys) return false
	if (previous.roots.map { it.stableBindingKey() }.toSet() !=
		incoming.roots.map { it.stableBindingKey() }.toSet()
	) return false
	var advanced = false
	for ((lineage, priorOwners) in previousByLineage) {
		val nextOwners = incomingByLineage.getValue(lineage)
		if (nextOwners.size < priorOwners.size ||
			nextOwners.take(priorOwners.size) != priorOwners
		) return false
		if (nextOwners.size > priorOwners.size) advanced = true
	}
	return advanced
}

private fun authenticateImportedAmbientStepsGraphLineage(
	lineage: AuthenticatedImportedAmbientStepsLineage,
	bindings: List<ImportedPortableStepsCountDomainBindingEntity>,
	graphs: Map<String, PortableCountDomainGraphV2>,
): List<AuthenticatedImportedAmbientStepsGraphRevision> {
	if (lineage.revisions.isEmpty()) {
		check(lineage.archiveDays.isEmpty())
		check(bindings.isEmpty())
		check(graphs.isEmpty())
		return emptyList()
	}
	val dayIdentity = lineage.latest.header.dayIdentity
	val members = lineage.archiveDays.filter { it.dayIdentity == dayIdentity }
	check(members.isNotEmpty())
	check(bindings.isNotEmpty())
	check(bindings.size <= ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY)
	check(bindings.map { it.productRevision } == (1L..bindings.size.toLong()).toList())
	check(
		bindings.mapTo(linkedSetOf()) { it.productRevision } ==
			members.mapTo(linkedSetOf()) { it.boundCountDomainGraphRevision },
	)
	check(graphs.keys == bindings.mapTo(linkedSetOf()) { it.graphIdentity })
	check(bindings.map { it.graphIdentity }.distinct().size == bindings.size)

	val revisionsByNumber = lineage.revisions.associateBy { it.header.importRevision }
	val archivesByIdentity = lineage.archives.associateBy { it.archiveIdentity }
	val receiptsByIdentity = lineage.receipts.associateBy { it.receiptIdentity }
	check(receiptsByIdentity.size == lineage.receipts.size)
	val graphLineage = bindings.map { binding ->
		check(binding.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY)
		check(binding.productIdentity == dayIdentity)
		val sourceArchiveIdentity = checkNotNull(binding.sourceArchiveIdentity)
		val sourceArchiveChecksum = checkNotNull(binding.sourceArchiveContentChecksum)
		val sourceReceiptIdentity = checkNotNull(binding.sourceReceiptIdentity)
		val archive = checkNotNull(archivesByIdentity[sourceArchiveIdentity])
		val receipt = checkNotNull(receiptsByIdentity[sourceReceiptIdentity])
		check(receipt.archiveIdentity == sourceArchiveIdentity)
		check(receipt.archiveContentChecksum == sourceArchiveChecksum)
		check(archive.contentChecksum == sourceArchiveChecksum)
		check(archive.sourceSchemaVersion == binding.sourceSchemaVersion)
		val revisionMembers = members.filter {
			it.boundCountDomainGraphRevision == binding.productRevision
		}
		check(revisionMembers.isNotEmpty())
		check(revisionMembers.any { it.archiveIdentity == sourceArchiveIdentity })
		val productRevisions = revisionMembers.mapTo(linkedSetOf()) { it.boundDayImportRevision }
		val graph = checkNotNull(graphs[binding.graphIdentity])
		check(graph.identity.value == binding.graphIdentity)
		if (binding.sourceSchemaVersion == 2) {
			check(graph.hasCompletePortableOwnerLineages())
		}
		productRevisions.forEach { productRevision ->
			val day = checkNotNull(revisionsByNumber[productRevision]).day
			if (binding.sourceSchemaVersion == 1) {
				check(
					graph ==
						day.withExplicitUnprovenCountDomain(binding.productRevision).countDomainGraph,
				)
			} else {
				PortableAmbientStepsDayV2(day, graph)
			}
		}
		AuthenticatedImportedAmbientStepsGraphRevision(
			binding = binding,
			productImportRevisions = productRevisions,
			graph = graph,
		)
	}
	graphLineage.zipWithNext().forEach { (previous, incoming) ->
		if (incoming.sourceSchemaVersion == 2) {
			check(isAuthenticatedAmbientGraphSuccessor(previous.graph, incoming.graph))
		}
	}
	check(lineage.latest.header.importRevision in graphLineage.last().productImportRevisions)
	return graphLineage
}

private fun authenticateImportedSessionBinding(
	binding: ImportedPortableStepsCountDomainBindingEntity,
	graph: PortableCountDomainGraphV2,
	fileReceipt: ImportedPortableStepsFileReceiptEntity?,
	expectedLegacyGraph: PortableCountDomainGraphV2,
) {
	check(binding.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY)
	check(binding.productRevision == IMPORTED_SESSION_PRODUCT_REVISION)
	check(binding.graphIdentity == graph.identity.value)
	if (binding.sourceSchemaVersion == 2) {
		check(graph.hasCompletePortableOwnerLineages())
	} else {
		check(binding.sourceSchemaVersion == 1)
		check(graph == expectedLegacyGraph)
	}
	if (binding.sourceReceiptIdentity == null) {
		check(binding.sourceSchemaVersion == 1)
		check(binding.sourceArchiveContentChecksum == null)
		check(fileReceipt == null)
		return
	}
	val receipt = checkNotNull(fileReceipt)
	check(receipt.receiptIdentity == binding.sourceReceiptIdentity)
	check(receipt.entryIdentity == binding.productIdentity)
	check(receipt.graphIdentity == binding.graphIdentity)
	check(receipt.archiveContentChecksum == binding.sourceArchiveContentChecksum)
}

fun PortableCountDomainGraphV2.hasCompletePortableOwnerLineages(): Boolean =
	ownerRevisions.groupBy { it.ownerKind to it.ownerIdentity }.values.all {
		it.first().ownerRevision == 1L
	}

private fun com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainRootV2
	.stableBindingKey(): List<String> = listOf(
	containerIdentity.value,
	productIdentity.value,
	ownerKind.name,
	ownerIdentity.value,
)

private fun com.adsamcik.tracker.shared.base.database.dao.ImportedPortableStepsCountDomainDao
	.authenticatedGraphForFullClear(
		graphIdentity: String,
		expectedSourceFormat: String,
	): PortableCountDomainGraphV2? {
	val graph = graphForFullClear(graphIdentity) ?: return null
	if (graph.sourceFormat != expectedSourceFormat) return null
	return ImportedPortableStepsCountDomainAuthenticator.authenticate(
		graph,
		receiptsForFullClear(graphIdentity, graph.receiptCount + 1),
		ownersForFullClear(graphIdentity, graph.ownerRevisionCount + 1),
		markersForFullClear(graphIdentity, graph.completenessMarkerCount + 1),
		rootsForFullClear(graphIdentity, graph.rootCount + 1),
	)
}

private const val IMPORTED_SESSION_PRODUCT_REVISION = 1L
