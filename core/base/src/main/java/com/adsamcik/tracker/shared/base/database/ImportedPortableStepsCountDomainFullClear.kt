package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity

/**
 * Converts every live imported portable owner into a value-free terminal fence before full clear.
 */
internal fun AppDatabase.preserveImportedPortableCountDomainFullClearFences(
	newCollectedDataEpoch: Long,
	fencedAtMs: Long,
) {
	require(newCollectedDataEpoch >= 0L)
	require(fencedAtMs >= 0L)
	val dao = importedPortableStepsCountDomainDao()
	val bindings = dao.allBindingsForFullClear(MAX_FULL_CLEAR_BINDINGS + 1)
	require(bindings.size <= MAX_FULL_CLEAR_BINDINGS)
	val fences = mutableListOf<ImportedPortableStepsCountDomainOwnerFenceEntity>()
	for (binding in bindings) {
		val roots = dao.rootsForFullClear(
			binding.graphIdentity,
			MAX_FULL_CLEAR_ROOTS_PER_GRAPH + 1,
		)
		val owners = dao.latestOwnersForFullClear(
			binding.graphIdentity,
			MAX_FULL_CLEAR_ROOTS_PER_GRAPH + 1,
		)
		require(roots.isNotEmpty())
		require(roots.size <= MAX_FULL_CLEAR_ROOTS_PER_GRAPH)
		require(owners.size <= MAX_FULL_CLEAR_ROOTS_PER_GRAPH)
		require(fences.size.toLong() + roots.size <= MAX_FULL_CLEAR_OWNER_FENCES)
		val ownersByKey = owners.associateBy { it.ownerKind to it.ownerIdentity }
		require(ownersByKey.size == owners.size)
		roots.forEach { root ->
			val owner = requireNotNull(ownersByKey[root.ownerKind to root.ownerIdentity])
			require(owner.ownerRevision == root.ownerRevision)
			fences += ImportedPortableStepsCountDomainOwnerFenceEntity.create(
				ownerKind = owner.ownerKind,
				ownerIdentity = owner.ownerIdentity,
				scopeIdentity = owner.scopeIdentity,
				latestSourceRevision = owner.ownerRevision,
				latestOwnerEffectChecksum = owner.ownerEffectChecksum,
				productKind = binding.productKind,
				productIdentity = binding.productIdentity,
				graphIdentity = binding.graphIdentity,
				fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_FULL_CLEAR,
				collectedDataEpoch = newCollectedDataEpoch,
				fencedAtMs = fencedAtMs,
			)
		}
	}
	val unique = fences.groupBy { it.ownerKind to it.ownerIdentity }.map { (_, lineage) ->
		require(lineage.map { it.scopeIdentity }.distinct().size == 1)
		require(lineage.map { it.productIdentity }.distinct().size == 1)
		lineage.maxBy { it.latestSourceRevision }
	}
	val existing = unique.chunked(INSERT_BATCH).flatMap { batch ->
		dao.ownerFencesForFullClear(batch.map { it.ownerIdentity })
	}.associateBy { it.ownerKind to it.ownerIdentity }
	val missing = unique.filter { candidate ->
		existing[candidate.ownerKind to candidate.ownerIdentity]?.let { stored ->
			require(stored.scopeIdentity == candidate.scopeIdentity)
			require(stored.productKind == candidate.productKind)
			require(stored.productIdentity == candidate.productIdentity)
			false
		} ?: true
	}
	missing.chunked(INSERT_BATCH).forEach(dao::insertOwnerFencesForFullClear)
	dao.deleteAllBindingsForFullClear()
	dao.deleteAllGraphsForFullClear()
	dao.deleteAllFileReceiptsForFullClear()
}

private const val MAX_FULL_CLEAR_BINDINGS = 131_072
private const val MAX_FULL_CLEAR_ROOTS_PER_GRAPH = 131_072
private const val MAX_FULL_CLEAR_OWNER_FENCES = 262_144L
private const val INSERT_BATCH = 256
