package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableCountDomainIdentity
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOperation
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerKind
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerRevisionV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainRootV2
import com.adsamcik.tracker.shared.model.steps.portable.StepsPortableFormatV1

/**
 * Converts every live imported portable owner into a value-free terminal fence before full clear.
 */
internal suspend fun AppDatabase.preserveImportedPortableCountDomainFullClearFences(
	oldCollectedDataEpoch: Long,
	newCollectedDataEpoch: Long,
	fencedAtMs: Long,
) {
	require(oldCollectedDataEpoch >= 0L)
	require(newCollectedDataEpoch > oldCollectedDataEpoch)
	require(fencedAtMs >= 0L)
	reconcileImportedPortableLegacyGraphsBeforeFullClear(oldCollectedDataEpoch)
	val dao = importedPortableStepsCountDomainDao()
	val bindings = dao.allBindingsForFullClear(MAX_FULL_CLEAR_BINDINGS + 1)
	require(bindings.size <= MAX_FULL_CLEAR_BINDINGS)
	require(bindings.distinct().size == bindings.size)
	val consumedBindings = linkedSetOf<ImportedPortableStepsCountDomainBindingEntity>()
	val authenticatedGraphIdentities = linkedSetOf<String>()
	val fenceAccumulator = AuthenticatedFullClearOwnerFenceAccumulator(
		fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_FULL_CLEAR,
		collectedDataEpoch = newCollectedDataEpoch,
		fencedAtMs = fencedAtMs,
		maximumOwnerCount = MAX_FULL_CLEAR_OWNER_FENCES,
		maximumOwnerRevisionCount = MAX_FULL_CLEAR_OWNER_REVISIONS,
	)
	val consumeAuthenticatedGraph:
		(AuthenticatedImportedPortableGraphBinding, Boolean) -> Unit = { current, isStored ->
		if (isStored) authenticatedGraphIdentities += current.binding.graphIdentity
		fenceAccumulator.add(
			AuthenticatedFullClearGraphAppearance(
				graph = current.graph,
				graphIdentity = current.binding.graphIdentity,
				productKind = current.binding.productKind,
				productIdentity = current.binding.productIdentity,
				graphRevision = current.binding.productRevision,
				sourceSchemaVersion = current.binding.sourceSchemaVersion,
				isBound = true,
			),
		)
	}
	val sessionBindings = bindings.filter {
		it.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY
	}.associateBy { it.productIdentity }
	require(sessionBindings.size == bindings.count {
		it.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY
	})
	val sessionAuthentication = authenticatedImportedSessionBindingsForFullClear(
		sessionBindings,
		consumedBindings,
		consumeAuthenticatedGraph,
	)
	val ambientBindings = bindings.filter {
		it.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY
	}.groupBy { it.productIdentity }
	authenticatedImportedAmbientBindingsForFullClear(
		oldCollectedDataEpoch,
		ambientBindings,
		consumedBindings,
		consumeAuthenticatedGraph,
	)
	require(consumedBindings == bindings.toSet())
	require(authenticatedGraphIdentities == bindings.mapTo(linkedSetOf()) { it.graphIdentity })
	authenticateAndFoldOrphanPortableGraphsForFullClear(
		authenticatedGraphIdentities = authenticatedGraphIdentities,
		bindings = bindings,
	) { orphan ->
		fenceAccumulator.add(orphan)
	}
	installAuthenticatedFullClearOwnerFences(fenceAccumulator.fences())
	authenticateAllPortableSessionFileReceiptsForFullClear(
		sessionBindings = sessionBindings,
		sessionProducts = sessionAuthentication,
		authenticatedGraphIdentities = authenticatedGraphIdentities,
	)
	dao.deleteAllBindingsForFullClear()
	dao.deleteAllGraphsForFullClear()
	dao.deleteAllFileReceiptsForFullClear()
}

private suspend fun AppDatabase.authenticatedImportedSessionBindingsForFullClear(
	bindingsByEntry: Map<String, ImportedPortableStepsCountDomainBindingEntity>,
	consumedBindings: MutableSet<ImportedPortableStepsCountDomainBindingEntity>,
	consume: (AuthenticatedImportedPortableGraphBinding, Boolean) -> Unit,
): Map<String, RetainedImportedStepsEntry> {
	val products = linkedMapOf<String, RetainedImportedStepsEntry>()
	val reader = ImportedStepsRetainedReader(this)
	var beforeStartTimeMs: Long? = null
	var beforeIdentity: String? = null
	var entryCount = 0
	while (true) {
		val page = importedStepsDao().entryPage(
			beforeStartTimeMs,
			beforeIdentity,
			SESSION_FULL_CLEAR_PAGE_SIZE,
		)
		if (page.isEmpty()) break
		require(page.size <= SESSION_FULL_CLEAR_PAGE_SIZE)
		entryCount = Math.addExact(entryCount, page.size)
		require(entryCount <= MAX_FULL_CLEAR_BINDINGS)
		val retained = when (
			val read = reader.readEntriesForRetentionInTransaction(page.map { it.identity })
		) {
			is ImportedStepsRetainedRead.Ready -> {
				require(read.unverifiableEntries.isEmpty())
				require(read.entries.map { it.metadata.identity }.toSet() ==
					page.map { it.identity }.toSet())
				read.entries.associateBy { it.metadata.identity }
			}
			is ImportedStepsRetainedRead.Unverifiable -> {
				error("Imported Steps product is unverifiable during full clear: ${read.reason}")
			}
		}
		page.forEach { entry ->
			val product = requireNotNull(retained[entry.identity])
			require(products.put(entry.identity, product) == null)
			val binding = bindingsByEntry[entry.identity]
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
				consumedBindings += binding
				loadAuthenticatedImportedSessionCountDomainBindingForFullClear(binding, product)
			}
			requireSessionGraphCoversRetainedProduct(authenticated, product)
			consume(authenticated, binding != null)
		}
		beforeStartTimeMs = page.last().startTimeMs
		beforeIdentity = page.last().identity
		if (page.size < SESSION_FULL_CLEAR_PAGE_SIZE) break
	}
	return products
}

private suspend fun AppDatabase.authenticatedImportedAmbientBindingsForFullClear(
	oldCollectedDataEpoch: Long,
	bindingsByDay: Map<String, List<ImportedPortableStepsCountDomainBindingEntity>>,
	consumedBindings: MutableSet<ImportedPortableStepsCountDomainBindingEntity>,
	consume: (AuthenticatedImportedPortableGraphBinding, Boolean) -> Unit,
) {
	val ambientDao = importedAmbientStepsDao()
	var afterDayId: String? = null
	var dayCount = 0
	while (true) {
		val page = ambientDao.fullClearDayCandidatePage(afterDayId, AMBIENT_FULL_CLEAR_PAGE_SIZE)
		if (page.isEmpty()) break
		require(page.size <= AMBIENT_FULL_CLEAR_PAGE_SIZE)
		dayCount = Math.addExact(dayCount, page.size)
		require(dayCount <= MAX_FULL_CLEAR_BINDINGS)
		page.forEach { candidate ->
			val lineage = ambientDao.loadAuthenticatedAmbientStepsLineageForFullClear(
				candidate,
				oldCollectedDataEpoch,
			)
			val storedBindings = bindingsByDay[candidate.dayIdentity].orEmpty()
			val graphLineage = if (storedBindings.isEmpty()) {
				reconstructGraphlessLegacyAmbientLineage(lineage).also {
					requireGraphlessLegacyAmbientProvenance(lineage, it)
				}
			} else {
				loadAuthenticatedImportedAmbientStepsGraphLineageForFullClear(lineage).also {
					require(it.map(AuthenticatedImportedAmbientStepsGraphRevision::binding) ==
						storedBindings)
					consumedBindings += storedBindings
				}
			}
			graphLineage.forEach {
				consume(
					AuthenticatedImportedPortableGraphBinding(it.binding, it.graph),
					storedBindings.isNotEmpty(),
				)
			}
		}
		afterDayId = page.last().dayIdentity
		if (page.size < AMBIENT_FULL_CLEAR_PAGE_SIZE) break
	}
}

private fun AppDatabase.authenticateAndFoldOrphanPortableGraphsForFullClear(
	authenticatedGraphIdentities: Set<String>,
	bindings: List<ImportedPortableStepsCountDomainBindingEntity>,
	consume: (AuthenticatedFullClearGraphAppearance) -> Unit,
) {
	val dao = importedPortableStepsCountDomainDao()
	val bindingsByGraph = bindings.groupBy { it.graphIdentity }
	val remainingBoundGraphs = authenticatedGraphIdentities.toMutableSet()
	var afterGraphIdentity: String? = null
	while (true) {
		val page = dao.graphPageForFullClear(afterGraphIdentity, GRAPH_FULL_CLEAR_PAGE_SIZE)
		if (page.isEmpty()) break
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
			if (graphRow.graphIdentity in authenticatedGraphIdentities) {
				require(bindingsByGraph[graphRow.graphIdentity].orEmpty().isNotEmpty())
				remainingBoundGraphs -= graphRow.graphIdentity
			} else {
				require(bindingsByGraph[graphRow.graphIdentity].orEmpty().isEmpty()) {
					"Imported portable graph binding was not consumed by a product"
				}
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
		if (page.size < GRAPH_FULL_CLEAR_PAGE_SIZE) break
	}
	require(remainingBoundGraphs.isEmpty()) {
		"Authenticated imported portable binding references a missing graph"
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

private data class FullClearRootAuthority(
	val containerIdentity: String,
	val productIdentity: String,
	val ownerKind: PortableCountDomainOwnerKind,
	val ownerIdentity: String,
)

internal data class AuthenticatedFullClearOwnerFence(
	val fence: ImportedPortableStepsCountDomainOwnerFenceEntity,
	val latestOwner: PortableCountDomainOwnerRevisionV2,
)

internal fun authenticatedPortableOwnerFencesForFullClear(
	graphs: List<AuthenticatedFullClearGraphAppearance>,
	fenceKind: String,
	collectedDataEpoch: Long,
	fencedAtMs: Long,
	maximumOwnerCount: Int,
	maximumOwnerRevisionCount: Int,
): List<AuthenticatedFullClearOwnerFence> =
	AuthenticatedFullClearOwnerFenceAccumulator(
		fenceKind,
		collectedDataEpoch,
		fencedAtMs,
		maximumOwnerCount,
		maximumOwnerRevisionCount,
	).also { accumulator ->
		graphs.forEach(accumulator::add)
	}.fences()

private class AuthenticatedFullClearOwnerFenceAccumulator(
	private val fenceKind: String,
	private val collectedDataEpoch: Long,
	private val fencedAtMs: Long,
	private val maximumOwnerCount: Int,
	private val maximumOwnerRevisionCount: Int,
) {
	private val byOwner = linkedMapOf<
		Pair<PortableCountDomainOwnerKind, String>,
		FullClearOwnerAuthority
	>()
	private var distinctOwnerRevisionCount = 0

	fun add(graph: AuthenticatedFullClearGraphAppearance) {
		check(graph.graph.identity.value == graph.graphIdentity)
		graph.graph.roots.forEach { root ->
			val lineage = graph.graph.ownerRevisions.filter {
				it.ownerKind == root.ownerKind && it.ownerIdentity == root.ownerIdentity
			}
			check(lineage.isNotEmpty() && lineage.last().ownerRevision == root.ownerRevision)
			val key = root.ownerKind to root.ownerIdentity.value
			val authority = byOwner.getOrPut(key) {
				require(byOwner.size < maximumOwnerCount) {
					"Imported portable distinct owner count exceeds its full-clear bound"
				}
				FullClearOwnerAuthority()
			}
			distinctOwnerRevisionCount = Math.addExact(
				distinctOwnerRevisionCount,
				authority.add(graph, lineage, root),
			)
			require(distinctOwnerRevisionCount <= maximumOwnerRevisionCount) {
				"Imported portable distinct owner revision count exceeds its full-clear bound"
			}
		}
	}

	fun fences(): List<AuthenticatedFullClearOwnerFence> =
		byOwner.values.map { authority ->
			authority.fence(fenceKind, collectedDataEpoch, fencedAtMs)
		}.also {
			require(it.size <= maximumOwnerCount)
		}
}

private class FullClearOwnerAuthority {
	private val revisions = sortedMapOf<Long, PortableCountDomainOwnerRevisionV2>()
	private val legacyAmbientRevisions = sortedSetOf<Long>()
	private var explicitLineage: List<PortableCountDomainOwnerRevisionV2>? = null
	private var rootAuthority: FullClearRootAuthority? = null
	private var scopeIdentity: String? = null
	private var productKind: String? = null
	private var boundProductIdentity: String? = null
	private var latestAppearance: Pair<AuthenticatedFullClearGraphAppearance, PortableCountDomainRootV2>? =
		null

	fun add(
		graph: AuthenticatedFullClearGraphAppearance,
		lineage: List<PortableCountDomainOwnerRevisionV2>,
		root: PortableCountDomainRootV2,
	): Int {
		val first = lineage.first()
		require(lineage.all {
			it.ownerKind == first.ownerKind && it.ownerIdentity == first.ownerIdentity
		})
		require(
			scopeIdentity == null || scopeIdentity == first.scopeIdentity.value,
		) {
			"Imported portable owner appears in conflicting scopes"
		}
		scopeIdentity = first.scopeIdentity.value
		require(lineage.all { it.scopeIdentity.value == scopeIdentity }) {
			"Imported portable owner lineage changes scope"
		}
		val currentRootAuthority = FullClearRootAuthority(
			root.containerIdentity.value,
			root.productIdentity.value,
			root.ownerKind,
			root.ownerIdentity.value,
		)
		require(rootAuthority == null || rootAuthority == currentRootAuthority) {
			"Imported portable owner appears in conflicting product roots"
		}
		rootAuthority = currentRootAuthority
		require(productKind == null || productKind == graph.productKind) {
			"Imported portable owner appears in conflicting product kinds"
		}
		productKind = graph.productKind
		if (graph.isBound) {
			val currentProductIdentity = requireNotNull(graph.productIdentity)
			require(
				boundProductIdentity == null || boundProductIdentity == currentProductIdentity,
			) {
				"Imported portable owner appears in conflicting bound products"
			}
			boundProductIdentity = currentProductIdentity
		}

		val isLegacyAmbient = graph.isLegacyAmbientAppearance(lineage)
		if (isLegacyAmbient) {
			require(lineage.size == 1)
			legacyAmbientRevisions += lineage.single().ownerRevision
		} else {
			val canonical = explicitLineage
			if (canonical == null || lineage.size > canonical.size) {
				require(canonical == null || lineage.take(canonical.size) == canonical) {
					"Imported portable owner has conflicting explicit graph lineage"
				}
				explicitLineage = lineage
			} else {
				require(canonical.take(lineage.size) == lineage) {
					"Imported portable owner has conflicting explicit graph lineage"
				}
			}
		}

		var addedRevisionCount = 0
		lineage.forEach { owner ->
			val prior = revisions.putIfAbsent(owner.ownerRevision, owner)
			require(prior == null || prior == owner) {
				"Imported portable owner revision has conflicting graph appearances"
			}
			if (prior == null) addedRevisionCount = Math.addExact(addedRevisionCount, 1)
		}
		val priorLatest = latestAppearance
		if (priorLatest == null ||
			compareFullClearAppearances(graph, root, priorLatest.first, priorLatest.second) > 0
		) {
			latestAppearance = graph to root
		}
		return addedRevisionCount
	}

	fun fence(
		fenceKind: String,
		collectedDataEpoch: Long,
		fencedAtMs: Long,
	): AuthenticatedFullClearOwnerFence {
		legacyAmbientRevisions.zipWithNext().forEach { (previous, next) ->
			require(next == Math.addExact(previous, 1L)) {
				"Legacy Ambient portable owner revisions are not contiguous"
			}
		}
		explicitLineage?.let { explicit ->
			legacyAmbientRevisions.forEach { revision ->
				require(explicit.singleOrNull { it.ownerRevision == revision } == revisions[revision]) {
					"Explicit portable lineage does not preserve legacy Ambient authority"
				}
			}
		}
		val latestOwner = revisions.values.last()
		val appearance = requireNotNull(latestAppearance)
		val latestGraph = appearance.first
		val latestRoot = appearance.second
		require(latestRoot.ownerRevision == latestOwner.ownerRevision)
		return AuthenticatedFullClearOwnerFence(
			fence = ImportedPortableStepsCountDomainOwnerFenceEntity.create(
				ownerKind = latestOwner.ownerKind.name,
				ownerIdentity = latestOwner.ownerIdentity.value,
				scopeIdentity = latestOwner.scopeIdentity.value,
				latestSourceRevision = latestOwner.ownerRevision,
				latestOwnerEffectChecksum = latestOwner.ownerEffectChecksum.value,
				productKind = requireNotNull(productKind),
				productIdentity = boundProductIdentity ?: latestRoot.productIdentity.value,
				graphIdentity = latestGraph.graphIdentity,
				fenceKind = fenceKind,
				collectedDataEpoch = collectedDataEpoch,
				fencedAtMs = fencedAtMs,
			),
			latestOwner = latestOwner,
		)
	}
}

private fun AuthenticatedFullClearGraphAppearance.isLegacyAmbientAppearance(
	lineage: List<PortableCountDomainOwnerRevisionV2>,
): Boolean {
	if (productKind != ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY) {
		return false
	}
	if (sourceSchemaVersion != null) {
		return sourceSchemaVersion == AmbientStepsPortableFormatV1.SCHEMA_VERSION
	}
	return graph.receipts.isEmpty() &&
		graph.completenessMarkers.isEmpty() &&
		graph.ownerRevisions.groupBy { it.ownerKind to it.ownerIdentity }.values.all {
			it.size == 1 &&
				it.single().operation == PortableCountDomainOperation.UNPROVEN &&
				it.single().receiptIdentity == null &&
				it.single().linkedAtMs == 0L
		} &&
		lineage.size == 1
}

private fun compareFullClearAppearances(
	leftGraph: AuthenticatedFullClearGraphAppearance,
	leftRoot: PortableCountDomainRootV2,
	rightGraph: AuthenticatedFullClearGraphAppearance,
	rightRoot: PortableCountDomainRootV2,
): Int = compareValuesBy(
	leftGraph to leftRoot,
	rightGraph to rightRoot,
	{ it.second.ownerRevision },
	{ it.first.graphRevision ?: 0L },
	{ if (it.first.isBound) 1 else 0 },
	{ it.first.productIdentity ?: it.second.productIdentity.value },
	{ it.first.graphIdentity },
)

private fun AppDatabase.installAuthenticatedFullClearOwnerFences(
	candidates: List<AuthenticatedFullClearOwnerFence>,
) {
	if (candidates.isEmpty()) return
	val dao = importedPortableStepsCountDomainDao()
	val candidatesByOwner = candidates.associateBy {
		it.fence.ownerKind to it.fence.ownerIdentity
	}
	require(candidatesByOwner.size == candidates.size)
	val existing = candidates.map { it.fence.ownerIdentity }.distinct().chunked(INSERT_BATCH)
		.flatMap { ownerIdentities ->
			dao.ownerFencesForFullClear(ownerIdentities)
	}
	val existingByOwner = existing.associateBy { it.ownerKind to it.ownerIdentity }
	require(existingByOwner.size == existing.size)
	require(existingByOwner.keys.all { it in candidatesByOwner })
	val missing = candidates.filter { candidate ->
		existingByOwner[candidate.fence.ownerKind to candidate.fence.ownerIdentity]?.let { stored ->
			require(
				stored.effectChecksum ==
					ImportedPortableCountDomainIdentity.ownerFenceChecksum(stored),
			)
			require(stored.hasCompatibleTerminalAuthority(candidate.fence))
			require(stored.latestSourceRevision == candidate.latestOwner.ownerRevision)
			require(
				stored.latestOwnerEffectChecksum ==
					candidate.latestOwner.ownerEffectChecksum.value,
			) {
				"Stored imported portable fence conflicts with authenticated owner lineage"
			}
			require(stored.collectedDataEpoch <= candidate.fence.collectedDataEpoch)
			require(stored.fencedAtMs <= candidate.fence.fencedAtMs)
			false
		} ?: true
	}
	missing.map(AuthenticatedFullClearOwnerFence::fence)
		.chunked(INSERT_BATCH)
		.forEach(dao::insertOwnerFencesForFullClear)
}

private fun String.toPortableProductKind(): String = when (this) {
	ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS ->
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY
	ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS ->
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY
	else -> error("Unsupported imported portable graph source")
}

private fun AppDatabase.authenticateAllPortableSessionFileReceiptsForFullClear(
	sessionBindings: Map<String, ImportedPortableStepsCountDomainBindingEntity>,
	sessionProducts: Map<String, RetainedImportedStepsEntry>,
	authenticatedGraphIdentities: Set<String>,
) {
	val dao = importedPortableStepsCountDomainDao()
	val observedSourceReceipts = linkedSetOf<String>()
	var afterJobId: String? = null
	var afterEntryKey: String? = null
	while (true) {
		val page = dao.fileReceiptPageForFullClear(
			afterJobId,
			afterEntryKey,
			FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE,
		)
		if (page.isEmpty()) break
		require(page.size <= FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE)
		page.forEach { receipt ->
			require(
				afterJobId == null ||
					receipt.importJobId > afterJobId!! ||
					(receipt.importJobId == afterJobId && receipt.entryKey > afterEntryKey!!),
			)
			val binding = requireNotNull(sessionBindings[receipt.entryIdentity]) {
				"Imported Steps file receipt has no authenticated product binding"
			}
			val product = requireNotNull(sessionProducts[receipt.entryIdentity]) {
				"Imported Steps file receipt has no authenticated product"
			}
			require(binding.graphIdentity == receipt.graphIdentity)
			require(binding.graphIdentity in authenticatedGraphIdentities)
			require(receipt.entryOrdinal in 0 until StepsPortableFormatV1.MAX_ENTRIES)
			if (binding.sourceSchemaVersion == StepsPortableFormatV1.SCHEMA_VERSION) {
				require(receipt.archiveContentChecksum == product.metadata.contentChecksum)
			}
			if (binding.sourceReceiptIdentity == receipt.receiptIdentity) {
				require(binding.sourceArchiveContentChecksum == receipt.archiveContentChecksum)
				observedSourceReceipts += receipt.receiptIdentity
			}
		}
		val last = page.last()
		afterJobId = last.importJobId
		afterEntryKey = last.entryKey
		if (page.size < FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE) break
	}
	sessionBindings.values.mapNotNull { it.sourceReceiptIdentity }.forEach { sourceReceipt ->
		require(sourceReceipt in observedSourceReceipts) {
			"Imported Steps binding source receipt was not independently enumerated"
		}
	}
}

private suspend fun AppDatabase.reconcileImportedPortableLegacyGraphsBeforeFullClear(
	oldCollectedDataEpoch: Long,
) {
	val sessionReader = ImportedStepsRetainedReader(this)
	var beforeStartTimeMs: Long? = null
	var beforeIdentity: String? = null
	var sessionCount = 0
	while (true) {
		val page = importedStepsDao().entryPage(
			beforeStartTimeMs,
			beforeIdentity,
			SESSION_FULL_CLEAR_PAGE_SIZE,
		)
		if (page.isEmpty()) break
		sessionCount = Math.addExact(sessionCount, page.size)
		require(sessionCount <= MAX_FULL_CLEAR_BINDINGS)
		val retained = when (
			val read = sessionReader.readEntriesForRetentionInTransaction(
				page.map { it.identity },
			)
		) {
			is ImportedStepsRetainedRead.Ready -> {
				require(read.unverifiableEntries.isEmpty())
				read.entries.associateBy { it.metadata.identity }
			}
			is ImportedStepsRetainedRead.Unverifiable ->
				error("Imported Steps product is unverifiable during graph reconciliation")
		}
		page.forEach { entry ->
			val product = requireNotNull(retained[entry.identity])
			if (importedPortableStepsCountDomainDao().bindingEvidenceCountForProduct(
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
					entry.identity,
				) > 0
			) {
				val expected = product.legacyUnprovenCountDomainGraph()
				val loaded = requireNotNull(
					loadImportedSessionCountDomainBindingForFullClear(entry.identity),
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
		beforeStartTimeMs = page.last().startTimeMs
		beforeIdentity = page.last().identity
		if (page.size < SESSION_FULL_CLEAR_PAGE_SIZE) break
	}

	val ambientDao = importedAmbientStepsDao()
	var afterDayId: String? = null
	var ambientCount = 0
	while (true) {
		val page = ambientDao.fullClearDayCandidatePage(afterDayId, AMBIENT_FULL_CLEAR_PAGE_SIZE)
		if (page.isEmpty()) break
		ambientCount = Math.addExact(ambientCount, page.size)
		require(ambientCount <= MAX_FULL_CLEAR_BINDINGS)
		page.forEach { candidate ->
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
		if (page.size < AMBIENT_FULL_CLEAR_PAGE_SIZE) break
	}
}

private fun AppDatabase.requireSessionGraphCoversRetainedProduct(
	authenticated: AuthenticatedImportedPortableGraphBinding,
	product: RetainedImportedStepsEntry,
) {
	val liveRunIds = product.runs.mapTo(linkedSetOf()) { it.identity }
	val expectedRoots = buildSet {
		product.portableRunsById.values.forEach { run ->
			run.facts.forEach { fact ->
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
	val graph = authenticated.graph
	val storedRoots = graph.roots.mapTo(linkedSetOf()) {
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
		SessionRootKey(
			root.containerIdentity.value,
			root.productIdentity.value,
			root.ownerKind,
		) !in expectedRoots
	}
	if (extraRoots.isEmpty()) return
	val dao = importedPortableStepsCountDomainDao()
	val fences = extraRoots.map { it.ownerIdentity.value }.distinct().chunked(FENCE_QUERY_BATCH)
		.flatMap { dao.ownerFencesForFullClear(it) }
	val fencesByOwner = fences.associateBy { it.ownerKind to it.ownerIdentity }
	require(fencesByOwner.size == fences.size)
	extraRoots.forEach { root ->
		require(root.containerIdentity.value !in liveRunIds ||
			root.ownerKind == PortableCountDomainOwnerKind.SESSION_FACT
		) {
			"Imported Steps graph has an unfenced extra live-run root"
		}
		val owner = graph.ownerRevisions.single {
			it.ownerKind == root.ownerKind &&
				it.ownerIdentity == root.ownerIdentity &&
				it.ownerRevision == root.ownerRevision
		}
		val fence = requireNotNull(
			fencesByOwner[root.ownerKind.name to root.ownerIdentity.value],
		) {
			"Imported Steps graph has an extra root without a terminal fence"
		}
		require(
			fence.scopeIdentity == owner.scopeIdentity.value &&
				fence.latestSourceRevision == owner.ownerRevision &&
				fence.latestOwnerEffectChecksum == owner.ownerEffectChecksum.value &&
				fence.productKind == authenticated.binding.productKind &&
				fence.productIdentity == authenticated.binding.productIdentity &&
				fence.graphIdentity == authenticated.binding.graphIdentity &&
				fence.fenceKind in SESSION_PRUNING_FENCE_KINDS &&
				fence.collectedDataEpoch == product.metadata.collectedDataEpoch,
		) {
			"Imported Steps graph extra root has conflicting terminal authority"
		}
	}
}

private data class SessionRootKey(
	val containerIdentity: String,
	val productIdentity: String,
	val ownerKind: PortableCountDomainOwnerKind,
)

private const val MAX_FULL_CLEAR_BINDINGS = 131_072
private const val MAX_FULL_CLEAR_OWNER_FENCES = 262_144
private const val MAX_FULL_CLEAR_OWNER_REVISIONS =
	MAX_FULL_CLEAR_OWNER_FENCES * ImportedAmbientStepsDao.MAX_REVISIONS_PER_DAY
private const val INSERT_BATCH = 256
private const val SESSION_FULL_CLEAR_PAGE_SIZE = 32
private const val AMBIENT_FULL_CLEAR_PAGE_SIZE = 256
private const val GRAPH_FULL_CLEAR_PAGE_SIZE = 64
private const val FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE = 256
private const val SESSION_PRODUCT_REVISION = 1L
private const val FENCE_QUERY_BATCH = 400
private val SESSION_PRUNING_FENCE_KINDS = setOf(
	ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_RETENTION,
	ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_SELECTED_DELETE,
)
