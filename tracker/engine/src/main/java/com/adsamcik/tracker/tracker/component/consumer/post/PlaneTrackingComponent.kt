package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.logging.api.ReporterFacade
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.stats.api.PolicyEscalationEngine
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.engine.plane.PlaneDetectionConfig
import com.adsamcik.tracker.stats.engine.plane.PlaneState
import com.adsamcik.tracker.stats.engine.plane.PlaneStateListener
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
 * Runs as a [PostTrackerComponent] in every tier (including AMBIENT), mirroring
 * [SkiTrackingComponent]'s structure. Manages the GPS tier based on the current flight phase:
 * - CLIMBING → ACTIVE (coarse GPS suffices; barometer already tracks the climb)
 * - DESCENDING → PRECISION (approach/landing track is worth the extra GPS detail)
 * - CRUISING → releases any lock (GPS is frequently unavailable at altitude; forcing a tier
 *   would just waste battery for no data)
 * - IDLE/WALK → releases lock once some airborne time has accumulated
 *
 * Unlike ski detection, this does not query external infrastructure (no airport/airspace
 * dataset) and does not persist discrete flight segments — it only drives GPS-tier adaptation
 * and exposes state for auto-tagging the session's activity as a flight.
 *
 * No required data: operates on whatever barometer data is available. If barometric data is
 * missing, the component silently does nothing.
 */
internal class PlaneTrackingComponent : PostTrackerComponent, PlaneStateListener {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private val detector = RealTimePlaneDetector(PlaneDetectionConfig())
	private var escalationEngine: PolicyEscalationEngine? = null

	private val _planeState = MutableStateFlow<RealTimePlaneState?>(null)

	/** Previous collection timestamp for step rate calculation. */
	private var lastCollectionTimeMs: Long = 0L

	/**
	 * Observable plane state. Null until the first barometric altitude sample has been processed.
	 * Emits on every collection cycle that has barometric data.
	 */
	val planeState: StateFlow<RealTimePlaneState?> = _planeState.asStateFlow()

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
		lastCollectionTimeMs = 0L
	}

	override suspend fun onDisable(context: Context) {
		detector.setListener(null)
		escalationEngine?.clearMinimumTier()
		_planeState.value = null
		lastCollectionTimeMs = 0L
	}

	override fun onNewData(
		context: Context,
		session: TrackerSession,
		collectionData: CollectionData,
		cycle: TrackingCycle,
	) {
		val reading = cycle.pressure ?: return

		val speedMps = collectionData.location?.speed ?: 0f
		val timeMs = cycle.timestampMs

		// Compute step rate from step delta and elapsed time (same derivation as ski's/sailing's).
		val newSteps = cycle.stepDelta ?: 0
		val stepRatePerMin = if (lastCollectionTimeMs > 0L && newSteps > 0) {
			val deltaMs = timeMs - lastCollectionTimeMs
			if (deltaMs > 0) (newSteps.toFloat() / deltaMs) * 60_000f else 0f
		} else {
			0f
		}
		lastCollectionTimeMs = timeMs

		try {
			val state = detector.onSample(
				timeMs = timeMs,
				altitudeM = reading.altitudeM,
				speedMps = speedMps,
				stepRatePerMin = stepRatePerMin,
			)
			if (state != null) {
				_planeState.value = state
			}
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			ReporterFacade.report(e)
		}
	}

	/**
	 * Called by [RealTimePlaneDetector] when the confirmed flight state transitions.
	 * Manages GPS tier based on the current flight phase.
	 */
	override fun onStateChanged(previousState: PlaneState, newState: RealTimePlaneState) {
		val engine = escalationEngine ?: return

		when (newState.state) {
			PlaneState.CLIMBING -> {
				engine.setMinimumTier(PolicyTier.ACTIVE, "plane climb detected")
			}
			PlaneState.DESCENDING -> {
				engine.setMinimumTier(PolicyTier.PRECISION, "plane descent detected")
			}
			PlaneState.CRUISING -> {
				// GPS is frequently unavailable at cruise altitude; forcing a minimum tier here
				// would just burn battery with no data to show for it.
				engine.clearMinimumTier()
			}
			PlaneState.IDLE, PlaneState.WALK -> {
				if (newState.totalAirborneDurationMs > 0L) {
					engine.clearMinimumTier()
				}
			}
		}
	}
}
