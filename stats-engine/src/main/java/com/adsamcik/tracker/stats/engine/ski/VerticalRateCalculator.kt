package com.adsamcik.tracker.stats.engine.ski

import java.util.Arrays

/**
 * Timestamped altitude reading (from barometer or GPS).
 */
data class TimestampedAltitude(
    val timeMs: Long,
    val altitudeM: Float
)

/**
 * Computed vertical rate at a point in time.
 */
data class TimestampedVerticalRate(
    val timeMs: Long,
    val verticalRateMps: Float
)

/**
 * Computes smoothed vertical rate (m/s) from altitude time series.
 *
 * Pipeline: raw altitude → median filter → finite difference derivative → EMA smoothing.
 * Positive rate = ascending, negative rate = descending.
 *
 * Pure function, no Android dependencies.
 */
object VerticalRateCalculator {

    /**
     * Compute vertical rate time series from altitude readings.
     *
     * @param altitudes ordered altitude readings (must be sorted by time)
     * @param medianWindow size of median filter window (odd number preferred)
     * @param emaAlpha exponential moving average alpha (0-1, higher = less smoothing)
     * @return vertical rate at each point (first point is always 0)
     */
    fun compute(
        altitudes: List<TimestampedAltitude>,
        medianWindow: Int = 5,
        emaAlpha: Float = 0.3f
    ): List<TimestampedVerticalRate> {
        if (altitudes.size < 2) {
            return altitudes.map { TimestampedVerticalRate(it.timeMs, 0f) }
        }

        // Step 1: Median filter to remove single-point spikes
        val filtered = medianFilter(altitudes, medianWindow)

        // Step 2: Finite difference derivative (Δalt / Δtime) with EMA smoothing.
        val result = ArrayList<TimestampedVerticalRate>(filtered.size)
        var previousSmoothedRate = 0f
        result.add(TimestampedVerticalRate(filtered[0].timeMs, previousSmoothedRate))

        for (i in 1 until filtered.size) {
            val dt = (filtered[i].timeMs - filtered[i - 1].timeMs) / 1000f
            val rawRate = if (dt > 0f) {
                (filtered[i].altitudeM - filtered[i - 1].altitudeM) / dt
            } else {
                0f
            }
            val smoothedRate = emaAlpha * rawRate + (1 - emaAlpha) * previousSmoothedRate
            result.add(TimestampedVerticalRate(filtered[i].timeMs, smoothedRate))
            previousSmoothedRate = smoothedRate
        }

        return result
    }

    internal fun medianFilter(
        data: List<TimestampedAltitude>,
        windowSize: Int
    ): List<TimestampedAltitude> {
        if (windowSize <= 1 || data.size <= windowSize) return data
        val halfWindow = windowSize / 2
        val windowValues = FloatArray(halfWindow * 2 + 1)
        val result = ArrayList<TimestampedAltitude>(data.size)
        for (index in data.indices) {
            val sample = data[index]
            val start = (index - halfWindow).coerceAtLeast(0)
            val end = (index + halfWindow).coerceAtMost(data.lastIndex)
            val windowLength = end - start + 1
            for (windowIndex in 0 until windowLength) {
                windowValues[windowIndex] = data[start + windowIndex].altitudeM
            }
            Arrays.sort(windowValues, 0, windowLength)
            result.add(sample.copy(altitudeM = windowValues[windowLength / 2]))
        }
        return result
    }

    internal fun emaSmooth(
        rates: List<TimestampedVerticalRate>,
        alpha: Float
    ): List<TimestampedVerticalRate> {
        if (rates.isEmpty()) return rates
        val result = ArrayList<TimestampedVerticalRate>(rates.size)
        result.add(rates[0])
        for (i in 1 until rates.size) {
            val smoothed = alpha * rates[i].verticalRateMps + (1 - alpha) * result[i - 1].verticalRateMps
            result.add(rates[i].copy(verticalRateMps = smoothed))
        }
        return result
    }
}
