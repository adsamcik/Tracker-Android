package com.adsamcik.tracker.stats.engine.aggregator

import com.adsamcik.tracker.stats.api.AggregatorSignal
import com.adsamcik.tracker.stats.api.AggregatorSnapshot
import com.adsamcik.tracker.stats.api.DetectedActivityType

/**
 * In-memory accumulator for real-time tracking statistics.
 * Receives per-cycle sensor signals and maintains running session and day totals.
 *
 * **Thread safety:** every public method synchronizes on a private monitor. The
 * `ProcessorPipeline` delivers signals under its own mutex but releases that mutex
 * before slow-path flush/snapshot calls, so an aggregator method can race a signal
 * arriving on another coroutine. Without per-method `@Synchronized` we'd see torn
 * snapshots or `ConcurrentModificationException` while iterating `activityVotes`.
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
	private val lock = Any()

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

	val isActive: Boolean get() = synchronized(lock) { active }

	/**
	 * Initialize for a new tracking session.
	 *
	 * @param sessionStartMs Session start timestamp
	 */
	fun start(sessionStartMs: Long) = synchronized(lock) {
		require(!active) { "Cannot start: aggregator already active" }
		active = true
		this.sessionStartMs = sessionStartMs
		lastSignalMs = sessionStartMs
		clearSessionAccumulatorsLocked()
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
	fun seedDayTotals(distanceM: Float, steps: Int, durationMs: Long, trips: Int) = synchronized(lock) {
		priorDayDistanceM = distanceM
		priorDaySteps = steps
		priorDayDurationMs = durationMs
		tripCount = trips
	}

	/**
	 * Process a per-cycle sensor signal.
	 * Accumulates distance, steps, speed, activity, and duration.
	 */
	fun onSignal(signal: AggregatorSignal) = synchronized(lock) {
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
	fun snapshot(): AggregatorSnapshot = synchronized(lock) {
		val avgSpeed = if (speedCount > 0) speedSum / speedCount else 0f
		val dominant = activityVotes.maxByOrNull { it.value }?.key

		AggregatorSnapshot(
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
	fun onTripCompleted() = synchronized(lock) {
		tripCount++
	}

	/**
	 * Finalize the current session. Returns final snapshot.
	 * After this call, [isActive] returns false and [start] can be called again.
	 */
	fun stop(): AggregatorSnapshot = synchronized(lock) {
		check(active) { "Cannot stop: aggregator not active" }
		val finalSnapshot = snapshotLocked()
		active = false
		clearSessionAccumulatorsLocked()
		finalSnapshot
	}

	/**
	 * Reset all state including day-level totals.
	 * Call on day rollover or when clearing all data.
	 */
	fun reset() = synchronized(lock) {
		active = false
		clearSessionAccumulatorsLocked()
		priorDayDistanceM = 0f
		priorDaySteps = 0
		priorDayDurationMs = 0L
		tripCount = 0
		sessionStartMs = 0L
		lastSignalMs = 0L
	}

	private fun clearSessionAccumulatorsLocked() {
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

	private fun snapshotLocked(): AggregatorSnapshot {
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

	/** Serialize all mutable state for crash-recovery checkpointing. */
	fun serialize(): ByteArray = synchronized(lock) {
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
		baos.toByteArray()
	}

	/** Restore mutable state from a checkpoint produced by [serialize]. */
	fun deserialize(data: ByteArray) = synchronized(lock) {
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
