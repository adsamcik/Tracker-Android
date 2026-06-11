package com.adsamcik.tracker.tracker.altitude

/**
 * 1D Kalman filter for altitude estimation.
 *
 * State vector: [altitude, verticalVelocity]
 * Measurement: scalar altitude from GPS or barometer
 *
 * This is a simple 2-state linear Kalman filter optimized for
 * the altitude fusion use case. No matrix library dependency —
 * all 2x2 math is inlined for clarity and performance.
 *
 * Thread-safety: All public mutating methods are synchronized.
 */
internal class AltitudeKalmanFilter(
	private val processNoiseAltitude: Double = DEFAULT_PROCESS_NOISE_ALTITUDE,
	private val processNoiseVelocity: Double = DEFAULT_PROCESS_NOISE_VELOCITY
) {
	// State: [altitude, verticalVelocity]
	private var x0: Double = 0.0 // altitude estimate (m)
	private var x1: Double = 0.0 // vertical velocity estimate (m/s)

	// Error covariance matrix P (2x2, symmetric)
	private var p00: Double = INITIAL_VARIANCE
	private var p01: Double = 0.0
	private var p10: Double = 0.0
	private var p11: Double = INITIAL_VARIANCE

	private var lastTimeMs: Long = 0L
	private var initialized: Boolean = false

	/**
	 * Whether the filter has been initialized with at least one measurement.
	 */
	val isInitialized: Boolean get() = initialized

	/**
	 * Current altitude estimate in meters.
	 */
	val altitude: Double get() = x0

	/**
	 * Current vertical velocity estimate in m/s.
	 */
	val verticalVelocity: Double get() = x1

	/**
	 * Current altitude uncertainty (1-sigma) in meters.
	 */
	val altitudeUncertainty: Double get() = kotlin.math.sqrt(p00)

	/**
	 * Prediction step: advance the state by dt seconds using constant-velocity model.
	 *
	 * State transition: x_new = F * x
	 *   F = [[1, dt], [0, 1]]
	 *
	 * Process noise: Q = [[q_alt * dt, 0], [0, q_vel * dt]]
	 */
	@Synchronized
	fun predict(timeMs: Long) {
		if (!initialized) return

		val dt = (timeMs - lastTimeMs) / 1000.0
		if (dt <= 0.0) return
		lastTimeMs = timeMs

		// State prediction: x = F * x
		x0 += x1 * dt
		// x1 stays the same (constant velocity model)

		// Covariance prediction: P = F * P * F' + Q
		val newP00 = p00 + dt * (p10 + p01) + dt * dt * p11 + processNoiseAltitude * dt
		val newP01 = p01 + dt * p11
		val newP10 = p10 + dt * p11
		val newP11 = p11 + processNoiseVelocity * dt

		p00 = newP00
		p01 = newP01
		p10 = newP10
		p11 = newP11
	}

	/**
	 * Update step: incorporate an altitude measurement.
	 *
	 * Measurement model: z = H * x + noise
	 *   H = [1, 0] (we observe altitude only)
	 *
	 * @param altitudeM Measured altitude in meters.
	 * @param measurementNoiseM2 Measurement noise variance (σ² in m²).
	 *        For GPS: use verticalAccuracy². For barometer: use ~1.0.
	 * @param timeMs Current time in milliseconds.
	 */
	@Synchronized
	fun update(altitudeM: Double, measurementNoiseM2: Double, timeMs: Long) {
		if (!initialized) {
			// First measurement initializes the filter
			x0 = altitudeM
			x1 = 0.0
			p00 = measurementNoiseM2
			p01 = 0.0
			p10 = 0.0
			p11 = INITIAL_VELOCITY_VARIANCE
			lastTimeMs = timeMs
			initialized = true
			return
		}

		// Prediction step first
		predict(timeMs)

		// Innovation (measurement residual): y = z - H * x
		val y = altitudeM - x0

		// Innovation covariance: S = H * P * H' + R = p00 + R
		val s = p00 + measurementNoiseM2

		if (s == 0.0) return // Degenerate case

		// Kalman gain: K = P * H' / S = [p00/S, p10/S]
		val k0 = p00 / s
		val k1 = p10 / s

		// State update: x = x + K * y
		x0 += k0 * y
		x1 += k1 * y

		// Covariance update: P = (I - K * H) * P
		val newP00 = (1.0 - k0) * p00
		val newP01 = (1.0 - k0) * p01
		val newP10 = -k1 * p00 + p10
		val newP11 = -k1 * p01 + p11

		p00 = newP00
		p01 = newP01
		p10 = newP10
		p11 = newP11
	}

	/**
	 * Resets the filter to uninitialized state.
	 */
	@Synchronized
	fun reset() {
		x1 = 0.0
		p00 = INITIAL_VARIANCE
		p01 = 0.0
		p10 = 0.0
		p11 = INITIAL_VARIANCE
		lastTimeMs = 0L
		initialized = false
	}

	companion object {
		/** Initial state uncertainty. Large value = low confidence in initial guess. */
		private const val INITIAL_VARIANCE = 1000.0

		/** Initial vertical velocity uncertainty. */
		private const val INITIAL_VELOCITY_VARIANCE = 10.0

		/**
		 * Default process noise for altitude.
		 * Represents expected altitude change variance per second from unmodeled dynamics.
		 * 0.5 m²/s is suitable for walking/hiking; increase for vehicle/skiing.
		 */
		const val DEFAULT_PROCESS_NOISE_ALTITUDE = 0.5

		/**
		 * Default process noise for vertical velocity.
		 * Represents expected vertical acceleration variance.
		 * 0.1 m²/s³ is suitable for moderate activity.
		 */
		const val DEFAULT_PROCESS_NOISE_VELOCITY = 0.1
	}
}
