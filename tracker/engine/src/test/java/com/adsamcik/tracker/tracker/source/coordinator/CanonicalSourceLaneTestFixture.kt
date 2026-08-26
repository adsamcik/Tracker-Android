package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.liveSourceProjectionActivationOrdinal

/**
 * Test-only canonical setup. Production intentionally has no equivalent bypass: promotion must
 * fence the legacy writer and prove product-reader readiness before changing ownership.
 */
internal suspend fun installCanonicalProductLanesForTest(
	database: AppDatabase,
	bindings: Collection<ExecutableSourceLaneBinding>,
	rolloutRevision: Long,
	updatedAtMs: Long = rolloutRevision,
): RoomTrackingRolloutStateStore {
	require(bindings.isNotEmpty())
	val catalog = ExecutableSourceLaneCatalog.explicit(*bindings.toTypedArray())
	database.withTransaction {
		val activationOrdinal = database.liveSourceProjectionActivationOrdinal()
		bindings.forEach { binding ->
			database.sourceProjectionStateDao().installProductLane(
				SourceProductProjectionLaneEntity(
					sourceKind = binding.source.stableCode,
					bindingGeneration = binding.bindingGeneration,
					projectionId = binding.projectionId,
					projectionVersion = binding.projectionVersion,
					captureModeMask = binding.captureModeMask,
					productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
					activatedRolloutRevision = rolloutRevision,
					activationOrdinal = activationOrdinal,
					contiguousAdmissionOrdinal = activationOrdinal - 1L,
					retentionRequired = true,
					status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
					installedAtMs = updatedAtMs,
					updatedAtMs = updatedAtMs,
				),
			)
		}
	}
	return RoomTrackingRolloutStateStore(database, catalog).also { store ->
		store.save(
			TrackingRolloutState.eventCanonical(
				sources = bindings.map(ExecutableSourceLaneBinding::source).toSet(),
				revision = rolloutRevision,
				captureModes = bindings.associate { it.source to it.captureModes },
			),
			updatedAtMs = updatedAtMs,
		)
	}
}
