package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerRevisionV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainRootV2

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
	val graph = checkNotNull(
		loadAuthenticatedImportedSessionCountDomainBinding(entryIdentity),
	) {
		"Imported Steps count-domain graph is unverifiable"
	}.also {
		check(it.binding == binding)
	}.graph
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

private const val IMPORTED_SESSION_PRODUCT_REVISION = 1L
