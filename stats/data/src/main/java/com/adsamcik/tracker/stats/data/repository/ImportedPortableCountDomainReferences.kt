package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.AuthenticatedImportedAmbientStepsLineage
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedAmbientStepsLineage
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedImportedAmbientStepsGraphLineage
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedImportedSessionCountDomainBinding
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerEffect
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerIdentity
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerKind
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerOrigin
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerReference

internal suspend fun AppDatabase.importedSessionCountDomainOwners(
	entry: RetainedImportedStepsEntry,
	runIdentity: String,
): List<StepsCountDomainOwnerReference>? {
	return try {
		loadAuthenticatedImportedSessionCountDomainBinding(entry)
			?.graph
			?.referencesForContainer(runIdentity)
	} catch (_: IllegalArgumentException) {
		null
	} catch (_: IllegalStateException) {
		null
	} catch (_: ArithmeticException) {
		null
	}
}

internal suspend fun AppDatabase.importedAmbientCountDomainOwner(
	dayIdentity: String,
	factIdentity: String,
): StepsCountDomainOwnerReference? {
	return try {
		val state = sourceEvidenceStateDao().get() ?: return null
		val lineage = importedAmbientStepsDao().loadAuthenticatedAmbientStepsLineage(
			dayIdentity,
			state.collectedDataEpoch,
		)
		val graph = loadAuthenticatedImportedAmbientStepsGraphLineage(lineage).lastOrNull()?.graph
			?: return null
		graph.referenceForProduct(
			containerIdentity = dayIdentity,
			productIdentity = factIdentity,
			expectedKind = StepsCountDomainOwnerKind.AMBIENT_FACT,
		)
	} catch (_: IllegalArgumentException) {
		null
	} catch (_: IllegalStateException) {
		null
	} catch (_: ArithmeticException) {
		null
	}
}

internal suspend fun AppDatabase.importedAmbientCountDomainOwners(
	lineage: AuthenticatedImportedAmbientStepsLineage,
): Map<String, StepsCountDomainOwnerReference>? {
	return try {
		val latestGraph = loadAuthenticatedImportedAmbientStepsGraphLineage(lineage).lastOrNull()
			?: return null
		if (lineage.latest.header.importRevision !in latestGraph.productImportRevisions) return null
		latestGraph.graph.ambientCountDomainOwners(lineage.latest.header.dayIdentity)
	} catch (_: IllegalArgumentException) {
		null
	} catch (_: IllegalStateException) {
		null
	} catch (_: ArithmeticException) {
		null
	}
}

internal fun PortableCountDomainGraphV2.ambientCountDomainOwners(
	dayIdentity: String,
): Map<String, StepsCountDomainOwnerReference>? {
	val selectedRoots = roots.filter {
		it.containerIdentity.value == dayIdentity &&
			it.ownerKind.name == StepsCountDomainOwnerKind.AMBIENT_FACT.name
	}
	if (selectedRoots.isEmpty()) return null
	val result = selectedRoots.associate { root ->
		root.productIdentity.value to (referenceForRoot(root) ?: return null)
	}
	return result.takeIf { it.size == selectedRoots.size }
}

private fun PortableCountDomainGraphV2.referencesForContainer(
	containerIdentity: String,
): List<StepsCountDomainOwnerReference>? {
	val selected = roots.filter { it.containerIdentity.value == containerIdentity }
	if (selected.isEmpty()) return null
	return selected.map { root ->
		referenceForRoot(root) ?: return null
	}.distinct()
}

private fun PortableCountDomainGraphV2.referenceForProduct(
	containerIdentity: String,
	productIdentity: String,
	expectedKind: StepsCountDomainOwnerKind,
): StepsCountDomainOwnerReference? {
	val selected = roots.singleOrNull {
		it.containerIdentity.value == containerIdentity &&
			it.productIdentity.value == productIdentity &&
			it.ownerKind.name == expectedKind.name
	} ?: return null
	return referenceForRoot(selected)
}

private fun PortableCountDomainGraphV2.referenceForRoot(
	root: com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainRootV2,
): StepsCountDomainOwnerReference? {
	val owner = ownerRevisions.singleOrNull {
		it.ownerKind == root.ownerKind &&
			it.ownerIdentity == root.ownerIdentity &&
			it.ownerRevision == root.ownerRevision
	} ?: return null
	return StepsCountDomainOwnerReference(
		kind = StepsCountDomainOwnerKind.valueOf(root.ownerKind.name),
		identity = StepsCountDomainOwnerIdentity.opaque(root.ownerIdentity.value),
		revision = root.ownerRevision,
		effect = StepsCountDomainOwnerEffect.opaque(owner.ownerEffectChecksum.value),
		origin = StepsCountDomainOwnerOrigin.IMPORTED_PORTABLE,
	)
}
