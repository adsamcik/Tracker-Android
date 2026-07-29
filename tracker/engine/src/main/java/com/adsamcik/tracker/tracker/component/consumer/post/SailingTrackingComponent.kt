package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.logging.api.ReporterFacade
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.stats.api.PolicyEscalationEngine
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.engine.sailing.RealTimeSailingDetector
import com.adsamcik.tracker.stats.engine.sailing.RealTimeSailingState
import com.adsamcik.tracker.stats.engine.sailing.SailingDetectionConfig
import com.adsamcik.tracker.stats.engine.sailing.SailingState
import com.adsamcik.tracker.stats.engine.sailing.SailingStateListener
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real-time sailing/boating tracking component that detects sailing activity from GPS speed
 * (no barometer/vertical-rate signal — see [SailingDetectionConfig] kdoc for why), then
 * adaptively manages the GPS tier.
 *
 * Runs as a [PostTrackerComponent] in every tier (including AMBIENT), mirroring
 * [SkiTrackingComponent]'s structure. When sailing is detected, locks the escalation engine to
 * [PolicyTier.PRECISION] for an accurate track; releases the lock once back to IDLE/WALK.
 *
 * Unlike ski detection, this does not query external infrastructure (no marina/harbor dataset)
 * and does not persist discrete run segments — it only drives GPS-tier adaptation and exposes
 * state for auto-tagging the session's activity as sailing.
 *
 * No required data: operates on whatever GPS speed and local activity-recognition context are
 * available. Without context, speed is reported only as boat-like motion and cannot trigger a
 * precision-tier lock.
 */
internal class SailingTrackingComponent : PostTrackerComponent, SailingStateListener {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private val config = SailingDetectionConfig()
	private val detector = RealTimeSailingDetector(config)
	private var escalationEngine: PolicyEscalationEngine? = null

	private val _sailingState = MutableStateFlow<RealTimeSailingState?>(null)

	/** Previous monotonic collection time for step-rate calculation. */
	private var lastCollectionElapsedTimeMs: Long = 0L

	/** Previous cumulative session distance, to derive a per-cycle delta. */
	private var lastSessionDistanceM: Float = 0f

	/**
	 * Observable sailing state. Null until the first GPS speed sample has been processed.
	 * Emits on every collection cycle that has location data.
	 */
	val sailingState: StateFlow<RealTimeSailingState?> = _sailingState.asStateFlow()

	/**
	 * Set the policy escalation engine for adaptive GPS management.
	 * Must be called before [onEnable].
	 */
	fun setEscalationEngine(engine: PolicyEscalationEngine?) {
		this.escalationEngine = engine
	}

	override suspend fun onEnable(context: Context) {
		detector.reset()
		detector.setListener(this)
		lastCollectionElapsedTimeMs = 0L
		lastSessionDistanceM = 0f
	}

	override suspend fun onDisable(context: Context) {
		detector.setListener(null)
		escalationEngine?.clearMinimumTier()
		_sailingState.value = null
		lastCollectionElapsedTimeMs = 0L
		lastSessionDistanceM = 0f
	}

	override fun onNewData(
		context: Context,
		session: TrackerSession,
		collectionData: CollectionData,
		cycle: TrackingCycle,
	) {
		val speedMps = collectionData.estimatedSpeedMps ?: return
		val elapsedTimeMs = cycle.elapsedRealtimeNanos / 1_000_000L

		// Compute step rate from step delta and elapsed time (same derivation as ski's).
		val newSteps = cycle.stepDelta ?: 0
		val stepRatePerMin = if (lastCollectionElapsedTimeMs > 0L && newSteps > 0) {
			val deltaMs = elapsedTimeMs - lastCollectionElapsedTimeMs
			if (deltaMs > 0) (newSteps.toFloat() / deltaMs) * 60_000f else 0f
		} else {
			0f
		}
		lastCollectionElapsedTimeMs = elapsedTimeMs

		val activity = collectionData.activity
		val motionContextAvailable = activity != null &&
			activity.activityType != DetectedActivity.UNKNOWN &&
			activity.activityType != DetectedActivity.TILTING
		val hasStrongVehicleOrBicycleSignature = motionContextAvailable &&
			activity.confidence >= 50 &&
			(activity.activityType == DetectedActivity.ON_BICYCLE ||
				activity.activityType == DetectedActivity.IN_VEHICLE)

		// SessionUpdateStage runs before PostProcessingStage, so session.distanceInM already
		// reflects this cycle; diff against the previous cycle's total for a per-cycle delta.
		val distanceDeltaM = (session.distanceInM - lastSessionDistanceM).coerceAtLeast(0f)
		lastSessionDistanceM = session.distanceInM

		try {
			val state = detector.onSample(
				timeMs = elapsedTimeMs,
				speedMps = speedMps,
				distanceDeltaM = distanceDeltaM,
				stepRatePerMin = stepRatePerMin,
				motionContextAvailable = motionContextAvailable,
				hasStrongVehicleOrBicycleSignature = hasStrongVehicleOrBicycleSignature,
			)
			_sailingState.value = state
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			ReporterFacade.report(e)
		}
	}

	/**
	 * Called by [RealTimeSailingDetector] when the confirmed sailing state transitions.
	 * Manages GPS tier based on the current phase.
	 */
	override fun onStateChanged(previousState: SailingState, newState: RealTimeSailingState) {
		val engine = escalationEngine ?: return

		when (newState.state) {
			SailingState.SAILING -> {
				engine.setMinimumTier(PolicyTier.PRECISION, "sailing detected")
			}
			SailingState.IDLE, SailingState.WALK -> {
				if (newState.totalSailingDurationMs > 0L) {
					engine.clearMinimumTier()
				}
			}
		}
	}
}
