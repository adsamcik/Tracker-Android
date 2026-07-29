package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.activity.ski.SkiInfrastructureManager
import com.adsamcik.tracker.stats.api.ski.SkiLift
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.logging.api.ReporterFacade
import com.adsamcik.tracker.stats.api.PolicyEscalationEngine
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.engine.ski.RealTimeSkiDetector
import com.adsamcik.tracker.stats.engine.ski.RealTimeSkiState
import com.adsamcik.tracker.stats.engine.ski.SkiDetectionConfig
import com.adsamcik.tracker.stats.engine.ski.SkiState
import com.adsamcik.tracker.stats.engine.ski.SkiStateListener
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

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
 * On the first LIFT_UP detection, queries [SkiInfrastructureManager] for
 * nearby ski lifts. If found, lowers the confirmation threshold to 1 cycle.
 *
 * No required data: operates on whatever sensors are available.
 * If barometer data is missing, the component silently does nothing.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SkiTrackingComponentEntryPoint {
	fun skiInfrastructureManager(): SkiInfrastructureManager
}

internal class SkiTrackingComponent : PostTrackerComponent, SkiStateListener {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private val detector = RealTimeSkiDetector(SkiDetectionConfig())
	private var escalationEngine: PolicyEscalationEngine? = null

	private val _skiState = MutableStateFlow<RealTimeSkiState?>(null)

	/** Previous monotonic collection timestamp for step rate calculation. */
	private var lastCollectionElapsedTimeMs: Long? = null

	private val listenerLock = Any()
	private var secondaryListeners: List<SkiStateListener> = emptyList()

	/** Infrastructure manager for resort proximity check. */
	private var infrastructureManager: SkiInfrastructureManager? = null

	/** Whether proximity has already been checked this session. */
	private var proximityChecked: Boolean = false

	/** Last known location for proximity check. */
	private var lastLat: Double = 0.0
	private var lastLon: Double = 0.0

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

	/**
	 * Set a secondary listener that receives the same state transitions.
	 * Used to wire [SkiSegmentWriter] for persistence.
	 */
	fun setSecondaryListener(listener: SkiStateListener?) {
		synchronized(listenerLock) {
			secondaryListeners = listOfNotNull(listener)
		}
	}

	internal fun setSecondaryListeners(listeners: List<SkiStateListener>) {
		synchronized(listenerLock) {
			secondaryListeners = listeners.toList()
		}
	}

	override suspend fun onEnable(context: Context) {
		detector.reset()
		detector.setListener(this)
		lastCollectionElapsedTimeMs = null
		proximityChecked = false
		infrastructureManager = try {
			val mgr = EntryPointAccessors.fromApplication(
				context.applicationContext,
				SkiTrackingComponentEntryPoint::class.java,
			).skiInfrastructureManager()
			if (mgr.isAvailable()) mgr else null
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			null
		}
	}

	override suspend fun onDisable(context: Context) {
		runCatching { detector.finish() }
			.onFailure(ReporterFacade::report)
		detector.setListener(null)
		escalationEngine?.clearMinimumTier()
		_skiState.value = null
		lastCollectionElapsedTimeMs = null
		// M7 fix: close infrastructure manager to release resources
		try {
			(infrastructureManager as? java.io.Closeable)?.close()
		} catch (e: Exception) {
			Reporter.report(e)
		}
		infrastructureManager = null
		proximityChecked = false
		nearbyLifts = emptyList()
	}

	override fun onNewData(
		context: Context,
		session: TrackerSession,
		collectionData: CollectionData,
		cycle: TrackingCycle
	) {
		val reading = cycle.pressure ?: return

		val speedMps = collectionData.estimatedSpeedMps ?: 0f
		val elapsedTimeMs = cycle.elapsedRealtimeNanos / NANOS_PER_MILLISECOND

		// Track last known GPS position for proximity check
		collectionData.location?.let { loc ->
			lastLat = loc.latitude
			lastLon = loc.longitude
		}

		// Compute step rate from step delta and elapsed time
		val newSteps = cycle.stepDelta ?: 0
		val previousElapsedTimeMs = lastCollectionElapsedTimeMs
		val stepRatePerMin = if (
			previousElapsedTimeMs != null &&
			elapsedTimeMs > previousElapsedTimeMs &&
			newSteps > 0
		) {
			(newSteps.toFloat() / (elapsedTimeMs - previousElapsedTimeMs)) * 60_000f
		} else {
			0f
		}
		if (previousElapsedTimeMs == null || elapsedTimeMs > previousElapsedTimeMs) {
			lastCollectionElapsedTimeMs = elapsedTimeMs
		}

		try {
			val state = detector.onSample(
				elapsedTimeMs = elapsedTimeMs,
				epochTimeMs = cycle.timestampMs,
				altitudeM = reading.altitudeM,
				speedMps = speedMps,
				stepRatePerMin = stepRatePerMin
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
		_skiState.value = newState

		val listeners = synchronized(listenerLock) { secondaryListeners }
		listeners.forEach { listener ->
			runCatching { listener.onStateChanged(previousState, newState) }
				.onFailure(ReporterFacade::report)
		}

		val engine = escalationEngine ?: return

		// On first LIFT_UP, check proximity to known ski lifts
		if (newState.state == SkiState.LIFT_UP && !proximityChecked) {
			checkResortProximity()
		}

		// Match nearest lift type on each LIFT_UP entry
		if (newState.state == SkiState.LIFT_UP && previousState != SkiState.LIFT_UP) {
			matchNearestLiftType()
		}

		when (newState.state) {
			SkiState.DOWNHILL_RUN -> {
				engine.setMinimumTier(PolicyTier.PRECISION, "ski descent detected")
			}
			SkiState.LIFT_UP -> {
				engine.setMinimumTier(PolicyTier.ACTIVE, "ski lift ascent")
			}
			SkiState.IDLE, SkiState.WALK -> {
				if (newState.completedRunCount > 0) {
					engine.clearMinimumTier()
				}
			}
		}
	}

	/** Cached nearby lifts from proximity check. */
	private var nearbyLifts: List<SkiLift> = emptyList()

	/**
	 * One-shot proximity check against OSM ski infrastructure.
	 * If near a known lift, tells the detector to confirm after 1 cycle.
	 * Caches results for lift type matching on subsequent LIFT_UP entries.
	 */
	private fun checkResortProximity() {
		proximityChecked = true
		val mgr = infrastructureManager ?: return
		if (lastLat == 0.0 && lastLon == 0.0) return

		try {
			val radiusDeg = PROXIMITY_RADIUS_M / METERS_PER_DEGREE
			nearbyLifts = mgr.findLiftsNearby(lastLat, lastLon, radiusDeg)
			if (nearbyLifts.isNotEmpty()) {
				detector.setNearResort(true)
			}
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			ReporterFacade.report(e)
		}
	}

	/**
	 * Match current position to the nearest known lift and set its type on the detector.
	 */
	private fun matchNearestLiftType() {
		if (nearbyLifts.isEmpty() || (lastLat == 0.0 && lastLon == 0.0)) {
			detector.setCurrentLiftType(null)
			return
		}

		// Find closest lift by start/end station distance
		val nearest = nearbyLifts.minByOrNull { lift ->
			val dStartLat = lift.startLat - lastLat
			val dStartLon = lift.startLon - lastLon
			val dEndLat = lift.endLat - lastLat
			val dEndLon = lift.endLon - lastLon
			val distStart = dStartLat * dStartLat + dStartLon * dStartLon
			val distEnd = dEndLat * dEndLat + dEndLon * dEndLon
			minOf(distStart, distEnd)
		}

		detector.setCurrentLiftType(nearest?.liftType)
	}

	companion object {
		private const val PROXIMITY_RADIUS_M = 1000.0
		private const val METERS_PER_DEGREE = 111_000.0
		private const val NANOS_PER_MILLISECOND = 1_000_000L
	}
}
