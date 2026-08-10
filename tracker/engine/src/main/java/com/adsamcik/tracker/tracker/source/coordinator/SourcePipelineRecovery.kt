package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationOutboxDispatcher
import com.adsamcik.tracker.tracker.source.projection.EventTrackingFrameOutboxDispatcher
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourcePipelineRecovery @Inject constructor(
	private val coordinator: TrackingCoordinator,
	private val activityEffects: ActivityAutomationOutboxDispatcher,
	private val trackingFrameEffects: EventTrackingFrameOutboxDispatcher,
) {
	suspend fun drainCommittedWork(): SourceRecoveryResult {
		val owner = "source-recovery:${UUID.randomUUID()}"
		val drain = coordinator.drainAvailable(owner)
		val activityDelivered = if (drain is CoordinatorDrainResult.Complete) activityEffects.drain() else 0
		val trackingFramesDelivered = if (drain is CoordinatorDrainResult.Complete) trackingFrameEffects.drain() else 0
		return SourceRecoveryResult(drain, activityDelivered, trackingFramesDelivered)
	}
}

data class SourceRecoveryResult(
	val drain: CoordinatorDrainResult,
	val activityEffectsDelivered: Int,
	val trackingFramesDelivered: Int,
)
