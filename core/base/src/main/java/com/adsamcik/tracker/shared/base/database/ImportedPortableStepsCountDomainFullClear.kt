package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableCountDomainIdentity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
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
	val authenticated = mutableListOf<AuthenticatedImportedPortableGraphBinding>()
	val consumedBindings = linkedSetOf<ImportedPortableStepsCountDomainBindingEntity>()
	val sessionBindings = bindings.filter {
		it.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY
	}.associateBy { it.productIdentity }
	require(sessionBindings.size == bindings.count {
		it.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY
	})
	val sessionAuthentication = authenticatedImportedSessionBindingsForFullClear(
		sessionBindings,
		consumedBindings,
	)
	authenticated += sessionAuthentication.graphs
	val ambientBindings = bindings.filter {
		it.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY
	}.groupBy { it.productIdentity }
	authenticated += authenticatedImportedAmbientBindingsForFullClear(
		oldCollectedDataEpoch,
		ambientBindings,
		consumedBindings,
	)
	require(consumedBindings == bindings.toSet())
	val authenticatedByGraph = authenticated
		.filter { it.binding in consumedBindings }
		.groupBy { it.binding.graphIdentity }
		.mapValues { (_, appearances) ->
			require(appearances.map { it.graph }.distinct().size == 1)
			appearances.first().graph
		}
	require(authenticatedByGraph.keys == bindings.mapTo(linkedSetOf()) { it.graphIdentity })
	val graphAppearances = authenticated.map { current ->
		AuthenticatedFullClearGraphAppearance(
			graph = current.graph,
			graphIdentity = current.binding.graphIdentity,
			productKind = current.binding.productKind,
			productIdentity = current.binding.productIdentity,
			graphRevision = current.binding.productRevision,
			isBound = true,
		)
	} + authenticateAndCollectOrphanPortableGraphsForFullClear(
		authenticatedByGraph = authenticatedByGraph,
		bindings = bindings,
	)
	val fullClearFences = authenticatedPortableOwnerFencesForFullClear(
		graphs = graphAppearances,
		fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_FULL_CLEAR,
		collectedDataEpoch = newCollectedDataEpoch,
		fencedAtMs = fencedAtMs,
		maximumFenceCount = MAX_FULL_CLEAR_OWNER_FENCES,
	)
	installAuthenticatedFullClearOwnerFences(fullClearFences)
	authenticateAllPortableSessionFileReceiptsForFullClear(
		sessionBindings = sessionBindings,
		sessionProducts = sessionAuthentication.products,
		authenticatedGraphIdentities = authenticatedByGraph.keys,
	)
	dao.deleteAllBindingsForFullClear()
	dao.deleteAllGraphsForFullClear()
	dao.deleteAllFileReceiptsForFullClear()
}

private data class AuthenticatedFullClearSessionProducts(
	val graphs: List<AuthenticatedImportedPortableGraphBinding>,
	val products: Map<String, RetainedImportedStepsEntry>,
)

private suspend fun AppDatabase.authenticatedImportedSessionBindingsForFullClear(
	bindingsByEntry: Map<String, ImportedPortableStepsCountDomainBindingEntity>,
	consumedBindings: MutableSet<ImportedPortableStepsCountDomainBindingEntity>,
): AuthenticatedFullClearSessionProducts {
	val result = mutableListOf<AuthenticatedImportedPortableGraphBinding>()
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
			result += authenticated
		}
		beforeStartTimeMs = page.last().startTimeMs
		beforeIdentity = page.last().identity
		if (page.size < SESSION_FULL_CLEAR_PAGE_SIZE) break
	}
	return AuthenticatedFullClearSessionProducts(result, products)
}

private suspend fun AppDatabase.authenticatedImportedAmbientBindingsForFullClear(
	oldCollectedDataEpoch: Long,
	bindingsByDay: Map<String, List<ImportedPortableStepsCountDomainBindingEntity>>,
	consumedBindings: MutableSet<ImportedPortableStepsCountDomainBindingEntity>,
): List<AuthenticatedImportedPortableGraphBinding> {
	val result = mutableListOf<AuthenticatedImportedPortableGraphBinding>()
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
			result += graphLineage.map {
				AuthenticatedImportedPortableGraphBinding(it.binding, it.graph)
			}
		}
		afterDayId = page.last().dayIdentity
		if (page.size < AMBIENT_FULL_CLEAR_PAGE_SIZE) break
	}
	return result
}

private fun AppDatabase.authenticateAndCollectOrphanPortableGraphsForFullClear(
	authenticatedByGraph: Map<String, PortableCountDomainGraphV2>,
	bindings: List<ImportedPortableStepsCountDomainBindingEntity>,
): List<AuthenticatedFullClearGraphAppearance> {
	val dao = importedPortableStepsCountDomainDao()
	val bindingsByGraph = bindings.groupBy { it.graphIdentity }
	val remainingBoundGraphs = authenticatedByGraph.keys.toMutableSet()
	val orphanGraphs = mutableListOf<AuthenticatedFullClearGraphAppearance>()
	var orphanRootCount = 0
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
			val consumed = authenticatedByGraph[graphRow.graphIdentity]
			if (consumed != null) {
				require(consumed == graph)
				require(bindingsByGraph[graphRow.graphIdentity].orEmpty().isNotEmpty())
				remainingBoundGraphs -= graphRow.graphIdentity
			} else {
				require(bindingsByGraph[graphRow.graphIdentity].orEmpty().isEmpty()) {
					"Imported portable graph binding was not consumed by a product"
				}
				orphanRootCount = Math.addExact(orphanRootCount, graph.roots.size)
				require(orphanRootCount <= MAX_FULL_CLEAR_OWNER_APPEARANCES)
				orphanGraphs += AuthenticatedFullClearGraphAppearance(
					graph = graph,
					graphIdentity = graphRow.graphIdentity,
					productKind = graphRow.sourceFormat.toPortableProductKind(),
					productIdentity = null,
					graphRevision = null,
					isBound = false,
				)
			}
		}
		afterGraphIdentity = page.last().graphIdentity
		if (page.size < GRAPH_FULL_CLEAR_PAGE_SIZE) break
	}
	require(remainingBoundGraphs.isEmpty()) {
		"Authenticated imported portable binding references a missing graph"
	}
	return orphanGraphs
}

private data class AuthenticatedFullClearGraphAppearance(
	val graph: PortableCountDomainGraphV2,
	val graphIdentity: String,
	val productKind: String,
	val productIdentity: String?,
	val graphRevision: Long?,
	val isBound: Boolean,
)

private data class AuthenticatedFullClearOwnerAppearance(
	val graph: AuthenticatedFullClearGraphAppearance,
	val lineage: List<PortableCountDomainOwnerRevisionV2>,
	val root: PortableCountDomainRootV2,
)

private data class AuthenticatedFullClearOwnerFence(
	val fence: ImportedPortableStepsCountDomainOwnerFenceEntity,
	val lineage: List<PortableCountDomainOwnerRevisionV2>,
)

private fun authenticatedPortableOwnerFencesForFullClear(
	graphs: List<AuthenticatedFullClearGraphAppearance>,
	fenceKind: String,
	collectedDataEpoch: Long,
	fencedAtMs: Long,
	maximumFenceCount: Int,
): List<AuthenticatedFullClearOwnerFence> {
	val byOwner = linkedMapOf<
		Pair<PortableCountDomainOwnerKind, String>,
		MutableList<AuthenticatedFullClearOwnerAppearance>
	>()
	var appearanceCount = 0
	graphs.forEach { graph ->
		check(graph.graph.identity.value == graph.graphIdentity)
		graph.graph.roots.forEach { root ->
			val lineage = graph.graph.ownerRevisions.filter {
				it.ownerKind == root.ownerKind && it.ownerIdentity == root.ownerIdentity
			}
			check(lineage.isNotEmpty() && lineage.last().ownerRevision == root.ownerRevision)
			byOwner.getOrPut(root.ownerKind to root.ownerIdentity.value, ::mutableListOf) +=
				AuthenticatedFullClearOwnerAppearance(graph, lineage, root)
			appearanceCount = Math.addExact(appearanceCount, 1)
			require(appearanceCount <= MAX_FULL_CLEAR_OWNER_APPEARANCES)
		}
	}

	require(byOwner.size <= maximumFenceCount)
	return byOwner.values.map { appearances ->
		val canonicalLineage = appearances.maxWith(
			compareBy<AuthenticatedFullClearOwnerAppearance> { it.lineage.size }
				.thenBy { it.lineage.last().ownerRevision },
		).lineage
		require(appearances.all { appearance ->
			appearance.lineage == canonicalLineage.take(appearance.lineage.size)
		}) {
			"Imported portable owner has conflicting graph lineage"
		}
		require(appearances.map { it.graph.productKind }.distinct().size == 1) {
			"Imported portable owner appears in conflicting product kinds"
		}
		require(
			appearances.mapNotNull { appearance ->
				appearance.graph.productIdentity.takeIf { appearance.graph.isBound }
			}.distinct().size <= 1,
		) {
			"Imported portable owner appears in conflicting bound products"
		}
		val latestOwner = canonicalLineage.last()
		val latestAppearance = appearances
			.filter { it.root.ownerRevision == latestOwner.ownerRevision }
			.maxWith(
				compareBy<AuthenticatedFullClearOwnerAppearance> {
					it.graph.graphRevision ?: 0L
				}.thenBy {
					if (it.graph.isBound) 1 else 0
				}.thenBy {
					it.graph.productIdentity ?: it.root.productIdentity.value
				}.thenBy { it.graph.graphIdentity },
			)
		AuthenticatedFullClearOwnerFence(
			fence = ImportedPortableStepsCountDomainOwnerFenceEntity.create(
				ownerKind = latestOwner.ownerKind.name,
				ownerIdentity = latestOwner.ownerIdentity.value,
				scopeIdentity = latestOwner.scopeIdentity.value,
				latestSourceRevision = latestOwner.ownerRevision,
				latestOwnerEffectChecksum = latestOwner.ownerEffectChecksum.value,
				productKind = latestAppearance.graph.productKind,
				productIdentity = latestAppearance.graph.productIdentity
					?: latestAppearance.root.productIdentity.value,
				graphIdentity = latestAppearance.graph.graphIdentity,
				fenceKind = fenceKind,
				collectedDataEpoch = collectedDataEpoch,
				fencedAtMs = fencedAtMs,
			),
			lineage = canonicalLineage,
		)
	}
}

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
	candidates.forEach { candidate ->
		existingByOwner[candidate.fence.ownerKind to candidate.fence.ownerIdentity]?.let { stored ->
			require(
				stored.effectChecksum ==
					ImportedPortableCountDomainIdentity.ownerFenceChecksum(stored),
			)
			require(stored.scopeIdentity == candidate.fence.scopeIdentity)
			require(stored.productKind == candidate.fence.productKind)
			require(stored.productIdentity == candidate.fence.productIdentity)
			val authenticatedStoredRevision = candidate.lineage.singleOrNull {
				it.ownerRevision == stored.latestSourceRevision
			}
			require(
				authenticatedStoredRevision?.ownerEffectChecksum?.value ==
					stored.latestOwnerEffectChecksum,
			) {
				"Stored imported portable fence conflicts with authenticated owner lineage"
			}
			require(stored.latestSourceRevision <= candidate.fence.latestSourceRevision)
			require(stored.collectedDataEpoch <= candidate.fence.collectedDataEpoch)
		}
	}
	candidates.map(AuthenticatedFullClearOwnerFence::fence)
		.chunked(INSERT_BATCH)
		.forEach(dao::upsertOwnerFencesForFullClear)
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
private const val MAX_FULL_CLEAR_OWNER_APPEARANCES = 262_144
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
