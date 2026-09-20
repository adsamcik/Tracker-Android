package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_STEPS_RUN_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerKind
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsDigest
import com.adsamcik.tracker.shared.model.steps.portable.StepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.legacyUnprovenStepsCountDomainGraph
import com.adsamcik.tracker.shared.model.steps.portable.withExplicitUnprovenCountDomain

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
	authenticated += authenticatedImportedSessionBindingsForFullClear(
		sessionBindings,
		consumedBindings,
	)
	val ambientBindings = bindings.filter {
		it.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY
	}.groupBy { it.productIdentity }
	authenticated += authenticatedImportedAmbientBindingsForFullClear(
		oldCollectedDataEpoch,
		ambientBindings,
		consumedBindings,
	)
	require(consumedBindings == bindings.toSet())
	val unique = authenticatedImportedPortableOwnerFences(
		graphs = authenticated,
		fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_FULL_CLEAR,
		collectedDataEpoch = newCollectedDataEpoch,
		fencedAtMs = fencedAtMs,
		maximumFenceCount = MAX_FULL_CLEAR_OWNER_FENCES,
	)
	val existing = unique.chunked(INSERT_BATCH).flatMap { batch ->
		dao.ownerFencesForFullClear(batch.map { it.ownerIdentity })
	}
	val candidatesByOwner = unique.associateBy { it.ownerKind to it.ownerIdentity }
	val existingByOwner = existing.associateBy { it.ownerKind to it.ownerIdentity }
	require(existingByOwner.size == existing.size)
	require(existingByOwner.keys.all { it in candidatesByOwner })
	val missing = unique.filter { candidate ->
		existingByOwner[candidate.ownerKind to candidate.ownerIdentity]?.let { stored ->
			require(stored.scopeIdentity == candidate.scopeIdentity)
			require(stored.latestSourceRevision == candidate.latestSourceRevision)
			require(stored.latestOwnerEffectChecksum == candidate.latestOwnerEffectChecksum)
			require(stored.productKind == candidate.productKind)
			require(stored.productIdentity == candidate.productIdentity)
			require(stored.graphIdentity == candidate.graphIdentity)
			false
		} ?: true
	}
	missing.chunked(INSERT_BATCH).forEach(dao::insertOwnerFencesForFullClear)
	dao.deleteAllBindingsForFullClear()
	dao.deleteAllGraphsForFullClear()
	dao.deleteAllFileReceiptsForFullClear()
}

private suspend fun AppDatabase.authenticatedImportedSessionBindingsForFullClear(
	bindingsByEntry: Map<String, ImportedPortableStepsCountDomainBindingEntity>,
	consumedBindings: MutableSet<ImportedPortableStepsCountDomainBindingEntity>,
): List<AuthenticatedImportedPortableGraphBinding> {
	val result = mutableListOf<AuthenticatedImportedPortableGraphBinding>()
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
			val binding = bindingsByEntry[entry.identity]
			val authenticated = if (binding == null) {
				val graph = legacyUnprovenStepsCountDomainGraph(
					PortableStepsDigest(product.metadata.contentChecksum),
					product.portableRunsById.values.sortedWith(PORTABLE_STEPS_RUN_ORDER),
				)
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
	return result
}

private fun AppDatabase.authenticatedImportedAmbientBindingsForFullClear(
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
				reconstructGraphlessLegacyAmbientLineage(lineage)
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

private fun reconstructGraphlessLegacyAmbientLineage(
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
	return membersByGraphRevision
		.map { (graphRevision, graphMembers) ->
			val sourceMembers = graphMembers.sortedWith(
				compareBy(
					com.adsamcik.tracker.shared.base.database.data
						.ImportedAmbientStepsArchiveDayEntity::archiveIdentity,
					com.adsamcik.tracker.shared.base.database.data
						.ImportedAmbientStepsArchiveDayEntity::ordinal,
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
					com.adsamcik.tracker.shared.base.database.data
						.ImportedAmbientStepsReceiptEntity::importJobId,
					com.adsamcik.tracker.shared.base.database.data
						.ImportedAmbientStepsReceiptEntity::archiveKey,
				),
			)
			AuthenticatedImportedAmbientStepsGraphRevision(
				binding = ImportedPortableStepsCountDomainBindingEntity(
					productKind =
						ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
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
		}
		.also { graphLineage ->
			require(lineage.latest.header.importRevision in
				graphLineage.last().productImportRevisions)
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
private const val INSERT_BATCH = 256
private const val SESSION_FULL_CLEAR_PAGE_SIZE = 32
private const val AMBIENT_FULL_CLEAR_PAGE_SIZE = 256
private const val SESSION_PRODUCT_REVISION = 1L
private const val FENCE_QUERY_BATCH = 400
private val SESSION_PRUNING_FENCE_KINDS = setOf(
	ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_RETENTION,
	ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_SELECTED_DELETE,
)
