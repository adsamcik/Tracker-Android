package com.adsamcik.tracker.stats.engine.ski

data class StreamingVerticalRateUpdate(
	val accepted: Boolean,
	val verticalRateMps: Float?,
)

/**
 * Streaming vertical rate calculator that processes altitude samples one at a time.
 *
 * Maintains a rolling median filter window and incremental EMA smoothing state.
 * Designed for real-time ski detection where samples arrive at ~1 Hz from the barometer.
 *
 * Pipeline per sample: raw altitude → rolling median → finite difference → EMA smooth.
 *
 * Pure Kotlin, no Android dependencies.
 */
class StreamingVerticalRateCalculator(
	private val medianWindowSize: Int = 5,
	private val emaAlpha: Float = 0.3f
) {
	private val altitudeWindow = ArrayDeque<TimestampedAltitude>(medianWindowSize + 1)
	private var lastFilteredAltitude: Float? = null
	private var lastFilteredTimeMs: Long? = null
	private var lastSmoothedRate: Float = 0f
	private var sampleCount: Int = 0
	private var lastAcceptedTimeMs: Long? = null

	/**
	 * Current smoothed vertical rate in m/s.
	 * Positive = ascending, negative = descending.
	 */
	val currentVerticalRate: Float
		get() = lastSmoothedRate

	/**
	 * Number of samples processed so far.
	 */
	val processedSamples: Int
		get() = sampleCount

	/**
	 * Whether enough samples have been collected to produce meaningful rates.
	 * Requires at least [medianWindowSize] samples to fill the median window.
	 */
	val isWarmedUp: Boolean
		get() = sampleCount >= medianWindowSize

	/**
	 * Process a new altitude sample and return the updated vertical rate.
	 *
	 * @param altitude timestamped altitude reading
	 * @return whether the sample was accepted and the smoothed vertical rate, or null rate while
	 *         the accepted samples are still warming the median window
	 */
	fun onNewSample(altitude: TimestampedAltitude): StreamingVerticalRateUpdate {
		if (!acceptTime(altitude.timeMs)) {
			return StreamingVerticalRateUpdate(accepted = false, verticalRateMps = null)
		}

		lastAcceptedTimeMs = altitude.timeMs
		altitudeWindow.addLast(altitude)
		if (altitudeWindow.size > medianWindowSize) {
			altitudeWindow.removeFirst()
		}
		sampleCount++

		if (altitudeWindow.size < medianWindowSize) {
			return StreamingVerticalRateUpdate(accepted = true, verticalRateMps = null)
		}

		// Step 1: Median filter — take the median altitude in the current window
		val filteredAltitude = medianOfWindow()
		val currentTimeMs = altitude.timeMs

		// Step 2: Finite difference derivative
		val prevAlt = lastFilteredAltitude
		val prevTime = lastFilteredTimeMs
		lastFilteredAltitude = filteredAltitude
		lastFilteredTimeMs = currentTimeMs

		if (prevAlt == null || prevTime == null) {
			return StreamingVerticalRateUpdate(accepted = true, verticalRateMps = 0f)
		}

		val dtSeconds = (currentTimeMs - prevTime) / 1000f
		check(dtSeconds > 0f)

		val rawRate = (filteredAltitude - prevAlt) / dtSeconds

		// Step 3: EMA smoothing
		lastSmoothedRate = emaAlpha * rawRate + (1f - emaAlpha) * lastSmoothedRate

		return StreamingVerticalRateUpdate(accepted = true, verticalRateMps = lastSmoothedRate)
	}

	/**
	 * Reset all state. Call when starting a new session.
	 */
	fun reset() {
		altitudeWindow.clear()
		lastFilteredAltitude = null
		lastFilteredTimeMs = null
		lastSmoothedRate = 0f
		sampleCount = 0
		lastAcceptedTimeMs = null
	}

	private fun acceptTime(timeMs: Long): Boolean =
		lastAcceptedTimeMs == null || timeMs > requireNotNull(lastAcceptedTimeMs)

	private fun medianOfWindow(): Float {
		val sorted = altitudeWindow.map { it.altitudeM }.sorted()
		return sorted[sorted.size / 2]
	}
}
