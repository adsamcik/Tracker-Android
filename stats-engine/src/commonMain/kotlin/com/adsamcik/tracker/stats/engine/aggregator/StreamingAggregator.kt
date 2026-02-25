package com.adsamcik.tracker.stats.engine.aggregator

import com.adsamcik.tracker.stats.api.AggregatorSignal
import com.adsamcik.tracker.stats.api.AggregatorSnapshot
import com.adsamcik.tracker.stats.api.DetectedActivityType

/**
 * In-memory accumulator for real-time tracking statistics.
 * Receives per-cycle sensor signals and maintains running session and day totals.
 *
 * Thread safety: Not thread-safe. Designed to be called from a single
 * component pipeline thread (TrackerService's componentMutex).
 *
 * Lifecycle:
 * 1. [start] - initialize for new session
 * 2. [onSignal] - called per tracking cycle
 * 3. [snapshot] - periodically read for DB flush
 * 4. [stop] - finalize session
 *
 * @param clock Time source for testability (epoch millis)
 */
class StreamingAggregator(
	private val clock: () -> Long = System::currentTimeMillis,
) {
	// Session state
	private var active = false
	private var sessionStartMs = 0L
	private var lastSignalMs = 0L

	// Session accumulators
	private var sessionDistanceM = 0f
	private var sessionSteps = 0
	private var sessionDurationMs = 0L
	private var sampleCount = 0

	// Speed tracking
	private var currentSpeedMps: Float? = null
	private var speedSum = 0f
	private var speedCount = 0
	private var maxSpeedMps = 0f

	// Activity histogram
	private val activityVotes = mutableMapOf<DetectedActivityType, Int>()

	// Day-level pre-session totals (set via seedDayTotals)
	private var priorDayDistanceM = 0f
	private var priorDaySteps = 0
	private var priorDayDurationMs = 0L
	private var tripCount = 0

	val isActive: Boolean get() = active

	/**
	 * Initialize for a new tracking session.
	 *
	 * @param sessionStartMs Session start timestamp
	 */
	fun start(sessionStartMs: Long) {
		require(!active) { "Cannot start: aggregator already active" }
		active = true
		this.sessionStartMs = sessionStartMs
		lastSignalMs = sessionStartMs
		clearSessionAccumulators()
	}

	/**
	 * Seed day-level totals from previous sessions today.
	 * Call after [start] to include prior sessions in day totals.
	 *
	 * @param distanceM Total distance from prior sessions today
	 * @param steps Total steps from prior sessions today
	 * @param durationMs Total duration from prior sessions today
	 * @param trips Number of trips completed today
	 */
	fun seedDayTotals(distanceM: Float, steps: Int, durationMs: Long, trips: Int) {
		priorDayDistanceM = distanceM
		priorDaySteps = steps
		priorDayDurationMs = durationMs
		tripCount = trips
	}

	/**
	 * Process a per-cycle sensor signal.
	 * Accumulates distance, steps, speed, activity, and duration.
	 */
	fun onSignal(signal: AggregatorSignal) {
		check(active) { "Cannot process signal: aggregator not active" }

		// Duration: time since last signal (or since session start for first signal)
		if (lastSignalMs > 0 && signal.timestampMs > lastSignalMs) {
			sessionDurationMs += (signal.timestampMs - lastSignalMs)
		}
		lastSignalMs = signal.timestampMs

		// Distance
		signal.distanceDeltaM?.let { sessionDistanceM += it }

		// Steps
		sessionSteps += signal.stepDelta

		// Speed
		signal.speedMps?.let { speed ->
			currentSpeedMps = speed
			speedSum += speed
			speedCount++
			if (speed > maxSpeedMps) maxSpeedMps = speed
		}

		// GPS sample count
		if (signal.distanceDeltaM != null || signal.speedMps != null) {
			sampleCount++
		}

		// Activity votes
		signal.activityType?.let { activity ->
			activityVotes[activity] = (activityVotes[activity] ?: 0) + 1
		}
	}

	/**
	 * Get current accumulated state as immutable snapshot.
	 * Non-destructive: does not modify internal state.
	 */
	fun snapshot(): AggregatorSnapshot {
		val avgSpeed = if (speedCount > 0) speedSum / speedCount else 0f
		val dominant = activityVotes.maxByOrNull { it.value }?.key

		return AggregatorSnapshot(
			sessionStartMs = sessionStartMs,
			lastUpdateMs = lastSignalMs,
			sessionDistanceM = sessionDistanceM,
			sessionSteps = sessionSteps,
			sessionDurationMs = sessionDurationMs,
			dayTotalDistanceM = priorDayDistanceM + sessionDistanceM,
			dayTotalSteps = priorDaySteps + sessionSteps,
			dayTotalDurationMs = priorDayDurationMs + sessionDurationMs,
			currentSpeedMps = currentSpeedMps,
			avgSpeedMps = avgSpeed,
			maxSpeedMps = maxSpeedMps,
			sampleCount = sampleCount,
			dominantActivity = dominant,
			tripCount = tripCount,
		)
	}

	/**
	 * Record a trip completion for the day counter.
	 */
	fun onTripCompleted() {
		tripCount++
	}

	/**
	 * Finalize the current session. Returns final snapshot.
	 * After this call, [isActive] returns false and [start] can be called again.
	 */
	fun stop(): AggregatorSnapshot {
		check(active) { "Cannot stop: aggregator not active" }
		val finalSnapshot = snapshot()
		active = false
		clearSessionAccumulators()
		return finalSnapshot
	}

	/**
	 * Reset all state including day-level totals.
	 * Call on day rollover or when clearing all data.
	 */
	fun reset() {
		active = false
		clearSessionAccumulators()
		priorDayDistanceM = 0f
		priorDaySteps = 0
		priorDayDurationMs = 0L
		tripCount = 0
		sessionStartMs = 0L
		lastSignalMs = 0L
	}

	private fun clearSessionAccumulators() {
		sessionDistanceM = 0f
		sessionSteps = 0
		sessionDurationMs = 0L
		sampleCount = 0
		currentSpeedMps = null
		speedSum = 0f
		speedCount = 0
		maxSpeedMps = 0f
		activityVotes.clear()
	}

	/** Serialize all mutable state for crash-recovery checkpointing. */
	fun serialize(): ByteArray {
		val baos = java.io.ByteArrayOutputStream()
		val dos = java.io.DataOutputStream(baos)
		dos.writeBoolean(active)
		dos.writeLong(sessionStartMs)
		dos.writeLong(lastSignalMs)
		dos.writeFloat(sessionDistanceM)
		dos.writeInt(sessionSteps)
		dos.writeLong(sessionDurationMs)
		dos.writeInt(sampleCount)
		dos.writeFloat(currentSpeedMps ?: Float.NaN)
		dos.writeFloat(speedSum)
		dos.writeInt(speedCount)
		dos.writeFloat(maxSpeedMps)
		dos.writeFloat(priorDayDistanceM)
		dos.writeInt(priorDaySteps)
		dos.writeLong(priorDayDurationMs)
		dos.writeInt(tripCount)
		dos.writeInt(activityVotes.size)
		for ((type, count) in activityVotes) {
			dos.writeInt(type.ordinal)
			dos.writeInt(count)
		}
		dos.flush()
		return baos.toByteArray()
	}

	/** Restore mutable state from a checkpoint produced by [serialize]. */
	fun deserialize(data: ByteArray) {
		val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(data))
		active = dis.readBoolean()
		sessionStartMs = dis.readLong()
		lastSignalMs = dis.readLong()
		sessionDistanceM = dis.readFloat()
		sessionSteps = dis.readInt()
		sessionDurationMs = dis.readLong()
		sampleCount = dis.readInt()
		val speed = dis.readFloat()
		currentSpeedMps = if (speed.isNaN()) null else speed
		speedSum = dis.readFloat()
		speedCount = dis.readInt()
		maxSpeedMps = dis.readFloat()
		priorDayDistanceM = dis.readFloat()
		priorDaySteps = dis.readInt()
		priorDayDurationMs = dis.readLong()
		tripCount = dis.readInt()
		activityVotes.clear()
		val mapSize = dis.readInt()
		repeat(mapSize) {
			val ordinal = dis.readInt()
			val count = dis.readInt()
			activityVotes[DetectedActivityType.entries[ordinal]] = count
		}
	}
}
