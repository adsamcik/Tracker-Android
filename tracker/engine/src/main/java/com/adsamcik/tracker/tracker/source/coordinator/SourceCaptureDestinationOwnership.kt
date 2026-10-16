package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.tracker.source.model.SourceKind

internal suspend fun AppDatabase.hasExactSourceCaptureDestinationOwner(
	binding: ExecutableSourceLaneBinding,
	stage: ProductProjectionStage,
): Boolean {
	if (stage != ProductProjectionStage.EVENT_CANONICAL) {
		return true
	}
	return when (binding.source) {
		SourceKind.STEPS -> hasExactStepsDestinationOwner(binding)
		SourceKind.PRESSURE -> hasExactSessionFactDestinationOwner(binding,
			ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS)
		SourceKind.ACTIVITY -> hasExactSessionFactDestinationOwner(binding,
			ExecutableSourceLaneCatalog.ACTIVITY_SESSION_FACTS,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
			SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS)
		SourceKind.CELL -> hasExactSessionFactDestinationOwner(binding,
			ExecutableSourceLaneCatalog.CELL_SESSION_FACTS,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
			SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS)
		SourceKind.WIFI -> hasExactSessionFactDestinationOwner(binding,
			ExecutableSourceLaneCatalog.WIFI_SESSION_FACTS,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI,
			SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS)
		SourceKind.LOCATION -> hasExactLocationDestinationOwner(binding)
	}
}

private suspend fun AppDatabase.hasExactStepsDestinationOwner(
	binding: ExecutableSourceLaneBinding,
): Boolean {
	if (binding.projectionId != SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID ||
		binding.projectionVersion != SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION
	) {
		return true
	}
	val owner = sourceDestinationOwnerDao().get(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
	) ?: return false
	return owner.owner == SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS &&
		owner.ownerGeneration >= SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
}

private suspend fun AppDatabase.hasExactLocationDestinationOwner(
	binding: ExecutableSourceLaneBinding,
): Boolean {
	if (binding != ExecutableSourceLaneCatalog.LOCATION_CANONICAL_HANDOFF_V1 &&
		binding != ExecutableSourceLaneCatalog.LOCATION_CANONICAL_HANDOFF_V2
	) {
		return false
	}
	val owner = sourceDestinationOwnerDao().get(
		SourceDestinationOwnerEntity.SOURCE_LOCATION,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
	) ?: return false
	return owner.owner == SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE &&
		owner.ownerGeneration == SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION
}

private suspend fun AppDatabase.hasExactSessionFactDestinationOwner(
	binding: ExecutableSourceLaneBinding,
	baseBinding: ExecutableSourceLaneBinding,
	destination: String,
	expectedOwner: String,
): Boolean {
	if (!binding.hasSameWriterSemanticsAs(baseBinding)) {
		return false
	}
	val owner = sourceDestinationOwnerDao().get(binding.source.stableCode, destination) ?: return false
	return owner.owner == expectedOwner && owner.ownerGeneration ==
		SourceWriterGenerationContract.canonicalOwnerGeneration(binding.bindingGeneration)
}

private fun ExecutableSourceLaneBinding.hasSameWriterSemanticsAs(
	base: ExecutableSourceLaneBinding,
): Boolean = source == base.source &&
	projectionId == base.projectionId &&
	projectionVersion == base.projectionVersion &&
	(captureModes == base.captureModes || captureModeMask ==
		SourceWriterGenerationContract.captureModeMaskForKnownBinding(bindingGeneration))
