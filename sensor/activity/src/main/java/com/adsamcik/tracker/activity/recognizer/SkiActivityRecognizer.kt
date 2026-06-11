package com.adsamcik.tracker.activity.recognizer

import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.activity.ski.SkiInfrastructureManager
import com.adsamcik.tracker.stats.engine.ski.SkiDetectionConfig
import com.adsamcik.tracker.stats.engine.ski.SkiLiftProximityScorer
import com.adsamcik.tracker.stats.engine.ski.SkiLocationPoint
import com.adsamcik.tracker.stats.engine.ski.SkiRunExtractor
import com.adsamcik.tracker.stats.engine.ski.SkiSessionSummary
import com.adsamcik.tracker.stats.engine.ski.SkiSignal
import com.adsamcik.tracker.stats.engine.ski.SkiStateMachine
import com.adsamcik.tracker.stats.engine.ski.TimestampedAltitude
import com.adsamcik.tracker.stats.engine.ski.TimestampedVerticalRate
import com.adsamcik.tracker.stats.engine.ski.VerticalRateCalculator

/**
 * Recognizes alpine skiing sessions by analyzing altitude profiles and speed patterns.
 *
 * Uses a sensor-fusion approach:
 * 1. Barometric pressure (preferred) or GPS altitude (fallback) for vertical rate
 * 2. GPS speed for movement detection
 * 3. State machine to segment into IDLE/LIFT_UP/DOWNHILL_RUN/WALK
 * 4. Classifies as SLOPE_SPORTS if >= 2 lift+descent cycles detected
 *
 * Post-processing only: runs after session ends with full data available.
 */
internal class SkiActivityRecognizer(
	private val config: SkiDetectionConfig = SkiDetectionConfig()
) : ActivityRecognizer() {

	override val precisionConfidence: Int = 85

	/**
	 * Pressure samples for the current session, set externally by the worker.
	 * Null means no barometer data available (fall back to GPS altitude).
	 */
	var pressureSamples: List<PressureSample>? = null

	/**
	 * Optional ski infrastructure manager for lift proximity scoring.
	 * Set externally by the worker. Null means no infrastructure data available.
	 */
	var infrastructureManager: SkiInfrastructureManager? = null

	/**
	 * After successful recognition, contains the ski session summary.
	 * Null if not skiing or not yet resolved.
	 */
	var skiSessionSummary: SkiSessionSummary? = null
		private set

	override fun resolve(
		session: TrackerSession,
		locationCollection: Collection<DatabaseLocation>
	): ActivityRecognitionResult {
		skiSessionSummary = null

		val locations = locationCollection.sortedBy { it.time }
		if (locations.size < 10) {
			return ActivityRecognitionResult(null, 0)
		}

		// Build altitude time series (prefer barometer, fall back to GPS)
		val altitudes = buildAltitudeTimeSeries(locations)
		if (altitudes.size < 10) {
			return ActivityRecognitionResult(null, 0)
		}

		// Compute vertical rate
		val medianWindow = if (pressureSamples != null) {
			config.baroMedianWindow
		} else {
			config.gpsAltMedianWindow
		}
		val verticalRates = VerticalRateCalculator.compute(
			altitudes, medianWindow, config.verticalRateEmaAlpha
		)

		// Build signals by joining vertical rate with GPS speed
		val signals = buildSkiSignals(verticalRates, locations)
		if (signals.size < 10) {
			return ActivityRecognitionResult(null, 0)
		}

		// Run state machine
		val stateMachine = SkiStateMachine(config)
		val segments = stateMachine.process(signals)
		val cycles = stateMachine.countSkiCycles(segments)

		// Extract ski locations for summary and proximity scoring
		val skiLocations = locations.map { loc ->
			SkiLocationPoint(
				timeMs = loc.time,
				latitudeDeg = loc.latitude,
				longitudeDeg = loc.longitude,
				altitudeM = loc.altitude?.toFloat(),
				speedMps = loc.location.speed
			)
		}

		// Check lift proximity if infrastructure data is available
		val proximityResult = infrastructureManager?.let { manager ->
			if (manager.isAvailable()) {
				SkiLiftProximityScorer.score(
					segments = segments,
					locations = skiLocations,
					nearbyLiftFinder = { lat, lon, radiusDeg ->
						manager.findLiftsNearby(lat, lon, radiusDeg)
					}
				)
			} else {
				null
			}
		}

		// Allow single-cycle detection if on a known lift
		val effectiveMinCycles = if (proximityResult?.onKnownLift == true) {
			1
		} else {
			config.minCyclesForClassification
		}

		if (cycles < effectiveMinCycles) {
			return ActivityRecognitionResult(null, 0)
		}

		skiSessionSummary = SkiRunExtractor.extract(segments, skiLocations)

		// Confidence based on cycle count + proximity boost
		val proximityBoost = proximityResult?.confidenceBoost ?: 0
		val confidence = (when {
			cycles >= 5 -> 95
			cycles >= 3 -> 85
			cycles >= 2 -> 70
			cycles >= 1 -> 60
			else -> 0
		} + proximityBoost).coerceAtMost(100)

		return ActivityRecognitionResult(NativeSessionActivity.SLOPE_SPORTS, confidence)
	}

	private fun buildAltitudeTimeSeries(
		locations: List<DatabaseLocation>
	): List<TimestampedAltitude> {
		val samples = pressureSamples
		return if (!samples.isNullOrEmpty()) {
			samples.map { TimestampedAltitude(it.timeMs, it.altitudeM) }
		} else {
			locations.mapNotNull { loc ->
				loc.altitude?.let { alt ->
					TimestampedAltitude(loc.time, alt.toFloat())
				}
			}
		}
	}

	private fun buildSkiSignals(
		verticalRates: List<TimestampedVerticalRate>,
		locations: List<DatabaseLocation>
	): List<SkiSignal> {
		val rateMap = verticalRates.associateBy { it.timeMs }

		return locations.mapNotNull { loc ->
			val nearestRate = findNearestRate(loc.time, rateMap, verticalRates)
				?: return@mapNotNull null
			SkiSignal(
				timeMs = loc.time,
				verticalRateMps = nearestRate,
				speedMps = loc.location.speed ?: 0f,
				stepRatePerMin = 0f
			)
		}
	}

	private fun findNearestRate(
		timeMs: Long,
		rateMap: Map<Long, TimestampedVerticalRate>,
		rates: List<TimestampedVerticalRate>
	): Float? {
		rateMap[timeMs]?.let { return it.verticalRateMps }

		val toleranceMs = 5000L
		val nearest = rates.minByOrNull { kotlin.math.abs(it.timeMs - timeMs) }
		return if (nearest != null && kotlin.math.abs(nearest.timeMs - timeMs) <= toleranceMs) {
			nearest.verticalRateMps
		} else {
			null
		}
	}
}
