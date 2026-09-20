package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity

/**
 * Converts every live imported portable owner into a value-free terminal fence before full clear.
 */
internal fun AppDatabase.preserveImportedPortableCountDomainFullClearFences(
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
	val authenticated = mutableListOf<AuthenticatedImportedPortableGraphBinding>()
	bindings.filter {
		it.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY
	}.forEach { binding ->
		authenticated += loadAuthenticatedImportedSessionCountDomainBindingForFullClear(binding)
	}
	bindings.filter {
		it.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY
	}.groupBy { it.productIdentity }.forEach { (dayIdentity, dayBindings) ->
		val ambientDao = importedAmbientStepsDao()
		val headers = ambientDao.dayRevisionsForFullClear(
			dayIdentity,
			com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
				.MAX_REVISIONS_PER_DAY + 1,
		)
		require(headers.isNotEmpty())
		val lineage = ambientDao.loadAuthenticatedAmbientStepsLineageForFullClear(
			headers.last(),
			oldCollectedDataEpoch,
		)
		val graphLineage = loadAuthenticatedImportedAmbientStepsGraphLineageForFullClear(lineage)
		require(graphLineage.map { it.binding } == dayBindings)
		authenticated += graphLineage.map {
			AuthenticatedImportedPortableGraphBinding(it.binding, it.graph)
		}
	}
	require(authenticated.map { it.binding }.toSet() == bindings.toSet())
	val unique = authenticatedImportedPortableOwnerFences(
		graphs = authenticated,
		fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_FULL_CLEAR,
		collectedDataEpoch = newCollectedDataEpoch,
		fencedAtMs = fencedAtMs,
		maximumFenceCount = MAX_FULL_CLEAR_OWNER_FENCES,
	)
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
private const val MAX_FULL_CLEAR_OWNER_FENCES = 262_144
private const val INSERT_BATCH = 256
