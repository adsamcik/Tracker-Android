package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainCompletenessState
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainIntegrity
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOperation
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerKind
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerRevisionV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainRootV2

/** Fences one imported session run without deleting sibling source graphs. */
suspend fun AppDatabase.fenceImportedPortableSessionRun(
	entry: RetainedImportedStepsEntry,
	runIdentity: String,
	fenceKind: String,
	collectedDataEpoch: Long,
	fencedAtMs: Long,
): AuthenticatedImportedPortableGraphBinding {
	val authenticated = authenticateOrInstallImportedSessionCountDomainBinding(entry)
	val roots = authenticated.graph.roots.filter { it.containerIdentity.value == runIdentity }
	require(roots.isNotEmpty())
	fenceImportedPortableSessionRoots(
		authenticated,
		roots,
		fenceKind,
		collectedDataEpoch,
		fencedAtMs,
	)
	return authenticated
}

/** Fences exact pruned fact owners while the retained run and its completeness root remain live. */
suspend fun AppDatabase.fenceImportedPortableSessionFacts(
	entry: RetainedImportedStepsEntry,
	runIdentity: String,
	factIdentities: Set<String>,
	fenceKind: String,
	collectedDataEpoch: Long,
	fencedAtMs: Long,
): AuthenticatedImportedPortableGraphBinding {
	require(factIdentities.isNotEmpty())
	val authenticated = authenticateOrInstallImportedSessionCountDomainBinding(entry)
	val roots = authenticated.graph.roots.filter {
		it.containerIdentity.value == runIdentity &&
			it.productIdentity.value in factIdentities &&
			it.ownerKind ==
				com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerKind
					.SESSION_FACT
	}
	require(roots.mapTo(linkedSetOf()) { it.productIdentity.value } == factIdentities)
	fenceImportedPortableSessionRoots(
		authenticated,
		roots,
		fenceKind,
		collectedDataEpoch,
		fencedAtMs,
	)
	return authenticated
}

/**
 * Authenticates the current retained product. A genuinely graphless legacy-v1 row is upgraded to
 * its one deterministic UNPROVEN graph before any destructive caller can fence or delete payload.
 */
suspend fun AppDatabase.authenticateOrInstallImportedSessionCountDomainBinding(
	entry: RetainedImportedStepsEntry,
): AuthenticatedImportedPortableGraphBinding {
	loadAuthenticatedImportedSessionCountDomainBinding(entry)?.let { return it }
	val graph = entry.legacyUnprovenCountDomainGraph()
	val dao = importedPortableStepsCountDomainDao()
	requireGraphlessLegacySessionProvenance(entry, graph)
	dao.insertAuthenticatedGraph(
		graph,
		ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
	)
	val binding = ImportedPortableStepsCountDomainBindingEntity(
		productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
		productIdentity = entry.metadata.identity,
		productRevision = IMPORTED_SESSION_PRODUCT_REVISION,
		graphIdentity = graph.identity.value,
		sourceSchemaVersion = 1,
	)
	dao.insertBinding(binding)
	return AuthenticatedImportedPortableGraphBinding(binding, graph)
}

/** Rebinds a maintained legacy-v1 graph to the newly authenticated retained product. */
suspend fun AppDatabase.refreshImportedLegacySessionCountDomainBinding(
	entryIdentity: String,
	previous: AuthenticatedImportedPortableGraphBinding,
) {
	val dao = importedPortableStepsCountDomainDao()
	require(
		dao.binding(
			ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
			entryIdentity,
			IMPORTED_SESSION_PRODUCT_REVISION,
		) == previous.binding,
	) {
		"Imported Steps graph binding changed during retention"
	}
	if (previous.binding.sourceSchemaVersion != 1) return
	val retained = when (
		val read = ImportedStepsRetainedReader(this)
			.readEntriesForRetentionInTransaction(listOf(entryIdentity))
	) {
		is ImportedStepsRetainedRead.Ready -> {
			require(read.unverifiableEntries.isEmpty())
			require(read.entries.size == 1)
			read.entries.single()
		}
		is ImportedStepsRetainedRead.Unverifiable ->
			error("Retained imported Steps product is unverifiable after pruning")
	}
	val replacement = retained.legacyUnprovenCountDomainGraph()
	if (replacement == previous.graph) return
	replaceImportedLegacySessionGraph(
		entryIdentity = entryIdentity,
		previous = previous,
		replacement = replacement,
		expectedFileReceiptCount = dao.fileReceiptCountForEntry(entryIdentity),
	)
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount", "ComplexCondition")
internal suspend fun AppDatabase.reconcilePreviouslyTruncatedLegacySessionBinding(
	entry: RetainedImportedStepsEntry,
	loaded: LoadedImportedSessionCountDomainBinding,
	expected: com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2,
): Boolean {
	val binding = loaded.binding
	val graph = loaded.graph
	if (binding.sourceSchemaVersion != 1 || graph == expected) return false
	require(binding.productKind ==
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY)
	require(binding.productIdentity == entry.metadata.identity)
	require(binding.productRevision == IMPORTED_SESSION_PRODUCT_REVISION)
	require(binding.graphIdentity == graph.identity.value)
	require(graph.receipts.isEmpty())
	if (binding.sourceReceiptIdentity == null) {
		require(binding.sourceArchiveContentChecksum == null)
		require(loaded.bindingReceipt == null)
	} else {
		val receipt = requireNotNull(loaded.bindingReceipt)
		require(receipt.receiptIdentity == binding.sourceReceiptIdentity)
		require(receipt.entryIdentity == entry.metadata.identity)
		require(receipt.graphIdentity == graph.identity.value)
		require(receipt.archiveContentChecksum == binding.sourceArchiveContentChecksum)
	}
	loaded.fileReceipts.forEach { receipt ->
		require(receipt.entryIdentity == entry.metadata.identity)
		require(receipt.graphIdentity == graph.identity.value)
		require(receipt.archiveContentChecksum == entry.metadata.contentChecksum)
	}
	loaded.bindingReceipt?.let { require(it in loaded.fileReceipts) }
	val expectedRoots = expected.roots.associateBy { it.stableSessionRootKey() }
	require(expectedRoots.size == expected.roots.size)
	val storedRoots = graph.roots.associateBy { it.stableSessionRootKey() }
	require(storedRoots.size == graph.roots.size)
	if (!storedRoots.keys.containsAll(expectedRoots.keys) ||
		storedRoots.keys == expectedRoots.keys
	) {
		return false
	}
	val storedOwners = graph.ownerRevisions.associateBy {
		Triple(it.ownerKind, it.ownerIdentity.value, it.ownerRevision)
	}
	require(storedOwners.size == graph.ownerRevisions.size)
	require(storedOwners.size == graph.roots.size)
	val expectedOwners = expected.ownerRevisions.associateBy {
		Triple(it.ownerKind, it.ownerIdentity.value, it.ownerRevision)
	}
	val storedMarkers = graph.completenessMarkers.associateBy {
		it.ownerIdentity.value to it.ownerRevision
	}
	require(storedMarkers.size == graph.completenessMarkers.size)
	val expectedMarkers = expected.completenessMarkers.associateBy {
		it.ownerIdentity.value to it.ownerRevision
	}
	val liveRuns = entry.runs.associateBy { it.identity }
	val removedRoots = graph.roots.filter {
		it.stableSessionRootKey() !in expectedRoots
	}
	val retainedOwnerIdentities = expected.roots.map { it.ownerIdentity.value }.distinct()
	val retainedOwnerFences = retainedOwnerIdentities.chunked(OWNER_QUERY_BATCH).flatMap { identities ->
		importedPortableStepsCountDomainDao().ownerFences(identities, identities.size + 1)
	}
	require(retainedOwnerFences.isEmpty()) {
		"Retained legacy Steps owner has terminal authority"
	}
	val fenceByRun = linkedMapOf<String, SourceDeletionFenceEntity>()
	graph.roots.forEach { root ->
		require(root.ownerRevision == LEGACY_UNPROVEN_REVISION)
		require(root.ownerKind in SESSION_OWNER_KINDS)
		require(
			root.ownerIdentity == PortableCountDomainIntegrity.unprovenOwnerIdentity(
				root.ownerKind,
				root.productIdentity.value,
			),
		)
		val owner = requireNotNull(
			storedOwners[Triple(root.ownerKind, root.ownerIdentity.value, root.ownerRevision)],
		)
		require(owner.operation == PortableCountDomainOperation.UNPROVEN)
		require(owner.receiptIdentity == null)
		require(owner.scopeIdentity == PortableCountDomainIntegrity.unprovenScopeIdentity(
			root.containerIdentity.value,
		))
		require(owner.linkedAtMs == LEGACY_UNPROVEN_LINK_TIME_MS)
		if (root.stableSessionRootKey() in expectedRoots) {
			require(owner == expectedOwners.getValue(
				Triple(root.ownerKind, root.ownerIdentity.value, root.ownerRevision),
			))
			if (root.ownerKind == PortableCountDomainOwnerKind.SESSION_COMPLETENESS) {
				require(storedMarkers[root.ownerIdentity.value to root.ownerRevision] ==
					expectedMarkers[root.ownerIdentity.value to root.ownerRevision])
			}
		} else {
			require(root.ownerKind == PortableCountDomainOwnerKind.SESSION_FACT)
			val run = liveRuns[root.containerIdentity.value] ?: return false
			val fence = databaseRetentionFence(entry, run.identity, run.deletionScopeDigest)
				?: return false
			fenceByRun[run.identity] = fence
		}
	}
	require(
		graph.completenessMarkers.size ==
			graph.roots.count { it.ownerKind == PortableCountDomainOwnerKind.SESSION_COMPLETENESS },
	)
	graph.completenessMarkers.forEach { marker ->
		require(marker.ownerRevision == LEGACY_UNPROVEN_REVISION)
		require(marker.terminalState == PortableCountDomainCompletenessState.UNPROVEN)
		require(marker.lastAdmissionOrdinal == null && marker.lastSourceSequence == null)
		require(marker.providerFlushOutcome == LEGACY_UNPROVEN_OUTCOME)
		require(marker.registrationRemovalOutcome == LEGACY_UNPROVEN_OUTCOME)
		require(graph.roots.any {
			it.ownerKind == PortableCountDomainOwnerKind.SESSION_COMPLETENESS &&
				it.ownerIdentity == marker.ownerIdentity &&
				it.ownerRevision == marker.ownerRevision
		})
	}
	val removedByFenceTime = removedRoots.groupBy {
		fenceByRun.getValue(it.containerIdentity.value).deletedAtMs
	}
	removedByFenceTime.forEach { (fencedAtMs, roots) ->
		insertOrAuthenticateImportedPortableOwnerFences(
			authenticatedImportedPortableSelectedOwnerFences(
				selections = listOf(
					AuthenticatedImportedPortableRootSelection(
						authenticated = AuthenticatedImportedPortableGraphBinding(binding, graph),
						selectedRoots = roots,
					),
				),
				fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_RETENTION,
				collectedDataEpoch = entry.metadata.collectedDataEpoch,
				fencedAtMs = fencedAtMs,
				maximumFenceCount = roots.size,
			),
		)
	}
	val replacement = entry.legacyUnprovenCountDomainGraph()
	require(replacement == expected) {
		"Retained legacy Steps graph changed during terminal fencing"
	}
	replaceImportedLegacySessionGraph(
		entryIdentity = entry.metadata.identity,
		previous = AuthenticatedImportedPortableGraphBinding(binding, graph),
		replacement = replacement,
		expectedFileReceiptCount = loaded.fileReceiptCount,
	)
	return true
}

private suspend fun AppDatabase.databaseRetentionFence(
	entry: RetainedImportedStepsEntry,
	runIdentity: String,
	deletionScopeDigest: String,
): SourceDeletionFenceEntity? {
	val fence = sourceDeletionFenceDao().get(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
		SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		deletionScopeDigest,
	) ?: return null
	return fence.takeIf {
		StepFactRevisionIntegrity.isRetentionTruncationFence(
			it,
			entry.metadata.identity,
			runIdentity,
			entry.metadata.collectedDataEpoch,
		)
	}
}

private suspend fun AppDatabase.replaceImportedLegacySessionGraph(
	entryIdentity: String,
	previous: AuthenticatedImportedPortableGraphBinding,
	replacement: com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2,
	expectedFileReceiptCount: Int,
) {
	val dao = importedPortableStepsCountDomainDao()
	require(dao.graph(replacement.identity.value) == null) {
		"Retained legacy Steps graph collides with an existing graph"
	}
	dao.insertAuthenticatedGraph(
		replacement,
		ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
	)
	if (expectedFileReceiptCount > 0) {
		require(
			dao.updateFileReceiptGraphIdentity(
				entryIdentity,
				previous.binding.graphIdentity,
				replacement.identity.value,
			) == expectedFileReceiptCount,
		)
	}
	require(
		dao.deleteBindingExact(
			previous.binding.productKind,
			previous.binding.productIdentity,
			previous.binding.productRevision,
			previous.binding.graphIdentity,
		) == 1,
	)
	dao.insertBinding(previous.binding.copy(graphIdentity = replacement.identity.value))
	require(dao.deleteGraphIfUnbound(previous.binding.graphIdentity) == 1)
}

private suspend fun AppDatabase.fenceImportedPortableSessionRoots(
	authenticated: AuthenticatedImportedPortableGraphBinding,
	roots: List<PortableCountDomainRootV2>,
	fenceKind: String,
	collectedDataEpoch: Long,
	fencedAtMs: Long,
) {
	val fences = roots.map { root ->
		val owner = authenticated.graph.ownerRevisions.single {
			it.ownerKind == root.ownerKind &&
				it.ownerIdentity == root.ownerIdentity &&
				it.ownerRevision == root.ownerRevision
		}
		ImportedPortableStepsCountDomainOwnerFenceEntity.create(
			ownerKind = owner.ownerKind.name,
			ownerIdentity = owner.ownerIdentity.value,
			scopeIdentity = owner.scopeIdentity.value,
			latestSourceRevision = owner.ownerRevision,
			latestOwnerEffectChecksum = owner.ownerEffectChecksum.value,
			productKind = authenticated.binding.productKind,
			productIdentity = authenticated.binding.productIdentity,
			graphIdentity = authenticated.binding.graphIdentity,
			fenceKind = fenceKind,
			collectedDataEpoch = collectedDataEpoch,
			fencedAtMs = fencedAtMs,
		)
	}.distinctBy { it.ownerKind to it.ownerIdentity }
	insertOrAuthenticateImportedPortableOwnerFences(fences)
}

internal suspend fun AppDatabase.insertOrAuthenticateImportedPortableOwnerFences(
	fences: List<ImportedPortableStepsCountDomainOwnerFenceEntity>,
) {
	if (fences.isEmpty()) return
	val dao = importedPortableStepsCountDomainDao()
	val existing = fences.chunked(OWNER_QUERY_BATCH).flatMap { batch ->
		dao.ownerFences(batch.map { it.ownerIdentity }, batch.size + 1)
	}
	val existingByOwner = existing.associateBy { it.ownerKind to it.ownerIdentity }
	require(existingByOwner.size == existing.size)
	val missing = fences.filter { candidate ->
		existingByOwner[candidate.ownerKind to candidate.ownerIdentity]?.let { stored ->
			require(stored.hasSameTerminalAuthority(candidate))
			false
		} ?: true
	}
	if (missing.isNotEmpty()) dao.insertOwnerFences(missing)
}

/** Removes an entry graph only after every product member has been deleted and fenced. */
suspend fun AppDatabase.removeImportedPortableSessionGraph(entryIdentity: String) {
	val dao = importedPortableStepsCountDomainDao()
	val binding = dao.binding(
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
		entryIdentity,
		IMPORTED_SESSION_PRODUCT_REVISION,
	) ?: return
	check(
		dao.deleteBindingExact(
			binding.productKind,
			binding.productIdentity,
			binding.productRevision,
			binding.graphIdentity,
		) == 1,
	)
	dao.deleteGraphIfUnbound(binding.graphIdentity)
}

fun authenticatedImportedPortableOwnerFences(
	graphs: List<AuthenticatedImportedPortableGraphBinding>,
	fenceKind: String,
	collectedDataEpoch: Long,
	fencedAtMs: Long,
	maximumFenceCount: Int,
): List<ImportedPortableStepsCountDomainOwnerFenceEntity> =
	authenticatedImportedPortableSelectedOwnerFences(
		selections = graphs.map { authenticated ->
			AuthenticatedImportedPortableRootSelection(
				authenticated = authenticated,
				selectedRoots = authenticated.graph.roots,
			)
		},
		fenceKind = fenceKind,
		collectedDataEpoch = collectedDataEpoch,
		fencedAtMs = fencedAtMs,
		maximumFenceCount = maximumFenceCount,
	)

internal data class AuthenticatedImportedPortableRootSelection(
	val authenticated: AuthenticatedImportedPortableGraphBinding,
	val selectedRoots: List<PortableCountDomainRootV2>,
)

internal fun authenticatedImportedPortableSelectedOwnerFences(
	selections: List<AuthenticatedImportedPortableRootSelection>,
	fenceKind: String,
	collectedDataEpoch: Long,
	fencedAtMs: Long,
	maximumFenceCount: Int,
): List<ImportedPortableStepsCountDomainOwnerFenceEntity> {
	val appearances = linkedMapOf<PortableOwnerRevisionKey, PortableOwnerAppearance>()
	selections.sortedBy { it.authenticated.binding.productRevision }.forEach { selection ->
		val authenticated = selection.authenticated
		val binding = authenticated.binding
		val rootsByKey = authenticated.graph.roots.associateBy { it.portableRootSelectionKey() }
		require(rootsByKey.size == authenticated.graph.roots.size)
		val selectedKeys = selection.selectedRoots.map { it.portableRootSelectionKey() }
		require(selectedKeys.distinct().size == selectedKeys.size)
		require(selection.selectedRoots.all { root ->
			rootsByKey[root.portableRootSelectionKey()] == root
		}) {
			"Selected imported portable root is not part of the authenticated graph"
		}
		selection.selectedRoots.forEach { root ->
			val owner = authenticated.graph.ownerRevisions.singleOrNull {
				it.ownerKind == root.ownerKind &&
					it.ownerIdentity == root.ownerIdentity &&
					it.ownerRevision == root.ownerRevision
			} ?: error("Imported portable root has no exact owner revision")
			val key = PortableOwnerRevisionKey(
				owner.ownerKind.name,
				owner.ownerIdentity.value,
				owner.ownerRevision,
			)
			val candidate = PortableOwnerAppearance(binding, owner, root)
			val prior = appearances[key]
			if (prior != null && !prior.isByteIdenticalAuthority(candidate)) {
				error("Imported portable owner revision has conflicting graph appearances")
			}
			if (prior == null ||
				candidate.binding.productRevision > prior.binding.productRevision
			) {
				appearances[key] = candidate
			}
			require(appearances.size <= maximumFenceCount)
		}
	}

	return appearances.values
		.groupBy { it.owner.ownerKind to it.owner.ownerIdentity }
		.map { (_, lineage) ->
			require(lineage.map { it.owner.scopeIdentity }.distinct().size == 1)
			require(lineage.map { it.binding.productKind }.distinct().size == 1)
			require(lineage.map { it.binding.productIdentity }.distinct().size == 1)
			val latest = lineage.maxWith(
				compareBy<PortableOwnerAppearance> { it.owner.ownerRevision }
					.thenBy { it.binding.productRevision },
			)
			ImportedPortableStepsCountDomainOwnerFenceEntity.create(
				ownerKind = latest.owner.ownerKind.name,
				ownerIdentity = latest.owner.ownerIdentity.value,
				scopeIdentity = latest.owner.scopeIdentity.value,
				latestSourceRevision = latest.owner.ownerRevision,
				latestOwnerEffectChecksum = latest.owner.ownerEffectChecksum.value,
				productKind = latest.binding.productKind,
				productIdentity = latest.binding.productIdentity,
				graphIdentity = latest.binding.graphIdentity,
				fenceKind = fenceKind,
				collectedDataEpoch = collectedDataEpoch,
				fencedAtMs = fencedAtMs,
			)
		}
		.also { require(it.size <= maximumFenceCount) }
}

private fun PortableCountDomainRootV2.portableRootSelectionKey(): List<Any> = listOf(
	containerIdentity.value,
	productIdentity.value,
	ownerKind,
	ownerIdentity.value,
	ownerRevision,
)

private data class PortableOwnerRevisionKey(
	val ownerKind: String,
	val ownerIdentity: String,
	val ownerRevision: Long,
)

private data class PortableOwnerAppearance(
	val binding: ImportedPortableStepsCountDomainBindingEntity,
	val owner: PortableCountDomainOwnerRevisionV2,
	val root: PortableCountDomainRootV2,
) {
	fun isByteIdenticalAuthority(other: PortableOwnerAppearance): Boolean =
		owner == other.owner &&
			root == other.root &&
			binding.productKind == other.binding.productKind &&
			binding.productIdentity == other.binding.productIdentity
}

private fun ImportedPortableStepsCountDomainOwnerFenceEntity.hasSameTerminalAuthority(
	other: ImportedPortableStepsCountDomainOwnerFenceEntity,
): Boolean =
	ownerKind == other.ownerKind &&
		ownerIdentity == other.ownerIdentity &&
		scopeIdentity == other.scopeIdentity &&
		latestSourceRevision == other.latestSourceRevision &&
		latestOwnerEffectChecksum == other.latestOwnerEffectChecksum &&
		productKind == other.productKind &&
		productIdentity == other.productIdentity &&
		graphIdentity == other.graphIdentity &&
		fenceKind == other.fenceKind &&
		collectedDataEpoch == other.collectedDataEpoch

private fun PortableCountDomainRootV2.stableSessionRootKey(): List<String> = listOf(
		containerIdentity.value,
		productIdentity.value,
		ownerKind.name,
		ownerIdentity.value,
)

private const val IMPORTED_SESSION_PRODUCT_REVISION = 1L
private const val OWNER_QUERY_BATCH = 400
private const val LEGACY_UNPROVEN_REVISION = 1L
private const val LEGACY_UNPROVEN_LINK_TIME_MS = 0L
private const val LEGACY_UNPROVEN_OUTCOME = "LEGACY_V1_UNPROVEN"
private val SESSION_OWNER_KINDS = setOf(
	PortableCountDomainOwnerKind.SESSION_FACT,
	PortableCountDomainOwnerKind.SESSION_COMPLETENESS,
)
