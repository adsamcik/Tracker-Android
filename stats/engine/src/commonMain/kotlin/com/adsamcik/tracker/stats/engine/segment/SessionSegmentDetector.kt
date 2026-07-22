package com.adsamcik.tracker.stats.engine.segment

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.SegmentEvent
import com.adsamcik.tracker.stats.api.SegmentSignal
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.TripState
import kotlin.math.sqrt

/**
 * Pure-Kotlin state machine that detects meaningful travel episodes
 * from per-cycle sensor signals.
 *
 * State transitions:
 * ```
 * STATIONARY → DEPARTING → IN_TRIP → STOP_PENDING → ARRIVED → STATIONARY
 * ```
 *
 * Thread safety: Not thread-safe. Designed to be called from a single
 * component pipeline thread (TrackerService's componentMutex).
 *
 * @param config Tunable detection thresholds
 */
class SessionSegmentDetector(
	private val config: SegmentDetectorConfig = SegmentDetectorConfig(),
) {

	/** Current state of the detector. */
	var state: TripState = TripState.STATIONARY
		private set

	// Anchor point for drift suppression (E7 coordinates)
	private var anchorLatE7: Int? = null
	private var anchorLonE7: Int? = null

	// Departure tracking
	private var departureStartMs: Long = 0L
	private var departureLatE7: Int? = null
	private var departureLonE7: Int? = null
	private var departureTriggerActivity: DetectedActivityType? = null
	private var departureAccumulatedSteps: Int = 0
	private var departureAccumulatedDistance: Float = 0f

	// Trip accumulation
	private var tripStartMs: Long = 0L
	private var tripDistanceM: Float = 0f
	private var tripSteps: Int = 0
	private var tripSampleCount: Int = 0
	private var tripMaxSpeedMps: Float = 0f
	private var tripSpeedSum: Float = 0f
	private var tripSpeedCount: Int = 0
	private var lastTripLatE7: Int? = null
	private var lastTripLonE7: Int? = null

	// Activity vote histogram (activity ordinal -> count)
	private val activityVotes = mutableMapOf<DetectedActivityType, Int>()
	private var activityConfidenceSum: Int = 0
	private var activityConfidenceCount: Int = 0

	// Stop detection
	private var consecutiveStillCycles: Int = 0
	private var stopPendingStartMs: Long = 0L

	/**
	 * Process a sensor signal and return an event if a state transition occurred.
	 *
	 * @param signal Per-cycle sensor data
	 * @return Event if a meaningful transition happened, null otherwise
	 */
	fun onSignal(signal: SegmentSignal): SegmentEvent? {
		return when (state) {
			TripState.STATIONARY -> handleStationary(signal)
			TripState.DEPARTING -> handleDeparting(signal)
			TripState.IN_TRIP -> handleInTrip(signal)
			TripState.STOP_PENDING -> handleStopPending(signal)
			TripState.ARRIVED -> handleArrived(signal)
		}
	}

	/**
	 * Reset the detector to initial state. Call on service lifecycle boundaries.
	 */
	fun reset() {
		state = TripState.STATIONARY
		clearAnchor()
		clearDepartureState()
		clearTripState()
		clearStopState()
	}

	/**
	 * Force-end any active trip. Call during clean shutdown (onDisable).
	 *
	 * @param timestampMs Current time for the forced end
	 * @return TripEnded event if a trip was active, null otherwise
	 */
	fun forceEnd(timestampMs: Long): SegmentEvent? {
		return when (state) {
			TripState.IN_TRIP, TripState.STOP_PENDING -> {
				val event = buildTripEnded(timestampMs)
				reset()
				event
			}
			TripState.DEPARTING -> {
				val event = SegmentEvent.DepartureCancelled(
					timestampMs = timestampMs,
					reason = "forced shutdown"
				)
				reset()
				event
			}
			else -> {
				reset()
				null
			}
		}
	}

	// --- State handlers ---

	private fun handleStationary(signal: SegmentSignal): SegmentEvent? {
		// Update anchor if we have GPS
		val lat = signal.latE7
		val lon = signal.lonE7
		if (anchorLatE7 == null && lat != null && lon != null) {
			setAnchor(lat, lon)
		}

		if (isDepartureTriggered(signal)) {
			enterDeparting(signal)
		}

		return null // STATIONARY never emits events
	}

	private fun handleDeparting(signal: SegmentSignal): SegmentEvent? {
		departureAccumulatedSteps += signal.stepDelta
		signal.distanceDeltaM?.let { departureAccumulatedDistance += it }
		val elapsed = signal.timestampMs - departureStartMs

		if (elapsed >= config.departureConfirmationMs) {
			// Check if departure is confirmed
			val displacement = computeDisplacementFromAnchor(signal)
			if (displacement >= config.departureDisplacementM ||
				departureAccumulatedSteps >= config.departureMinSteps
			) {
				return confirmDeparture(signal)
			} else {
				// Insufficient movement - cancel
				val event = SegmentEvent.DepartureCancelled(
					timestampMs = signal.timestampMs,
					reason = "insufficient movement after ${elapsed}ms"
				)
				enterStationary(signal)
				return event
			}
		}

		// Before timeout: check for early confirmation via strong displacement
		val displacement = computeDisplacementFromAnchor(signal)
		if (displacement >= config.departureDisplacementM &&
			departureAccumulatedSteps >= config.departureMinSteps
		) {
			return confirmDeparture(signal)
		}

		return null
	}

	private fun handleInTrip(signal: SegmentSignal): SegmentEvent? {
		accumulateTripData(signal)

		if (isStillCycle(signal)) {
			consecutiveStillCycles++
			if (consecutiveStillCycles >= config.stillCyclesForStopPending) {
				enterStopPending(signal)
				return SegmentEvent.TripUpdated(
					currentTimeMs = signal.timestampMs,
					accumulatedDistanceM = tripDistanceM,
					accumulatedSteps = tripSteps,
					sampleCount = tripSampleCount,
					currentState = TripState.STOP_PENDING,
				)
			}
		} else {
			consecutiveStillCycles = 0
		}

		return null
	}

	private fun handleStopPending(signal: SegmentSignal): SegmentEvent? {
		accumulateTripData(signal)

		if (!isStillCycle(signal)) {
			// Movement resumes - back to IN_TRIP
			state = TripState.IN_TRIP
			clearStopState()
			return SegmentEvent.TripUpdated(
				currentTimeMs = signal.timestampMs,
				accumulatedDistanceM = tripDistanceM,
				accumulatedSteps = tripSteps,
				sampleCount = tripSampleCount,
				currentState = TripState.IN_TRIP,
			)
		}

		// Check mode-dependent timeout
		val elapsed = signal.timestampMs - stopPendingStartMs
		val timeout = getStopTimeout(signal.timestampMs)
		if (elapsed >= timeout) {
			// Transition through ARRIVED to STATIONARY
			state = TripState.ARRIVED
			return handleArrived(signal)
		}

		return null
	}

	private fun handleArrived(signal: SegmentSignal): SegmentEvent? {
		val event = buildTripEnded(signal.timestampMs)
		enterStationary(signal)
		return event
	}

	// --- Transition helpers ---

	private fun isDepartureTriggered(signal: SegmentSignal): Boolean {
		// GPS-based: displacement exceeds drift radius
		val displacement = computeDisplacementFromAnchor(signal)
		if (displacement > config.driftRadiusM) return true

		// Step + speed: movement with meaningful speed
		if (signal.stepDelta > 0 && (signal.speedMps ?: 0f) > config.stillnessSpeedThreshold) {
			return true
		}

		// Activity-based: moving activity with high confidence
		val activity = signal.activityType
		val confidence = signal.activityConfidence ?: 0
		if (activity != null && activity.isMoving && confidence >= config.departureActivityConfidence) {
			return true
		}

		return false
	}

	private fun enterDeparting(signal: SegmentSignal) {
		state = TripState.DEPARTING
		departureStartMs = signal.timestampMs
		departureLatE7 = signal.latE7
		departureLonE7 = signal.lonE7
		departureTriggerActivity = signal.activityType
		departureAccumulatedSteps = signal.stepDelta
		departureAccumulatedDistance = signal.distanceDeltaM ?: 0f
	}

	private fun confirmDeparture(signal: SegmentSignal): SegmentEvent {
		state = TripState.IN_TRIP
		tripStartMs = departureStartMs
		tripDistanceM = departureAccumulatedDistance
		tripSteps = departureAccumulatedSteps
		tripSampleCount = 0
		tripMaxSpeedMps = 0f
		tripSpeedSum = 0f
		tripSpeedCount = 0
		lastTripLatE7 = signal.latE7
		lastTripLonE7 = signal.lonE7
		activityVotes.clear()
		activityConfidenceSum = 0
		activityConfidenceCount = 0
		consecutiveStillCycles = 0

		// The departure accumulators already include this signal's distance and steps.
		accumulateTripData(signal, includeDistanceAndSteps = false)

		return SegmentEvent.TripStarted(
			startTimeMs = tripStartMs,
			triggerActivity = departureTriggerActivity,
		)
	}

	private fun enterStopPending(signal: SegmentSignal) {
		state = TripState.STOP_PENDING
		stopPendingStartMs = signal.timestampMs
	}

	private fun enterStationary(signal: SegmentSignal) {
		state = TripState.STATIONARY
		clearDepartureState()
		clearTripState()
		clearStopState()
		// Set anchor to current position
		val lat = signal.latE7
		val lon = signal.lonE7
		if (lat != null && lon != null) {
			setAnchor(lat, lon)
		} else {
			clearAnchor()
		}
	}

	private fun buildTripEnded(endTimeMs: Long): SegmentEvent.TripEnded? {
		if (tripDistanceM <= 0f && tripSteps <= 0) {
			return null
		}
		val primaryActivity = activityVotes.maxByOrNull { it.value }?.key
		val avgConfidence = if (activityConfidenceCount > 0) {
			activityConfidenceSum / activityConfidenceCount
		} else {
			null
		}
		val avgSpeed = if (tripSpeedCount > 0) tripSpeedSum / tripSpeedCount else 0f
		val durationMs = endTimeMs - tripStartMs

		val transportMode = TransportModeClassifier.classify(
			avgSpeedMps = avgSpeed,
			maxSpeedMps = tripMaxSpeedMps,
			totalSteps = tripSteps,
			durationMs = durationMs,
			primaryActivity = primaryActivity,
			totalDistanceM = tripDistanceM,
		)

		return SegmentEvent.TripEnded(
			startTimeMs = tripStartMs,
			endTimeMs = endTimeMs,
			totalDistanceM = tripDistanceM,
			totalSteps = tripSteps,
			sampleCount = tripSampleCount,
			primaryActivity = primaryActivity,
			averageActivityConfidence = avgConfidence,
			inferredTransportMode = transportMode,
		)
	}

	// --- Data accumulation ---

	private fun accumulateTripData(
		signal: SegmentSignal,
		includeDistanceAndSteps: Boolean = true,
	) {
		if (includeDistanceAndSteps) {
			signal.distanceDeltaM?.let { tripDistanceM += it }
			tripSteps += signal.stepDelta
		}

		// Location sample count
		val sigLat = signal.latE7
		val sigLon = signal.lonE7
		if (sigLat != null && sigLon != null) {
			tripSampleCount++
			lastTripLatE7 = sigLat
			lastTripLonE7 = sigLon
		}

		// Speed stats
		signal.speedMps?.let { speed ->
			tripSpeedSum += speed
			tripSpeedCount++
			if (speed > tripMaxSpeedMps) tripMaxSpeedMps = speed
		}

		// Activity histogram
		signal.activityType?.let { activity ->
			activityVotes[activity] = (activityVotes[activity] ?: 0) + 1
		}
		signal.activityConfidence?.let { confidence ->
			activityConfidenceSum += confidence
			activityConfidenceCount++
		}
	}

	private fun isStillCycle(signal: SegmentSignal): Boolean {
		val speedStill = (signal.speedMps ?: 0f) < config.stillnessSpeedThreshold
		val noSteps = signal.stepDelta == 0
		val activityStill = signal.activityType == DetectedActivityType.STILL &&
			(signal.activityConfidence ?: 0) >= config.minActivityConfidence

		return speedStill && noSteps && activityStill
	}

	private fun getStopTimeout(traceTimeMs: Long): Long {
		val currentMode = inferCurrentTransportMode(traceTimeMs)
		return when (currentMode) {
			TransportMode.WALK, TransportMode.RUN -> config.walkStopTimeoutMs
			TransportMode.TRANSIT, TransportMode.HIGH_SPEED_RAIL -> config.transitStopTimeoutMs
			else -> config.driveStopTimeoutMs
		}
	}

	/**
	 * Classify the in-progress trip at a recorded trace time.
	 *
	 * This must not use host execution time: replaying an old trace on another day
	 * must preserve the same timeout selection and emitted events.
	 */
	private fun inferCurrentTransportMode(traceTimeMs: Long): TransportMode {
		val durationMs = (traceTimeMs - tripStartMs).coerceAtLeast(0L)
		val avgSpeed = if (tripSpeedCount > 0) tripSpeedSum / tripSpeedCount else 0f
		val primaryActivity = activityVotes.maxByOrNull { it.value }?.key

		return TransportModeClassifier.classify(
			avgSpeedMps = avgSpeed,
			maxSpeedMps = tripMaxSpeedMps,
			totalSteps = tripSteps,
			durationMs = durationMs,
			primaryActivity = primaryActivity,
			totalDistanceM = tripDistanceM,
		)
	}

	// --- GPS displacement ---

	private fun computeDisplacementFromAnchor(signal: SegmentSignal): Float {
		val aLat = anchorLatE7 ?: return 0f
		val aLon = anchorLonE7 ?: return 0f
		val sLat = signal.latE7 ?: return 0f
		val sLon = signal.lonE7 ?: return 0f
		return approximateDistanceE7(aLat, aLon, sLat, sLon)
	}

	private fun setAnchor(latE7: Int, lonE7: Int) {
		anchorLatE7 = latE7
		anchorLonE7 = lonE7
	}

	private fun clearAnchor() {
		anchorLatE7 = null
		anchorLonE7 = null
	}

	private fun clearDepartureState() {
		departureStartMs = 0L
		departureLatE7 = null
		departureLonE7 = null
		departureTriggerActivity = null
		departureAccumulatedSteps = 0
		departureAccumulatedDistance = 0f
	}

	private fun clearTripState() {
		tripStartMs = 0L
		tripDistanceM = 0f
		tripSteps = 0
		tripSampleCount = 0
		tripMaxSpeedMps = 0f
		tripSpeedSum = 0f
		tripSpeedCount = 0
		lastTripLatE7 = null
		lastTripLonE7 = null
		activityVotes.clear()
		activityConfidenceSum = 0
		activityConfidenceCount = 0
	}

	private fun clearStopState() {
		consecutiveStillCycles = 0
		stopPendingStartMs = 0L
	}

	/** Serialize all mutable state for crash-recovery checkpointing. */
	fun serialize(): ByteArray {
		val baos = java.io.ByteArrayOutputStream()
		val dos = java.io.DataOutputStream(baos)
		dos.writeInt(state.ordinal)
		dos.writeInt(anchorLatE7 ?: Int.MIN_VALUE)
		dos.writeInt(anchorLonE7 ?: Int.MIN_VALUE)
		dos.writeLong(departureStartMs)
		dos.writeInt(departureLatE7 ?: Int.MIN_VALUE)
		dos.writeInt(departureLonE7 ?: Int.MIN_VALUE)
		dos.writeInt(departureTriggerActivity?.ordinal ?: -1)
		dos.writeInt(departureAccumulatedSteps)
		dos.writeFloat(departureAccumulatedDistance)
		dos.writeLong(tripStartMs)
		dos.writeFloat(tripDistanceM)
		dos.writeInt(tripSteps)
		dos.writeInt(tripSampleCount)
		dos.writeFloat(tripMaxSpeedMps)
		dos.writeFloat(tripSpeedSum)
		dos.writeInt(tripSpeedCount)
		dos.writeInt(lastTripLatE7 ?: Int.MIN_VALUE)
		dos.writeInt(lastTripLonE7 ?: Int.MIN_VALUE)
		dos.writeInt(activityVotes.size)
		for ((type, count) in activityVotes) {
			dos.writeInt(type.ordinal)
			dos.writeInt(count)
		}
		dos.writeInt(activityConfidenceSum)
		dos.writeInt(activityConfidenceCount)
		dos.writeInt(consecutiveStillCycles)
		dos.writeLong(stopPendingStartMs)
		dos.flush()
		return baos.toByteArray()
	}

	/** Restore mutable state from a checkpoint produced by [serialize]. */
	fun deserialize(data: ByteArray) {
		val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(data))
		state = TripState.entries[dis.readInt()]
		val aLat = dis.readInt()
		anchorLatE7 = if (aLat == Int.MIN_VALUE) null else aLat
		val aLon = dis.readInt()
		anchorLonE7 = if (aLon == Int.MIN_VALUE) null else aLon
		departureStartMs = dis.readLong()
		val dLat = dis.readInt()
		departureLatE7 = if (dLat == Int.MIN_VALUE) null else dLat
		val dLon = dis.readInt()
		departureLonE7 = if (dLon == Int.MIN_VALUE) null else dLon
		val actOrd = dis.readInt()
		departureTriggerActivity = if (actOrd == -1) null else DetectedActivityType.entries[actOrd]
		departureAccumulatedSteps = dis.readInt()
		departureAccumulatedDistance = dis.readFloat()
		tripStartMs = dis.readLong()
		tripDistanceM = dis.readFloat()
		tripSteps = dis.readInt()
		tripSampleCount = dis.readInt()
		tripMaxSpeedMps = dis.readFloat()
		tripSpeedSum = dis.readFloat()
		tripSpeedCount = dis.readInt()
		val tLat = dis.readInt()
		lastTripLatE7 = if (tLat == Int.MIN_VALUE) null else tLat
		val tLon = dis.readInt()
		lastTripLonE7 = if (tLon == Int.MIN_VALUE) null else tLon
		activityVotes.clear()
		val mapSize = dis.readInt()
		repeat(mapSize) {
			val ordinal = dis.readInt()
			val count = dis.readInt()
			activityVotes[DetectedActivityType.entries[ordinal]] = count
		}
		activityConfidenceSum = dis.readInt()
		activityConfidenceCount = dis.readInt()
		consecutiveStillCycles = dis.readInt()
		stopPendingStartMs = dis.readLong()
	}

	companion object {
		// Approximate meters per E7 unit at the equator
		private const val METERS_PER_E7_LAT = 0.0111f // ~111km / 1e7
		private const val WORLD_E7 = 3_600_000_000L
		private const val HALF_WORLD_E7 = WORLD_E7 / 2

		/**
		 * Fast approximate distance between two E7 coordinate pairs.
		 * Uses equirectangular approximation (suitable for short distances).
		 */
		fun approximateDistanceE7(
			lat1E7: Int, lon1E7: Int,
			lat2E7: Int, lon2E7: Int,
		): Float {
			val dLat = (lat2E7 - lat1E7) * METERS_PER_E7_LAT
			// Approximate longitude scaling using average latitude
			val avgLatRad = ((lat1E7 + lat2E7) / 2.0) / 1e7 * (Math.PI / 180.0)
			val cosLat = kotlin.math.cos(avgLatRad).toFloat()
			val longitudeDeltaE7 = signedLongitudeDeltaE7(lon2E7.toLong() - lon1E7.toLong())
			val dLon = longitudeDeltaE7.toFloat() * METERS_PER_E7_LAT * cosLat
			return sqrt(dLat * dLat + dLon * dLon)
		}

		private fun normalizeLongitudeE7(value: Long): Long =
			Math.floorMod(value + HALF_WORLD_E7, WORLD_E7) - HALF_WORLD_E7

		private fun signedLongitudeDeltaE7(delta: Long): Long = normalizeLongitudeE7(delta)
	}
}
