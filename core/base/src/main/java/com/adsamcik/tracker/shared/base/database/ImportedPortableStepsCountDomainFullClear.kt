package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedTraversal
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerKind
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerRevisionV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainRootV2
import com.adsamcik.tracker.shared.model.steps.portable.StepsPortableFormatV1
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Converts every live imported portable owner into a value-free terminal fence before full clear.
 */
internal suspend fun AppDatabase.preserveImportedPortableCountDomainFullClearFences(
	operationId: String,
	oldCollectedDataEpoch: Long,
	newCollectedDataEpoch: Long,
	fencedAtMs: Long,
) {
	require(operationId.isNotBlank())
	require(oldCollectedDataEpoch >= 0L)
	require(newCollectedDataEpoch > oldCollectedDataEpoch)
	require(fencedAtMs >= 0L)
	val coroutineContext = currentCoroutineContext()
	val sqlite = openHelper.writableDatabase
	check(sqlite.inTransaction()) {
		"Imported portable full clear requires the owning Room transaction"
	}
	reconcileImportedPortableLegacyGraphsBeforeFullClear(oldCollectedDataEpoch)
	val dao = importedPortableStepsCountDomainDao()
	val staging = AuthenticatedFullClearOwnerFenceStaging(
		sqlite = sqlite,
		operationId = operationId,
		fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_FULL_CLEAR,
		collectedDataEpoch = newCollectedDataEpoch,
		fencedAtMs = fencedAtMs,
		maximumOwnerCount = null,
		maximumOwnerRevisionCount = null,
		checkpoint = coroutineContext::ensureActive,
	)
	try {
		stageImportedPortableBindingsForFullClear(dao, staging)
		val consumeAuthenticatedGraph:
			(AuthenticatedImportedPortableGraphBinding, Boolean) -> Unit = { current, isStored ->
			staging.consumeAuthenticatedGraph(current, isStored)
		}
		authenticatedImportedSessionBindingsForFullClear(
			consumeAuthenticatedGraph,
			staging,
		)
		authenticatedImportedAmbientBindingsForFullClear(
			oldCollectedDataEpoch,
			consumeAuthenticatedGraph,
			staging,
		)
		staging.requireEveryBindingConsumed()
		authenticateAndFoldOrphanPortableGraphsForFullClear(
			staging = staging,
		) { orphan ->
			staging.add(orphan)
		}
		authenticateAllPortableSessionFileReceiptsForFullClear(
			sessionProducts = staging,
		)
		staging.install(dao)
		dao.deleteAllBindingsForFullClear()
		dao.deleteAllGraphsForFullClear()
		dao.deleteAllFileReceiptsForFullClear()
	} finally {
		staging.close()
	}
}

private fun stageImportedPortableBindingsForFullClear(
	dao: com.adsamcik.tracker.shared.base.database.dao.ImportedPortableStepsCountDomainDao,
	staging: AuthenticatedFullClearOwnerFenceStaging,
) {
	var afterProductKind: String? = null
	var afterProductIdentity: String? = null
	var afterProductRevision: Long? = null
	while (true) {
		val page = dao.bindingPageForFullClear(
			afterProductKind,
			afterProductIdentity,
			afterProductRevision,
			BINDING_FULL_CLEAR_PAGE_SIZE,
		)
		if (page.isEmpty()) {
			break
		}
		require(page.size <= BINDING_FULL_CLEAR_PAGE_SIZE)
		page.forEach { binding ->
			requireBindingKeyAfter(binding, afterProductKind, afterProductIdentity, afterProductRevision)
			staging.addBinding(binding)
		}
		val last = page.last()
		afterProductKind = last.productKind
		afterProductIdentity = last.productIdentity
		afterProductRevision = last.productRevision
		if (page.size < BINDING_FULL_CLEAR_PAGE_SIZE) {
			break
		}
	}
}

// One immutable composite key must be after the supplied previous page key, using the original strict ordering.
@Suppress("ComplexCondition")
private fun requireBindingKeyAfter(
	binding: ImportedPortableStepsCountDomainBindingEntity,
	afterProductKind: String?,
	afterProductIdentity: String?,
	afterProductRevision: Long?,
) {
	require(
		afterProductKind == null ||
			binding.productKind > afterProductKind!! ||
			(binding.productKind == afterProductKind &&
				binding.productIdentity > afterProductIdentity!!) ||
			(binding.productKind == afterProductKind &&
				binding.productIdentity == afterProductIdentity &&
				binding.productRevision > afterProductRevision!!),
	) {
		"Imported portable binding keyset did not advance"
	}
}

private suspend fun AppDatabase.authenticatedImportedSessionBindingsForFullClear(
	consume: (AuthenticatedImportedPortableGraphBinding, Boolean) -> Unit,
	staging: AuthenticatedFullClearOwnerFenceStaging,
) {
	val reader = ImportedStepsRetainedReader(this)
	when (
		val traversal = reader.forEachEntryForRetentionInTransaction { product ->
			val binding = staging.singleBinding(
				ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
				product.metadata.identity,
			)
			val authenticated = if (binding == null) {
				val graph = product.legacyUnprovenCountDomainGraph()
				requireGraphlessLegacySessionProvenance(product, graph)
				AuthenticatedImportedPortableGraphBinding(
					ImportedPortableStepsCountDomainBindingEntity(
						productKind =
							ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
						productIdentity = product.metadata.identity,
						productRevision = SESSION_PRODUCT_REVISION,
						graphIdentity = graph.identity.value,
						sourceSchemaVersion = StepsPortableFormatV1.SCHEMA_VERSION,
					),
					graph,
				)
			} else {
				loadAuthenticatedImportedSessionCountDomainBindingForFullClear(binding, product)
			}
			requireSessionGraphCoversRetainedProduct(authenticated, product)
			staging.addSessionProduct(product, authenticated, binding != null)
			consume(authenticated, binding != null)
		}
	) {
		is ImportedStepsRetainedTraversal.Complete -> Unit
		is ImportedStepsRetainedTraversal.Unverifiable -> error(
			"Imported Steps product is unverifiable during full clear: " +
				"${traversal.entryIdentity}:${traversal.reason}",
		)
	}
}

private suspend fun AppDatabase.authenticatedImportedAmbientBindingsForFullClear(
	oldCollectedDataEpoch: Long,
	consume: (AuthenticatedImportedPortableGraphBinding, Boolean) -> Unit,
	staging: AuthenticatedFullClearOwnerFenceStaging,
) {
	val ambientDao = importedAmbientStepsDao()
	var afterDayId: String? = null
	while (true) {
		val page = ambientDao.fullClearDayCandidatePage(afterDayId, AMBIENT_FULL_CLEAR_PAGE_SIZE)
		if (page.isEmpty()) {
			break
		}
		require(page.size <= AMBIENT_FULL_CLEAR_PAGE_SIZE)
		page.forEach { candidate ->
			val lineage = ambientDao.loadAuthenticatedAmbientStepsLineageForFullClear(
				candidate,
				oldCollectedDataEpoch,
			)
			consumeAuthenticatedAmbientBindingLineage(candidate.dayIdentity, lineage, consume, staging)
		}
		afterDayId = page.last().dayIdentity
		if (page.size < AMBIENT_FULL_CLEAR_PAGE_SIZE) {
			break
		}
	}
}

private suspend fun AppDatabase.consumeAuthenticatedAmbientBindingLineage(
	dayIdentity: String,
	lineage: AuthenticatedImportedAmbientStepsLineage,
	consume: (AuthenticatedImportedPortableGraphBinding, Boolean) -> Unit,
	staging: AuthenticatedFullClearOwnerFenceStaging,
) {
	val storedBindings = staging.bindings(
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
		dayIdentity,
		ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY + 1,
	)
	require(storedBindings.size <= ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY)
	val graphLineage = if (storedBindings.isEmpty()) {
		reconstructGraphlessLegacyAmbientLineage(lineage).also {
			requireGraphlessLegacyAmbientProvenance(lineage, it)
		}
	} else {
		loadAuthenticatedImportedAmbientStepsGraphLineageForFullClear(lineage).also {
			require(it.map(AuthenticatedImportedAmbientStepsGraphRevision::binding) ==
				storedBindings)
		}
	}
	graphLineage.forEach {
		consume(
			AuthenticatedImportedPortableGraphBinding(it.binding, it.graph),
			storedBindings.isNotEmpty(),
		)
	}
}

private fun AppDatabase.authenticateAndFoldOrphanPortableGraphsForFullClear(
	staging: AuthenticatedFullClearOwnerFenceStaging,
	consume: (AuthenticatedFullClearGraphAppearance) -> Unit,
) {
	val dao = importedPortableStepsCountDomainDao()
	var afterGraphIdentity: String? = null
	while (true) {
		val page = dao.graphPageForFullClear(afterGraphIdentity, GRAPH_FULL_CLEAR_PAGE_SIZE)
		if (page.isEmpty()) {
			break
		}
		require(page.size <= GRAPH_FULL_CLEAR_PAGE_SIZE)
		page.forEach { graphRow ->
			require(afterGraphIdentity == null || graphRow.graphIdentity > afterGraphIdentity!!)
			val graph = requireNotNull(
				dao.authenticatedGraphForFullClear(
					graphRow.graphIdentity,
					graphRow.sourceFormat,
				),
			) {
				"Imported portable graph is not independently authentic"
			}
			if (staging.bindingCountForGraph(graphRow.graphIdentity) > 0L) {
				require(staging.isGraphAuthenticated(graphRow.graphIdentity)) {
					"Imported portable graph binding was not authenticated by its product"
				}
			} else {
				consume(
					AuthenticatedFullClearGraphAppearance(
					graph = graph,
					graphIdentity = graphRow.graphIdentity,
					productKind = graphRow.sourceFormat.toPortableProductKind(),
					productIdentity = null,
					graphRevision = null,
					sourceSchemaVersion = null,
					isBound = false,
					),
				)
			}
		}
		afterGraphIdentity = page.last().graphIdentity
		if (page.size < GRAPH_FULL_CLEAR_PAGE_SIZE) {
			break
		}
	}
}

internal data class AuthenticatedFullClearGraphAppearance(
	val graph: PortableCountDomainGraphV2,
	val graphIdentity: String,
	val productKind: String,
	val productIdentity: String?,
	val graphRevision: Long?,
	val sourceSchemaVersion: Int?,
	val isBound: Boolean,
)

internal suspend fun AppDatabase.stageAuthenticatedPortableOwnerFencesForFullClear(
	graphs: Sequence<AuthenticatedFullClearGraphAppearance>,
	operationId: String,
	fenceKind: String,
	collectedDataEpoch: Long,
	fencedAtMs: Long,
	maximumOwnerCount: Int,
	maximumOwnerRevisionCount: Int,
) {
	require(operationId.isNotBlank())
	require(collectedDataEpoch >= 0L)
	require(fencedAtMs >= 0L)
	require(maximumOwnerCount > 0)
	require(maximumOwnerRevisionCount > 0)
	val coroutineContext = currentCoroutineContext()
	val staging = AuthenticatedFullClearOwnerFenceStaging(
		openHelper.writableDatabase,
		operationId,
		fenceKind,
		collectedDataEpoch,
		fencedAtMs,
		maximumOwnerCount.toLong(),
		maximumOwnerRevisionCount.toLong(),
		checkpoint = coroutineContext::ensureActive,
	)
	try {
		graphs.forEach(staging::add)
		staging.install(importedPortableStepsCountDomainDao())
	} finally {
		staging.close()
	}
}

private data class FullClearOwnerRevisionKey(
	val ownerKind: PortableCountDomainOwnerKind,
	val ownerIdentity: String,
	val ownerRevision: Long,
)

private fun String.toPortableProductKind(): String = when (this) {
	ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS ->
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY
	ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS ->
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY
	else -> error("Unsupported imported portable graph source")
}

private fun AppDatabase.authenticateAllPortableSessionFileReceiptsForFullClear(
	sessionProducts: AuthenticatedFullClearOwnerFenceStaging,
) {
	val dao = importedPortableStepsCountDomainDao()
	var afterJobId: String? = null
	var afterEntryKey: String? = null
	while (true) {
		val page = dao.fileReceiptPageForFullClear(
			afterJobId,
			afterEntryKey,
			FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE,
		)
		if (page.isEmpty()) {
			break
		}
		require(page.size <= FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE)
		val products = sessionProducts.sessionProducts(page.map { it.entryIdentity })
		require(products.keys == page.mapTo(linkedSetOf()) { it.entryIdentity }) {
			"Imported Steps file receipt has no authenticated product"
		}
		page.forEach { receipt ->
			requireSessionFileReceiptKeyAfter(receipt, afterJobId, afterEntryKey)
			val product = requireNotNull(products[receipt.entryIdentity])
			requireSessionFileReceiptProduct(receipt, product, sessionProducts)
			if (product.sourceReceiptIdentity == receipt.receiptIdentity) {
				require(
					product.sourceArchiveContentChecksum == receipt.archiveContentChecksum,
				)
				sessionProducts.markSourceReceiptObserved(
					productIdentity = product.productIdentity,
					receiptIdentity = receipt.receiptIdentity,
					archiveContentChecksum = receipt.archiveContentChecksum,
				)
			}
		}
		val last = page.last()
		afterJobId = last.importJobId
		afterEntryKey = last.entryKey
		if (page.size < FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE) {
			break
		}
	}
	sessionProducts.requireEverySourceReceiptObserved()
}

private fun requireSessionFileReceiptKeyAfter(
	receipt: com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFileReceiptEntity,
	afterJobId: String?,
	afterEntryKey: String?,
) {
	require(
		afterJobId == null ||
			receipt.importJobId > afterJobId!! ||
			(receipt.importJobId == afterJobId && receipt.entryKey > afterEntryKey!!),
	)
}

private fun requireSessionFileReceiptProduct(
	receipt: com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFileReceiptEntity,
	product: StagedFullClearSessionProduct,
	sessionProducts: AuthenticatedFullClearOwnerFenceStaging,
) {
	require(product.hasStoredBinding) {
		"Imported Steps file receipt has no authenticated product binding"
	}
	require(product.productIdentity == receipt.entryIdentity)
	require(product.sourceFormat == StepsPortableFormatV1.FORMAT)
	require(product.graphIdentity == receipt.graphIdentity)
	require(sessionProducts.isGraphAuthenticated(product.graphIdentity))
	require(receipt.entryOrdinal in 0 until StepsPortableFormatV1.MAX_ENTRIES)
	if (product.sourceSchemaVersion == StepsPortableFormatV1.SCHEMA_VERSION) {
		require(receipt.archiveContentChecksum == product.contentChecksum)
	}
}

private suspend fun AppDatabase.reconcileImportedPortableLegacyGraphsBeforeFullClear(
	oldCollectedDataEpoch: Long,
) {
	val coroutineContext = currentCoroutineContext()
	reconcileImportedPortableLegacySessionGraphsBeforeFullClear()

	reconcileImportedPortableLegacyAmbientGraphsBeforeFullClear(oldCollectedDataEpoch, coroutineContext)
}

private suspend fun AppDatabase.reconcileImportedPortableLegacySessionGraphsBeforeFullClear() {
	val sessionReader = ImportedStepsRetainedReader(this)
	when (
		val traversal = sessionReader.forEachEntryForRetentionInTransaction { product ->
			if (importedPortableStepsCountDomainDao().bindingEvidenceCountForProduct(
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
					product.metadata.identity,
				) > 0
			) {
				val expected = product.legacyUnprovenCountDomainGraph()
				val loaded = requireNotNull(
					loadImportedSessionCountDomainBindingForFullClear(product.metadata.identity),
				)
				try {
					loadAuthenticatedImportedSessionCountDomainBindingForFullClear(
						loaded.binding,
						product,
					)
				} catch (failure: IllegalArgumentException) {
					if (!reconcilePreviouslyTruncatedLegacySessionBinding(
							product,
							loaded,
							expected,
						)
					) {
						throw failure
					}
				} catch (failure: IllegalStateException) {
					if (!reconcilePreviouslyTruncatedLegacySessionBinding(
							product,
							loaded,
							expected,
						)
					) {
						throw failure
					}
				}
			}
		}
	) {
		is ImportedStepsRetainedTraversal.Complete -> Unit
		is ImportedStepsRetainedTraversal.Unverifiable -> error(
			"Imported Steps product is unverifiable during graph reconciliation: " +
				"${traversal.entryIdentity}:${traversal.reason}",
		)
	}
}

private suspend fun AppDatabase.reconcileImportedPortableLegacyAmbientGraphsBeforeFullClear(
	oldCollectedDataEpoch: Long,
	coroutineContext: kotlin.coroutines.CoroutineContext,
) {
	val ambientDao = importedAmbientStepsDao()
	var afterDayId: String? = null
	while (true) {
		coroutineContext.ensureActive()
		val page = ambientDao.fullClearDayCandidatePage(afterDayId, AMBIENT_FULL_CLEAR_PAGE_SIZE)
		if (page.isEmpty()) {
			break
		}
		page.forEach { candidate ->
			coroutineContext.ensureActive()
			val lineage = ambientDao.loadAuthenticatedAmbientStepsLineageForFullClear(
				candidate,
				oldCollectedDataEpoch,
			)
			if (importedPortableStepsCountDomainDao().bindingEvidenceCountForProduct(
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
					candidate.dayIdentity,
				) > 0
			) {
				loadAuthenticatedImportedAmbientStepsGraphLineage(lineage)
			}
		}
		afterDayId = page.last().dayIdentity
		if (page.size < AMBIENT_FULL_CLEAR_PAGE_SIZE) {
			break
		}
	}
}

private suspend fun AppDatabase.requireSessionGraphCoversRetainedProduct(
	authenticated: AuthenticatedImportedPortableGraphBinding,
	product: RetainedImportedStepsEntry,
) {
	val coroutineContext = currentCoroutineContext()
	val liveRunIds = product.runs.mapTo(linkedSetOf()) { it.identity }
	val expectedRoots = retainedSessionProductRoots(product, coroutineContext::ensureActive)
	val graph = authenticated.graph
	val storedRoots = graph.roots.mapTo(linkedSetOf()) {
			coroutineContext.ensureActive()
			SessionRootKey(
				it.containerIdentity.value,
				it.productIdentity.value,
				it.ownerKind,
			)
		}
	require(storedRoots.containsAll(expectedRoots)) {
		"Imported Steps graph is missing retained run or fact ownership"
	}
	val extraRoots = graph.roots.filter { root ->
		coroutineContext.ensureActive()
		SessionRootKey(
			root.containerIdentity.value,
			root.productIdentity.value,
			root.ownerKind,
		) !in expectedRoots
	}
	if (extraRoots.isEmpty()) {
		return
	}
	requireFencedExtraSessionRoots(authenticated, product, extraRoots, liveRunIds, coroutineContext)
}

private fun AppDatabase.requireFencedExtraSessionRoots(
	authenticated: AuthenticatedImportedPortableGraphBinding,
	product: RetainedImportedStepsEntry,
	extraRoots: List<PortableCountDomainRootV2>,
	liveRunIds: Set<String>,
	coroutineContext: kotlin.coroutines.CoroutineContext,
) {
	val dao = importedPortableStepsCountDomainDao()
	val fences = extraRoots.map { it.ownerIdentity.value }.distinct().chunked(FENCE_QUERY_BATCH)
		.flatMap { dao.ownerFencesForFullClear(it) }
	val fencesByOwner = fences.associateBy { it.ownerKind to it.ownerIdentity }
	require(fencesByOwner.size == fences.size)
	val ownerRevisionsByRoot = authenticated.graph.ownerRevisions.associateBy {
		coroutineContext.ensureActive()
		FullClearOwnerRevisionKey(
			it.ownerKind,
			it.ownerIdentity.value,
			it.ownerRevision,
		)
	}
	require(ownerRevisionsByRoot.size == authenticated.graph.ownerRevisions.size)
	extraRoots.forEach { root ->
		coroutineContext.ensureActive()
		require(root.containerIdentity.value !in liveRunIds ||
			root.ownerKind == PortableCountDomainOwnerKind.SESSION_FACT
		) {
			"Imported Steps graph has an unfenced extra live-run root"
		}
		val owner = requireNotNull(
			ownerRevisionsByRoot[
				FullClearOwnerRevisionKey(
					root.ownerKind,
					root.ownerIdentity.value,
					root.ownerRevision,
				)
			],
		) {
			"Imported Steps graph extra root has no exact owner revision"
		}
		val fence = requireNotNull(
			fencesByOwner[root.ownerKind.name to root.ownerIdentity.value],
		) {
			"Imported Steps graph has an extra root without a terminal fence"
		}
		require(fence.authenticatesExtraSessionOwner(
			owner, authenticated.binding, product.metadata.collectedDataEpoch,
		)) {
			"Imported Steps graph extra root has conflicting terminal authority"
		}
	}
}

// One retained extra root must match this complete immutable terminal-fence owner and product tuple.
@Suppress("ComplexCondition")
private fun ImportedPortableStepsCountDomainOwnerFenceEntity.authenticatesExtraSessionOwner(
	owner: PortableCountDomainOwnerRevisionV2,
	binding: ImportedPortableStepsCountDomainBindingEntity,
	expectedCollectedDataEpoch: Long,
): Boolean = scopeIdentity == owner.scopeIdentity.value &&
	latestSourceRevision == owner.ownerRevision &&
	latestOwnerEffectChecksum == owner.ownerEffectChecksum.value &&
	productKind == binding.productKind &&
	productIdentity == binding.productIdentity &&
	graphIdentity == binding.graphIdentity &&
	fenceKind in SESSION_PRUNING_FENCE_KINDS &&
	collectedDataEpoch == expectedCollectedDataEpoch

private fun retainedSessionProductRoots(
	product: RetainedImportedStepsEntry,
	checkpoint: () -> Unit,
): Set<SessionRootKey> = buildSet {
	product.portableRunsById.values.forEach { run ->
		checkpoint()
		run.facts.forEach { fact ->
			checkpoint()
			add(
				SessionRootKey(
					run.identity.value,
					fact.identity.value,
					PortableCountDomainOwnerKind.SESSION_FACT,
				),
			)
		}
		add(
			SessionRootKey(
				run.identity.value,
				run.identity.value,
				PortableCountDomainOwnerKind.SESSION_COMPLETENESS,
			),
		)
	}
}

private data class SessionRootKey(
	val containerIdentity: String,
	val productIdentity: String,
	val ownerKind: PortableCountDomainOwnerKind,
)

private const val BINDING_FULL_CLEAR_PAGE_SIZE = 256
private const val AMBIENT_FULL_CLEAR_PAGE_SIZE = 256
private const val GRAPH_FULL_CLEAR_PAGE_SIZE = 64
private const val FENCE_QUERY_BATCH = 400
private val SESSION_PRUNING_FENCE_KINDS = setOf(
	ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_RETENTION,
	ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_SELECTED_DELETE,
)
