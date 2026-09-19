package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity

/** Fences one imported session run without deleting sibling source graphs. */
suspend fun AppDatabase.fenceImportedPortableSessionRun(
	entryIdentity: String,
	runIdentity: String,
	fenceKind: String,
	collectedDataEpoch: Long,
	fencedAtMs: Long,
) {
	val dao = importedPortableStepsCountDomainDao()
	val binding = dao.binding(
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
		entryIdentity,
		IMPORTED_SESSION_PRODUCT_REVISION,
	) ?: return
	val graph = dao.authenticatedGraph(
		binding.graphIdentity,
		com.adsamcik.tracker.shared.base.database.data
			.ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
	)
		?: error("Imported Steps count-domain graph is unverifiable")
	val roots = graph.roots.filter { it.containerIdentity.value == runIdentity }
	require(roots.isNotEmpty())
	val fences = roots.map { root ->
		val owner = graph.ownerRevisions.single {
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
			productKind = binding.productKind,
			productIdentity = binding.productIdentity,
			graphIdentity = binding.graphIdentity,
			fenceKind = fenceKind,
			collectedDataEpoch = collectedDataEpoch,
			fencedAtMs = fencedAtMs,
		)
	}.distinctBy { it.ownerKind to it.ownerIdentity }
	dao.insertOwnerFences(fences)
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

private const val IMPORTED_SESSION_PRODUCT_REVISION = 1L
