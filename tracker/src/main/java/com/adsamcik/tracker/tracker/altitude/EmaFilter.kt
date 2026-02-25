package com.adsamcik.tracker.tracker.altitude

/**
 * Exponential Moving Average (EMA) filter for real-time signal smoothing.
 * Lightweight O(1) filter requiring only the previous filtered value.
 *
 * @param alpha Smoothing factor in (0, 1]. Lower = smoother but more lag.
 *              Typical values: 0.1-0.3 for GPS altitude smoothing.
 */
internal class EmaFilter(private val alpha: Float) {
	init {
		require(alpha > 0f && alpha <= 1f) { "Alpha must be in (0, 1], was $alpha" }
	}

	private var filteredValue: Double? = null

	/**
	 * Whether the filter has been initialized with at least one value.
	 */
	val isInitialized: Boolean
		get() = filteredValue != null

	/**
	 * Applies the EMA filter to a new raw value.
	 * On first call, initializes with the raw value (no smoothing).
	 *
	 * @param rawValue The new unfiltered measurement.
	 * @return The smoothed value.
	 */
	fun update(rawValue: Double): Double {
		val current = filteredValue
		val result = if (current == null) {
			rawValue
		} else {
			alpha * rawValue + (1.0 - alpha) * current
		}
		filteredValue = result
		return result
	}

	/**
	 * Resets the filter state. Next [update] call will reinitialize.
	 */
	fun reset() {
		filteredValue = null
	}
}
