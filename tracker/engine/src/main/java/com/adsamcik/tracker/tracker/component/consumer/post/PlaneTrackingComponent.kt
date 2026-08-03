package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.stats.engine.plane.PlaneDetectionConfig
import com.adsamcik.tracker.stats.engine.plane.RealTimePlaneDetector
import com.adsamcik.tracker.stats.engine.plane.RealTimePlaneState
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real-time plane/flight tracking component that detects flight activity primarily from
 * barometric (cabin-pressure) altitude — always available, unlike GPS at cruise altitude — with
 * GPS speed only as an optional corroborator (see [PlaneDetectionConfig] kdoc).
 *
 * Runs passively when the tracking pipeline already supplies pressure data. It never starts GPS,
 * changes request fidelity, or changes collection cadence.
 *
 * Unlike ski detection, this does not query external infrastructure (no airport/airspace
 * dataset) and does not persist discrete flight segments. It exposes state for downstream
 * classification.
 *
 * Pressure is required. GPS speed and steps are optional corroborating inputs.
 */
internal class PlaneTrackingComponent : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = listOf(
		TrackerComponentRequirement.PRESSURE,
	)

	private val detector = RealTimePlaneDetector(PlaneDetectionConfig())
	private val _planeState = MutableStateFlow<RealTimePlaneState?>(null)

	/** Previous collection timestamp for step rate calculation. */
	private var lastCollectionTimeMs: Long = 0L

	/**
	 * Observable plane state. Null until the first barometric altitude sample has been processed.
	 * Emits on every collection cycle that has barometric data.
	 */
	val planeState: StateFlow<RealTimePlaneState?> = _planeState.asStateFlow()

	override suspend fun onEnable(context: Context) {
		detector.reset()
		lastCollectionTimeMs = 0L
	}

	override suspend fun onDisable(context: Context) {
		_planeState.value = null
		lastCollectionTimeMs = 0L
	}

	override fun onNewData(
		context: Context,
		session: TrackerSession,
		collectionData: CollectionData,
		cycle: TrackingCycle,
	) {
		val timeMs = cycle.elapsedRealtimeNanos / 1_000_000L
		val reading = cycle.pressure ?: return

		val speedMps = collectionData.estimatedSpeedMps ?: 0f

		// Compute step rate from step delta and elapsed time (same derivation as ski's/sailing's).
		val newSteps = cycle.stepDelta ?: 0
		val stepRatePerMin = if (lastCollectionTimeMs > 0L && newSteps > 0) {
			val deltaMs = timeMs - lastCollectionTimeMs
			if (deltaMs > 0) (newSteps.toFloat() / deltaMs) * 60_000f else 0f
		} else {
			0f
		}
		lastCollectionTimeMs = timeMs

		val state = detector.onSample(
			timeMs = timeMs,
			altitudeM = reading.altitudeM,
			speedMps = speedMps,
			stepRatePerMin = stepRatePerMin,
		)
		if (state != null) {
			_planeState.value = state
		}
	}
}
