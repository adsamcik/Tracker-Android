package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.logging.ReporterFacade
import com.adsamcik.tracker.stats.api.PolicyEscalationEngine
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.engine.ski.RealTimeSkiDetector
import com.adsamcik.tracker.stats.engine.ski.RealTimeSkiState
import com.adsamcik.tracker.stats.engine.ski.SkiDetectionConfig
import com.adsamcik.tracker.stats.engine.ski.SkiState
import com.adsamcik.tracker.stats.engine.ski.SkiStateListener
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.producer.BarometerDataProducer
import com.adsamcik.tracker.tracker.component.producer.PressureReading
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real-time ski tracking component that detects skiing activity from
 * barometric pressure and GPS speed, then adaptively manages the GPS tier.
 *
 * Runs as a [PostTrackerComponent] in every tier (including AMBIENT).
 * When skiing is detected, it locks the escalation engine to appropriate
 * tiers based on the current ski phase:
 * - DESCENDING → PRECISION (2-3s GPS for detailed track)
 * - ASCENDING → ACTIVE (coarse GPS for lift path)
 * - IDLE/WALK → releases lock (engine manages normally)
 *
 * No required data: operates on whatever sensors are available.
 * If barometer data is missing, the component silently does nothing.
 */
internal class SkiTrackingComponent : PostTrackerComponent, SkiStateListener {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private val detector = RealTimeSkiDetector(SkiDetectionConfig())
	private var escalationEngine: PolicyEscalationEngine? = null

	private val _skiState = MutableStateFlow<RealTimeSkiState?>(null)

	/**
	 * Observable ski state. Null when no ski data has been processed yet.
	 * Emits on every collection cycle that has barometric data.
	 */
	val skiState: StateFlow<RealTimeSkiState?> = _skiState.asStateFlow()

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
	}

	override suspend fun onDisable(context: Context) {
		detector.setListener(null)
		// Release any GPS tier lock
		escalationEngine?.clearMinimumTier()
		_skiState.value = null
	}

	override fun onNewData(
		context: Context,
		session: TrackerSession,
		collectionData: CollectionData,
		tempData: CollectionTempData
	) {
		val reading = tempData.tryGet<PressureReading>(BarometerDataProducer.PRESSURE_KEY)
			?: return

		val speedMps = collectionData.location?.speed ?: 0f
		val timeMs = tempData.timeMillis

		try {
			val state = detector.onSample(
				timeMs = timeMs,
				altitudeM = reading.altitudeM,
				speedMps = speedMps,
				stepRatePerMin = 0f
			)
			if (state != null) {
				_skiState.value = state
			}
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			ReporterFacade.report(e)
		}
	}

	/**
	 * Called by [RealTimeSkiDetector] when the confirmed ski state transitions.
	 * Manages GPS tier based on the current ski phase.
	 */
	override fun onStateChanged(previousState: SkiState, newState: RealTimeSkiState) {
		val engine = escalationEngine ?: return

		when (newState.state) {
			SkiState.DOWNHILL_RUN -> {
				engine.setMinimumTier(PolicyTier.PRECISION, "ski descent detected")
			}
			SkiState.LIFT_UP -> {
				engine.setMinimumTier(PolicyTier.ACTIVE, "ski lift ascent")
			}
			SkiState.IDLE, SkiState.WALK -> {
				// Only release the lock if we've confirmed skiing
				// (otherwise we were never locked in the first place)
				if (newState.completedRunCount > 0) {
					engine.clearMinimumTier()
				}
			}
		}
	}
}
