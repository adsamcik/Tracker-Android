package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
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
	require(dao.graph(graph.identity.value) == null) {
		"Graphless legacy Steps product collides with an unbound stored graph"
	}
	graph.roots.map { it.ownerIdentity.value }.distinct().chunked(OWNER_QUERY_BATCH).forEach {
		require(dao.rootsForOwners(it, 1).isEmpty()) {
			"Graphless legacy Steps product collides with stored roots"
		}
		require(dao.ownerFences(it, 1).isEmpty()) {
			"Graphless legacy Steps product collides with terminal owner fences"
		}
	}
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
	require(dao.graph(replacement.identity.value) == null) {
		"Retained legacy Steps graph collides with an existing graph"
	}
	dao.insertAuthenticatedGraph(
		replacement,
		ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
	)
	previous.binding.sourceReceiptIdentity?.let {
		val receiptCount = dao.fileReceiptCountForEntry(entryIdentity)
		require(receiptCount > 0)
		require(
			dao.updateFileReceiptGraphIdentity(
				entryIdentity,
				previous.binding.graphIdentity,
				replacement.identity.value,
			) == receiptCount,
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
): List<ImportedPortableStepsCountDomainOwnerFenceEntity> {
	val appearances = linkedMapOf<PortableOwnerRevisionKey, PortableOwnerAppearance>()
	graphs.sortedBy { it.binding.productRevision }.forEach { authenticated ->
		val binding = authenticated.binding
		authenticated.graph.roots.forEach { root ->
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

private const val IMPORTED_SESSION_PRODUCT_REVISION = 1L
private const val OWNER_QUERY_BATCH = 400
