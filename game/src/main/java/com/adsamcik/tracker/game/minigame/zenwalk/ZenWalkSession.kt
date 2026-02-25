package com.adsamcik.tracker.game.minigame.zenwalk

import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameState
import kotlin.math.abs
import kotlin.math.min

/**
 * Zen Walk: maintain speed within ±0.5 km/h of target.
 * First 2 minutes auto-detect target pace (median). EMA smoothing α=0.15.
 * Pause handling: speed < 0.3 m/s for >10s → freeze timer.
 */
class ZenWalkSession(
	private val fixedTargetMps: Double? = null,
) : MiniGameSession() {

	private var emaSpeed: Double = 0.0
	private var targetSpeedMps: Double = 0.0
	private var calibrationSpeeds = mutableListOf<Double>()
	private var isCalibrating: Boolean = true
	private var calibrationStartMs: Long = 0L

	private var zoneSecondsTotal: Double = 0.0
	private var lastUpdateMs: Long = 0L
	private var pauseStartMs: Long = 0L
	private var isPaused: Boolean = false

	private var _state: MiniGameState = MiniGameState.IDLE

	override val state: MiniGameState get() = _state
	override val score: Double get() = zoneSecondsTotal

	override val statusText: String
		get() {
			val mins = (zoneSecondsTotal / 60).toInt()
			val secs = (zoneSecondsTotal % 60).toInt()
			return "🧘 ${mins}m${secs}s in zone"
		}

	override fun onLocationUpdate(
		latitude: Double,
		longitude: Double,
		speedMps: Float,
		accuracyM: Float,
		timestampMs: Long,
	) {
		if (accuracyM > MAX_ACCURACY_M) return

		if (_state == MiniGameState.IDLE) {
			_state = MiniGameState.RUNNING
			calibrationStartMs = timestampMs
			lastUpdateMs = timestampMs
			emaSpeed = speedMps.toDouble()
			if (fixedTargetMps != null) {
				targetSpeedMps = fixedTargetMps
				isCalibrating = false
			}
			return
		}

		// EMA smoothing
		emaSpeed = EMA_ALPHA * speedMps + (1 - EMA_ALPHA) * emaSpeed

		// Calibration phase: collect speeds for first 2 minutes
		if (isCalibrating) {
			if (speedMps > PAUSE_THRESHOLD_MPS) {
				calibrationSpeeds.add(speedMps.toDouble())
			}
			if (timestampMs - calibrationStartMs >= CALIBRATION_DURATION_MS && calibrationSpeeds.size >= MIN_CALIBRATION_SAMPLES) {
				targetSpeedMps = calibrationSpeeds.sorted()[calibrationSpeeds.size / 2]
				isCalibrating = false
			}
			lastUpdateMs = timestampMs
			return
		}

		val dtSeconds = (timestampMs - lastUpdateMs) / 1000.0
		lastUpdateMs = timestampMs

		// Pause handling
		if (emaSpeed < PAUSE_THRESHOLD_MPS) {
			if (!isPaused) {
				pauseStartMs = timestampMs
				isPaused = true
			}
			return
		}
		isPaused = false

		// Check if in zone (±0.5 km/h = ±0.139 m/s)
		val deviation = abs(emaSpeed - targetSpeedMps)
		if (deviation <= ZONE_HALF_WIDTH_MPS) {
			zoneSecondsTotal += dtSeconds
		}
	}

	override fun onSessionEnd() {
		_state = MiniGameState.FINISHED
	}

	override fun calculateXp(): Int {
		val baseXp = 15
		val timeBonus = min(zoneSecondsTotal / 60.0, MAX_TIME_BONUS_MINUTES) * 3
		return baseXp + timeBonus.toInt()
	}

	companion object {
		private const val EMA_ALPHA = 0.15
		private const val ZONE_HALF_WIDTH_MPS = 0.139 // 0.5 km/h
		private const val PAUSE_THRESHOLD_MPS = 0.3
		private const val CALIBRATION_DURATION_MS = 120_000L // 2 minutes
		private const val MIN_CALIBRATION_SAMPLES = 5
		private const val MAX_ACCURACY_M = 30f
		private const val MAX_TIME_BONUS_MINUTES = 30.0
	}
}
