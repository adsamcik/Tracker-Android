package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedPortableStepsCountDomainDao
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveDayEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFileReceiptEntity
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_STEPS_RUN_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsPartialCause
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOperation
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerKind
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsEntryV1
import com.adsamcik.tracker.shared.model.steps.portable.StepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.deletionScopeIdentity
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
): List<AuthenticatedImportedAmbientStepsGraphRevision> = withTransaction {
	loadAuthenticatedImportedAmbientStepsGraphLineageInCurrentTransaction(lineage)
}

private suspend fun AppDatabase.loadAuthenticatedImportedAmbientStepsGraphLineageInCurrentTransaction(
	lineage: AuthenticatedImportedAmbientStepsLineage,
): List<AuthenticatedImportedAmbientStepsGraphRevision> {
	val dayIdentity = lineage.revisions.lastOrNull()?.header?.dayIdentity
	val bindings = dayIdentity?.let {
		importedPortableStepsCountDomainDao().bindingsForProduct(
			ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
			it,
			ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY + 1,
		)
	}.orEmpty()
	check(bindings.size <= ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY)
	val graphs = loadAuthenticatedImportedAmbientGraphs(bindings)
	try {
		return authenticateImportedAmbientStepsGraphLineage(lineage, bindings, graphs)
	} catch (failure: IllegalArgumentException) {
		if (!reconcilePreviouslyTruncatedLegacyAmbientGraph(lineage, bindings, graphs)) {
			throw failure
		}
	} catch (failure: IllegalStateException) {
		if (!reconcilePreviouslyTruncatedLegacyAmbientGraph(lineage, bindings, graphs)) {
			throw failure
		}
	} catch (failure: ArithmeticException) {
		if (!reconcilePreviouslyTruncatedLegacyAmbientGraph(lineage, bindings, graphs)) {
			throw failure
		}
	}
	val repairedBindings = importedPortableStepsCountDomainDao().bindingsForProduct(
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
		requireNotNull(dayIdentity),
		ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY + 1,
	)
	check(repairedBindings.size <= ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY)
	val repairedLineage = importedAmbientStepsDao().loadAuthenticatedAmbientStepsLineage(
		dayIdentity,
		lineage.latest.header.collectedDataEpoch,
	)
	return authenticateImportedAmbientStepsGraphLineage(
		repairedLineage,
		repairedBindings,
		loadAuthenticatedImportedAmbientGraphs(repairedBindings),
	)
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
): AuthenticatedImportedPortableGraphBinding? = withTransaction {
	val expected = entry.legacyUnprovenCountDomainGraph()
	val loaded = loadImportedSessionCountDomainBinding(
		entry.metadata.identity,
		entry.metadata.contentChecksum,
	)
	if (loaded == null) {
		requireGraphlessLegacySessionProvenance(entry, expected)
		return@withTransaction null
	}
	try {
		authenticateImportedSessionBinding(
			loaded.binding,
			loaded.graph,
			loaded.bindingReceipt,
			expected,
		)
		return@withTransaction AuthenticatedImportedPortableGraphBinding(
			loaded.binding,
			loaded.graph,
		)
	} catch (failure: IllegalArgumentException) {
		if (!reconcilePreviouslyTruncatedLegacySessionBinding(entry, loaded, expected)) {
			throw failure
		}
	} catch (failure: IllegalStateException) {
		if (!reconcilePreviouslyTruncatedLegacySessionBinding(entry, loaded, expected)) {
			throw failure
		}
	}
	val repaired = requireNotNull(
		loadImportedSessionCountDomainBinding(
			entry.metadata.identity,
			entry.metadata.contentChecksum,
		),
	)
	authenticateImportedSessionBinding(
		repaired.binding,
		repaired.graph,
		repaired.bindingReceipt,
		expected,
	)
	AuthenticatedImportedPortableGraphBinding(repaired.binding, repaired.graph)
}

suspend fun AppDatabase.loadAuthenticatedImportedSessionCountDomainBinding(
	entry: PortableStepsEntryV1,
): AuthenticatedImportedPortableGraphBinding? = withTransaction {
	val expected = legacyUnprovenStepsCountDomainGraph(entry.contentChecksum, entry.runs)
	val loaded = loadImportedSessionCountDomainBinding(
		entry.identity.value,
		entry.contentChecksum.value,
	)
	if (loaded == null) {
		requireGraphlessLegacySessionProvenance(entry)
		return@withTransaction null
	}
	authenticateImportedSessionBinding(
		loaded.binding,
		loaded.graph,
		loaded.bindingReceipt,
		expected,
	)
	AuthenticatedImportedPortableGraphBinding(loaded.binding, loaded.graph)
}

private suspend fun AppDatabase.loadImportedSessionCountDomainBinding(
	entryIdentity: String,
	expectedProductChecksum: String,
): LoadedImportedSessionCountDomainBinding? {
	val dao = importedPortableStepsCountDomainDao()
	val bindings = dao.bindingsForProduct(
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
		entryIdentity,
		2,
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
	val fileReceipts = loadBoundedSessionFileReceipts(entryIdentity)
	authenticateImportedSessionFileReceipts(
		binding,
		fileReceipts,
		expectedProductChecksum,
	)
	return LoadedImportedSessionCountDomainBinding(
		binding = binding,
		graph = graph,
		bindingReceipt = fileReceipt,
		fileReceipts = fileReceipts,
		fileReceiptCount = fileReceipts.size,
	)
}

private suspend fun AppDatabase.loadBoundedSessionFileReceipts(
	entryIdentity: String,
): List<ImportedPortableStepsFileReceiptEntity> {
	val dao = importedPortableStepsCountDomainDao()
	val result = mutableListOf<ImportedPortableStepsFileReceiptEntity>()
	var afterJobId: String? = null
	var afterEntryKey: String? = null
	while (true) {
		val remaining = ImportedPortableStepsCountDomainDao.MAX_FILE_RECEIPTS_PER_ENTRY -
			result.size + 1
		val page = dao.fileReceiptPageForEntry(
			entryIdentity = entryIdentity,
			afterJobId = afterJobId,
			afterEntryKey = afterEntryKey,
			limit = minOf(
				ImportedPortableStepsCountDomainDao.FILE_RECEIPT_PAGE_SIZE,
				remaining,
			),
		)
		result += page
		check(
			result.size <= ImportedPortableStepsCountDomainDao.MAX_FILE_RECEIPTS_PER_ENTRY,
		) {
			"Imported Steps file provenance exceeds its per-entry bound"
		}
		if (page.size < ImportedPortableStepsCountDomainDao.FILE_RECEIPT_PAGE_SIZE ||
			result.size == ImportedPortableStepsCountDomainDao.MAX_FILE_RECEIPTS_PER_ENTRY
		) {
			if (result.size == ImportedPortableStepsCountDomainDao.MAX_FILE_RECEIPTS_PER_ENTRY) {
				val last = result.last()
				check(
					dao.fileReceiptPageForEntry(
						entryIdentity,
						last.importJobId,
						last.entryKey,
						1,
					).isEmpty(),
				) {
					"Imported Steps file provenance exceeds its per-entry bound"
				}
			}
			break
		}
		val last = page.last()
		afterJobId = last.importJobId
		afterEntryKey = last.entryKey
	}
	return result
}

private fun authenticateImportedSessionFileReceipts(
	binding: ImportedPortableStepsCountDomainBindingEntity,
	receipts: List<ImportedPortableStepsFileReceiptEntity>,
	expectedProductChecksum: String,
) {
	check(receipts.map { it.importJobId to it.entryKey }.distinct().size == receipts.size)
	check(receipts.map { it.receiptIdentity }.distinct().size == receipts.size)
	receipts.forEach { receipt ->
		check(receipt.entryIdentity == binding.productIdentity)
		check(receipt.graphIdentity == binding.graphIdentity)
		check(receipt.entryOrdinal in 0 until StepsPortableFormatV1.MAX_ENTRIES)
		if (binding.sourceSchemaVersion == StepsPortableFormatV1.SCHEMA_VERSION) {
			check(receipt.archiveContentChecksum == expectedProductChecksum)
		}
	}
	val sourceReceiptIdentity = binding.sourceReceiptIdentity ?: return
	val sourceReceipt = receipts.singleOrNull {
		it.receiptIdentity == sourceReceiptIdentity
	}
	checkNotNull(sourceReceipt)
	check(sourceReceipt.archiveContentChecksum == binding.sourceArchiveContentChecksum)
}

private suspend fun AppDatabase.loadAuthenticatedImportedAmbientGraphs(
	bindings: List<ImportedPortableStepsCountDomainBindingEntity>,
): Map<String, PortableCountDomainGraphV2> {
	val dao = importedPortableStepsCountDomainDao()
	val graphs = linkedMapOf<String, PortableCountDomainGraphV2>()
	bindings.forEach { binding ->
		graphs[binding.graphIdentity] = checkNotNull(
			dao.authenticatedGraph(
				binding.graphIdentity,
				ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS,
			),
		)
	}
	return graphs
}

internal data class LoadedImportedSessionCountDomainBinding(
	val binding: ImportedPortableStepsCountDomainBindingEntity,
	val graph: PortableCountDomainGraphV2,
	val bindingReceipt: ImportedPortableStepsFileReceiptEntity?,
	val fileReceipts: List<ImportedPortableStepsFileReceiptEntity>,
	val fileReceiptCount: Int,
)

@Suppress("LongParameterList")
internal suspend fun AppDatabase.requireNoImportedPortableGraphEraEvidence(
	productKind: String,
	productIdentity: String,
	graphIdentities: Collection<String>,
	containerIdentities: Collection<String>,
	productIdentities: Collection<String>,
	ownerIdentities: Collection<String>,
) {
	val dao = importedPortableStepsCountDomainDao()
	require(dao.bindingEvidenceCountForProduct(productKind, productIdentity) == 0) {
		"Graphless legacy product has stored count-domain binding evidence"
	}
	graphIdentities.distinct().chunked(PORTABLE_EVIDENCE_QUERY_BATCH).forEach { identities ->
		require(dao.graphEvidenceCount(identities) == 0) {
			"Graphless legacy product has stored graph evidence"
		}
		require(dao.bindingEvidenceCountForGraphs(identities) == 0) {
			"Graphless legacy product has orphaned graph binding evidence"
		}
	}
	containerIdentities.distinct().chunked(PORTABLE_EVIDENCE_QUERY_BATCH).forEach { identities ->
		require(dao.rootEvidenceCountForContainers(identities) == 0) {
			"Graphless legacy product has orphaned container-root evidence"
		}
	}
	productIdentities.distinct().chunked(PORTABLE_EVIDENCE_QUERY_BATCH).forEach { identities ->
		require(dao.rootEvidenceCountForProducts(identities) == 0) {
			"Graphless legacy product has orphaned product-root evidence"
		}
	}
	ownerIdentities.distinct().chunked(PORTABLE_EVIDENCE_QUERY_BATCH).forEach { identities ->
		require(dao.receiptEvidenceCountForOwners(identities) == 0) {
			"Graphless legacy product has v2 count-domain receipt evidence"
		}
		require(dao.ownerEvidenceCount(identities) == 0) {
			"Graphless legacy product has orphaned owner evidence"
		}
		require(dao.completenessEvidenceCount(identities) == 0) {
			"Graphless legacy product has orphaned completeness evidence"
		}
		require(dao.rootEvidenceCountForOwners(identities) == 0) {
			"Graphless legacy product has orphaned owner-root evidence"
		}
		require(dao.ownerFenceEvidenceCount(identities) == 0) {
			"Graphless legacy product has terminal owner evidence"
		}
	}
}

internal suspend fun AppDatabase.requireGraphlessLegacySessionProvenance(
	entry: RetainedImportedStepsEntry,
	graph: PortableCountDomainGraphV2 = entry.legacyUnprovenCountDomainGraph(),
) {
	val dao = importedPortableStepsCountDomainDao()
	require(dao.fileReceiptCountForEntry(entry.metadata.identity) == 0) {
		"Graphless legacy Steps product has durable file provenance"
	}
	requireNoImportedPortableGraphEraEvidence(
		productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
		productIdentity = entry.metadata.identity,
		graphIdentities = listOf(graph.identity.value),
		containerIdentities = graph.roots.map { it.containerIdentity.value },
		productIdentities = buildList {
			add(entry.metadata.identity)
			addAll(graph.roots.map { it.productIdentity.value })
		},
		ownerIdentities = graph.roots.map { it.ownerIdentity.value },
	)
}

internal suspend fun AppDatabase.requireGraphlessLegacySessionProvenance(
	entry: PortableStepsEntryV1,
) {
	val graph = legacyUnprovenStepsCountDomainGraph(entry.contentChecksum, entry.runs)
	val dao = importedPortableStepsCountDomainDao()
	require(dao.fileReceiptCountForEntry(entry.identity.value) == 0) {
		"Graphless legacy Steps product has durable file provenance"
	}
	requireNoImportedPortableGraphEraEvidence(
		productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
		productIdentity = entry.identity.value,
		graphIdentities = listOf(graph.identity.value),
		containerIdentities = graph.roots.map { it.containerIdentity.value },
		productIdentities = buildList {
			add(entry.identity.value)
			addAll(graph.roots.map { it.productIdentity.value })
		},
		ownerIdentities = graph.roots.map { it.ownerIdentity.value },
	)
}

internal suspend fun AppDatabase.requireGraphlessLegacyAmbientProvenance(
	lineage: AuthenticatedImportedAmbientStepsLineage,
	graphLineage: List<AuthenticatedImportedAmbientStepsGraphRevision>,
) {
	require(lineage.archives.isNotEmpty())
	require(lineage.archives.all {
		it.sourceSchemaVersion == AmbientStepsPortableFormatV1.SCHEMA_VERSION
	}) {
		"Missing Ambient graph binding has explicit v2 source provenance"
	}
	val dayIdentity = lineage.latest.header.dayIdentity
	requireNoImportedPortableGraphEraEvidence(
		productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
		productIdentity = dayIdentity,
		graphIdentities = graphLineage.map { it.graph.identity.value },
		containerIdentities = listOf(dayIdentity),
		productIdentities = buildList {
			add(dayIdentity)
			graphLineage.forEach { revision ->
				addAll(revision.graph.roots.map { it.productIdentity.value })
			}
		},
		ownerIdentities = graphLineage.flatMap { revision ->
			revision.graph.roots.map { it.ownerIdentity.value }
		},
	)
}

internal fun reconstructGraphlessLegacyAmbientLineage(
	lineage: AuthenticatedImportedAmbientStepsLineage,
): List<AuthenticatedImportedAmbientStepsGraphRevision> {
	val dayIdentity = lineage.latest.header.dayIdentity
	val members = lineage.archiveDays.filter { it.dayIdentity == dayIdentity }
	require(members.isNotEmpty())
	val revisions = lineage.revisions.associateBy { it.header.importRevision }
	val archives = lineage.archives.associateBy { it.archiveIdentity }
	val receipts = lineage.receipts.groupBy { it.archiveIdentity }
	val membersByGraphRevision = members.groupBy { it.boundCountDomainGraphRevision }
		.toSortedMap()
	require(
		membersByGraphRevision.keys.toList() ==
			(1L..membersByGraphRevision.size.toLong()).toList(),
	)
	return membersByGraphRevision.map { (graphRevision, graphMembers) ->
		val sourceMembers = graphMembers.sortedWith(
			compareBy(
				ImportedAmbientStepsArchiveDayEntity::archiveIdentity,
				ImportedAmbientStepsArchiveDayEntity::ordinal,
			),
		)
		val graphs = sourceMembers.map { member ->
			val archive = requireNotNull(archives[member.archiveIdentity])
			require(archive.sourceSchemaVersion == AmbientStepsPortableFormatV1.SCHEMA_VERSION)
			requireNotNull(revisions[member.boundDayImportRevision])
				.day
				.withExplicitUnprovenCountDomain(graphRevision)
				.countDomainGraph
		}.distinct()
		require(graphs.size == 1) {
			"Legacy Ambient Steps graph revision has conflicting synthetic ownership"
		}
		val source = sourceMembers.first()
		val archive = requireNotNull(archives[source.archiveIdentity])
		val receipt = receipts.getValue(source.archiveIdentity).minWith(
			compareBy(
				ImportedAmbientStepsReceiptEntity::importJobId,
				ImportedAmbientStepsReceiptEntity::archiveKey,
			),
		)
		AuthenticatedImportedAmbientStepsGraphRevision(
			binding = ImportedPortableStepsCountDomainBindingEntity(
				productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
				productIdentity = dayIdentity,
				productRevision = graphRevision,
				graphIdentity = graphs.single().identity.value,
				sourceSchemaVersion = AmbientStepsPortableFormatV1.SCHEMA_VERSION,
				sourceReceiptIdentity = receipt.receiptIdentity,
				sourceArchiveIdentity = archive.archiveIdentity,
				sourceArchiveContentChecksum = archive.contentChecksum,
			),
			productImportRevisions =
				sourceMembers.mapTo(linkedSetOf()) { it.boundDayImportRevision },
			graph = graphs.single(),
		)
	}.also { graphLineage ->
		require(lineage.latest.header.importRevision in
			graphLineage.last().productImportRevisions)
	}
}

/** Reconstructs legacy v1 graphless authority only after all graph-era evidence is absent. */
suspend fun AppDatabase.loadProvenGraphlessLegacyAmbientLineage(
	lineage: AuthenticatedImportedAmbientStepsLineage,
): List<AuthenticatedImportedAmbientStepsGraphRevision> =
	reconstructGraphlessLegacyAmbientLineage(lineage).also {
		requireGraphlessLegacyAmbientProvenance(lineage, it)
	}

/** Installs proven graphless v1 authority only inside the caller's destructive transaction. */
suspend fun AppDatabase.authenticateOrInstallGraphlessLegacyAmbientLineage(
	lineage: AuthenticatedImportedAmbientStepsLineage,
): List<AuthenticatedImportedAmbientStepsGraphRevision> {
	val existing = importedPortableStepsCountDomainDao().bindingsForProduct(
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
		lineage.latest.header.dayIdentity,
		ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY + 1,
	)
	require(existing.size <= ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY)
	if (existing.isNotEmpty()) {
		return loadAuthenticatedImportedAmbientStepsGraphLineageInCurrentTransaction(lineage)
	}
	val reconstructed = loadProvenGraphlessLegacyAmbientLineage(lineage)
	val dao = importedPortableStepsCountDomainDao()
	reconstructed.forEach { revision ->
		dao.insertAuthenticatedGraph(
			revision.graph,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS,
		)
		dao.insertBinding(revision.binding)
	}
	return loadAuthenticatedImportedAmbientStepsGraphLineageInCurrentTransaction(lineage)
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

internal fun AppDatabase.loadImportedSessionCountDomainBindingForFullClear(
	entryIdentity: String,
): LoadedImportedSessionCountDomainBinding? {
	val dao = importedPortableStepsCountDomainDao()
	val bindings = dao.bindingsForFullClear(
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
		entryIdentity,
		2,
	)
	if (bindings.isEmpty()) return null
	check(bindings.size == 1)
	val binding = bindings.single()
	check(binding.productRevision == IMPORTED_SESSION_PRODUCT_REVISION)
	val graph = checkNotNull(
		dao.authenticatedGraphForFullClear(
			binding.graphIdentity,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
		),
	)
	val bindingReceipt = binding.sourceReceiptIdentity?.let { receiptIdentity ->
		checkNotNull(dao.fileReceiptByIdentityForFullClear(receiptIdentity))
	}
	return LoadedImportedSessionCountDomainBinding(
		binding = binding,
		graph = graph,
		bindingReceipt = bindingReceipt,
		fileReceipts = listOfNotNull(bindingReceipt),
		fileReceiptCount = dao.fileReceiptCountForEntryForFullClear(entryIdentity),
	)
}

internal fun RetainedImportedStepsEntry.legacyUnprovenCountDomainGraph(): PortableCountDomainGraphV2 =
	legacyUnprovenStepsCountDomainGraph(
		com.adsamcik.tracker.shared.model.steps.portable.PortableStepsDigest(
			metadata.contentChecksum,
		),
		portableRunsById.values.sortedWith(PORTABLE_STEPS_RUN_ORDER),
	)

@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount", "ComplexCondition")
private suspend fun AppDatabase.reconcilePreviouslyTruncatedLegacyAmbientGraph(
	lineage: AuthenticatedImportedAmbientStepsLineage,
	bindings: List<ImportedPortableStepsCountDomainBindingEntity>,
	graphs: Map<String, PortableCountDomainGraphV2>,
): Boolean {
	if (lineage.revisions.size < 2 || bindings.isEmpty() ||
		bindings.any { it.sourceSchemaVersion != AmbientStepsPortableFormatV1.SCHEMA_VERSION } ||
		lineage.archives.any {
			it.sourceSchemaVersion != AmbientStepsPortableFormatV1.SCHEMA_VERSION
		}
	) {
		return false
	}
	val dayIdentity = lineage.latest.header.dayIdentity
	val members = lineage.archiveDays.filter { it.dayIdentity == dayIdentity }
	if (members.isEmpty() ||
		bindings.size >= ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY ||
		bindings.map { it.productRevision } != (1L..bindings.size.toLong()).toList() ||
		graphs.keys != bindings.mapTo(linkedSetOf()) { it.graphIdentity } ||
		bindings.map { it.graphIdentity }.distinct().size != bindings.size
	) {
		return false
	}
	val revisionsByNumber = lineage.revisions.associateBy { it.header.importRevision }
	val archivesByIdentity = lineage.archives.associateBy { it.archiveIdentity }
	val receiptsByIdentity = lineage.receipts.associateBy { it.receiptIdentity }
	if (revisionsByNumber.size != lineage.revisions.size ||
		archivesByIdentity.size != lineage.archives.size ||
		receiptsByIdentity.size != lineage.receipts.size
	) {
		return false
	}
	bindings.forEach { binding ->
		if (!binding.hasAuthenticatedLegacyAmbientProvenance(
				dayIdentity,
				archivesByIdentity,
				receiptsByIdentity,
			)
		) {
			return false
		}
	}
	val latestRevision = lineage.latest.header.importRevision
	val latestMembers = members.filter { it.boundDayImportRevision == latestRevision }
	if (latestMembers.isEmpty()) return false
	val previousGraphRevision = latestMembers
		.mapTo(linkedSetOf()) { it.boundCountDomainGraphRevision }
		.singleOrNull() ?: return false
	val previousBinding = bindings.singleOrNull {
		it.productRevision == previousGraphRevision
	} ?: return false
	if (previousBinding != bindings.last()) return false
	val previousGraph = graphs[previousBinding.graphIdentity] ?: return false
	val previousMembers = members.filter {
		it.boundCountDomainGraphRevision == previousGraphRevision &&
			it.boundDayImportRevision != latestRevision
	}
	val priorRevision = previousMembers.mapNotNull { member ->
		revisionsByNumber[member.boundDayImportRevision]
	}.distinct().singleOrNull {
		it.day.withExplicitUnprovenCountDomain(previousGraphRevision).countDomainGraph ==
			previousGraph
	} ?: return false
	if (priorRevision.header.importRevision >= latestRevision) return false
	bindings.forEach { binding ->
		val graph = graphs.getValue(binding.graphIdentity)
		val revisionMembers = members.filter {
			it.boundCountDomainGraphRevision == binding.productRevision
		}
		if (revisionMembers.isEmpty() ||
			revisionMembers.none { it.archiveIdentity == binding.sourceArchiveIdentity }
		) {
			return false
		}
		revisionMembers.filterNot {
			binding == previousBinding && it.boundDayImportRevision == latestRevision
		}.forEach { member ->
			val revision = revisionsByNumber[member.boundDayImportRevision] ?: return false
			if (revision.day.withExplicitUnprovenCountDomain(binding.productRevision)
					.countDomainGraph != graph
			) {
				return false
			}
		}
	}
	val previousDay = priorRevision.day
	val retainedDay = lineage.latest.day
	val retainedFromMs = retainedDay.retainedFromTimeMs ?: return false
	if (PortableAmbientStepsPartialCause.RETENTION !in retainedDay.partialCauses ||
		retainedDay.structuralDayStartTimeMs != previousDay.structuralDayStartTimeMs ||
		retainedDay.structuralDayEndTimeMs != previousDay.structuralDayEndTimeMs ||
		retainedDay.storedZoneId != previousDay.storedZoneId ||
		retainedDay.structuralEpochDay != previousDay.structuralEpochDay ||
		retainedDay.deletionScopeIdentity != previousDay.deletionScopeIdentity ||
		previousDay.retainedFromTimeMs?.let { it >= retainedFromMs } == true
	) {
		return false
	}
	val previousFacts = previousDay.facts.associateBy { it.identity.value }
	val retainedFacts = retainedDay.facts.associateBy { it.identity.value }
	if (previousFacts.size != previousDay.facts.size ||
		retainedFacts.size != retainedDay.facts.size ||
		retainedFacts.keys.isEmpty() ||
		!previousFacts.keys.containsAll(retainedFacts.keys) ||
		previousFacts.keys == retainedFacts.keys ||
		retainedFacts.any { (identity, fact) -> previousFacts[identity] != fact } ||
		retainedFacts.values.any { it.intervalStartTimeMs < retainedFromMs }
	) {
		return false
	}
	val removedFactIdentities = previousFacts.keys - retainedFacts.keys
	if (removedFactIdentities.any {
			requireNotNull(previousFacts[it]).intervalStartTimeMs >= retainedFromMs
		}
	) {
		return false
	}
	val removedRoots = previousGraph.roots.filter {
		it.ownerKind == PortableCountDomainOwnerKind.AMBIENT_FACT &&
			it.containerIdentity.value == dayIdentity &&
			it.productIdentity.value in removedFactIdentities
	}
	if (removedRoots.mapTo(linkedSetOf()) { it.productIdentity.value } != removedFactIdentities) {
		return false
	}
	val retainedRootKeys = retainedFacts.keys
	val previousRetainedRootKeys = previousGraph.roots.filter {
		it.ownerKind == PortableCountDomainOwnerKind.AMBIENT_FACT &&
			it.containerIdentity.value == dayIdentity &&
			it.productIdentity.value in retainedRootKeys
	}.mapTo(linkedSetOf()) { it.productIdentity.value }
	if (previousRetainedRootKeys != retainedRootKeys ||
		previousGraph.receipts.isNotEmpty() ||
		previousGraph.ownerRevisions.any {
			it.ownerKind != PortableCountDomainOwnerKind.AMBIENT_FACT ||
				it.ownerRevision != previousGraphRevision ||
				it.operation != PortableCountDomainOperation.UNPROVEN ||
				it.receiptIdentity != null
		} ||
		previousGraph.completenessMarkers.isNotEmpty()
	) {
		return false
	}
	val retainedOwnerIdentities = previousGraph.roots.filter {
		it.productIdentity.value in retainedRootKeys
	}.map { it.ownerIdentity.value }.distinct()
	val retainedOwnerFences = retainedOwnerIdentities.chunked(PORTABLE_EVIDENCE_QUERY_BATCH)
		.flatMap { identities ->
			importedPortableStepsCountDomainDao().ownerFences(
				identities,
				identities.size + 1,
			)
		}
	if (retainedOwnerFences.isNotEmpty()) return false
	val replacementRevision = Math.addExact(bindings.last().productRevision, 1L)
	val graphDao = importedPortableStepsCountDomainDao()
	insertOrAuthenticateImportedPortableOwnerFences(
		authenticatedImportedPortableSelectedOwnerFences(
			selections = listOf(
				AuthenticatedImportedPortableRootSelection(
					authenticated = AuthenticatedImportedPortableGraphBinding(
						previousBinding,
						previousGraph,
					),
					selectedRoots = removedRoots,
				),
			),
			fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_RETENTION,
			collectedDataEpoch = lineage.latest.header.collectedDataEpoch,
			fencedAtMs = lineage.latest.header.receivedAtMs,
			maximumFenceCount = ImportedAmbientStepsDao.MAX_TOTAL_FACT_ROWS_PER_LINEAGE,
		),
	)
	val replacementGraph = retainedDay
		.withExplicitUnprovenCountDomain(replacementRevision)
		.countDomainGraph
	if (graphDao.graph(replacementGraph.identity.value) != null ||
		graphDao.bindingEvidenceCountForGraphs(listOf(replacementGraph.identity.value)) != 0
	) {
		return false
	}
	val currentArchive = archivesByIdentity[lineage.latest.header.archiveIdentity] ?: return false
	val currentReceipt = lineage.receipts
		.filter { it.archiveIdentity == currentArchive.archiveIdentity }
		.minWithOrNull(
			compareBy(
				ImportedAmbientStepsReceiptEntity::importJobId,
				ImportedAmbientStepsReceiptEntity::archiveKey,
			),
		) ?: return false
	val replacementBinding = ImportedPortableStepsCountDomainBindingEntity(
		productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
		productIdentity = dayIdentity,
		productRevision = replacementRevision,
		graphIdentity = replacementGraph.identity.value,
		sourceSchemaVersion = AmbientStepsPortableFormatV1.SCHEMA_VERSION,
		sourceReceiptIdentity = currentReceipt.receiptIdentity,
		sourceArchiveIdentity = currentArchive.archiveIdentity,
		sourceArchiveContentChecksum = currentArchive.contentChecksum,
	)
	graphDao.insertAuthenticatedGraph(
		replacementGraph,
		ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS,
	)
	graphDao.insertBinding(replacementBinding)
	check(
		importedAmbientStepsDao().rebindDayRevisionCountDomainGraph(
			dayIdentity = dayIdentity,
			dayImportRevision = latestRevision,
			expectedGraphRevision = previousGraphRevision,
			newGraphRevision = replacementRevision,
		) == latestMembers.size,
	) {
		"Legacy Ambient Steps graph membership changed during upgrade"
	}
	return true
}

private fun ImportedPortableStepsCountDomainBindingEntity.hasAuthenticatedLegacyAmbientProvenance(
	dayIdentity: String,
	archivesByIdentity: Map<String, ImportedAmbientStepsArchiveEntity>,
	receiptsByIdentity: Map<String, ImportedAmbientStepsReceiptEntity>,
): Boolean {
	if (productKind != ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY ||
		productIdentity != dayIdentity ||
		sourceSchemaVersion != AmbientStepsPortableFormatV1.SCHEMA_VERSION
	) {
		return false
	}
	val archiveIdentity = sourceArchiveIdentity ?: return false
	val archiveChecksum = sourceArchiveContentChecksum ?: return false
	val receiptIdentity = sourceReceiptIdentity ?: return false
	val archive = archivesByIdentity[archiveIdentity] ?: return false
	val receipt = receiptsByIdentity[receiptIdentity] ?: return false
	return archive.sourceSchemaVersion == AmbientStepsPortableFormatV1.SCHEMA_VERSION &&
		archive.contentChecksum == archiveChecksum &&
		receipt.archiveIdentity == archiveIdentity &&
		receipt.archiveContentChecksum == archiveChecksum
}

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

internal fun com.adsamcik.tracker.shared.base.database.dao.ImportedPortableStepsCountDomainDao
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
private const val PORTABLE_EVIDENCE_QUERY_BATCH = 400
