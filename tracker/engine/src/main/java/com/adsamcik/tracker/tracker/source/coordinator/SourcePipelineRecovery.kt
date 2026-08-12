package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationOutboxDispatcher
import com.adsamcik.tracker.tracker.source.projection.EventTrackingFrameOutboxDispatcher
import com.adsamcik.tracker.tracker.source.projection.ExplicitTrackingJoinProjection
import com.adsamcik.tracker.tracker.source.projection.LocationDomainProjection
import dev.tracebox.Tracebox
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourcePipelineRecovery @Inject constructor(
	private val database: AppDatabase,
	private val coordinator: TrackingCoordinator,
	private val activityEffects: ActivityAutomationOutboxDispatcher,
	private val trackingFrameEffects: EventTrackingFrameOutboxDispatcher,
) {
	suspend fun drainCommittedWork(): SourceRecoveryResult {
		val owner = "source-recovery:${UUID.randomUUID()}"
		val completedProjectionRecords = database.sourceProjectionStateDao().completeOutboxKinds(
			LocationDomainProjection.LOCATION_EFFECT_KINDS + ExplicitTrackingJoinProjection.OUTBOX_KIND,
			System.currentTimeMillis(),
		) + trackingFrameEffects.completeTerminalLegacyEffects()
		val drain = coordinator.drainAvailable(owner)
		val activityDelivered = if (drain is CoordinatorDrainResult.Complete) {
			drainOutboxToQuiescence("activity automation", activityEffects::drain)
		} else {
			0
		}
		val trackingFramesDelivered = if (drain is CoordinatorDrainResult.Complete) {
			drainOutboxToQuiescence("tracking frame", trackingFrameEffects::drain)
		} else {
			0
		}
		return SourceRecoveryResult(
			drain,
			activityDelivered,
			trackingFramesDelivered,
			completedProjectionRecords,
		)
	}

	private suspend fun drainOutboxToQuiescence(
		name: String,
		drainBatch: suspend (Int) -> Int,
	): Int {
		var delivered = 0
		repeat(MAX_OUTBOX_DRAIN_BATCHES) {
			val batch = drainBatch(OUTBOX_BATCH_SIZE)
			delivered += batch
			if (batch < OUTBOX_BATCH_SIZE) return delivered
		}
		Tracebox.log.warn("$name outbox drain reached its safety bound")
		return delivered
	}

	private companion object {
		const val OUTBOX_BATCH_SIZE = 100
		const val MAX_OUTBOX_DRAIN_BATCHES = 100
	}
}

data class SourceRecoveryResult(
	val drain: CoordinatorDrainResult,
	val activityEffectsDelivered: Int,
	val trackingFramesDelivered: Int,
	val completedProjectionRecords: Int,
)
