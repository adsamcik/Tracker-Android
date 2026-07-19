package com.adsamcik.tracker.game.minigame.zenwalk

import com.adsamcik.tracker.game.minigame.MiniGameDifficulty
import com.adsamcik.tracker.game.minigame.MiniGameFeedbackCue
import com.adsamcik.tracker.game.minigame.MiniGameCompletionOutcome
import com.adsamcik.tracker.game.minigame.MiniGameConfigurations
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameSignal
import com.adsamcik.tracker.game.minigame.MiniGameSignalQuality
import com.adsamcik.tracker.game.minigame.MiniGameState
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.game.minigame.ZenPaceZone
import com.adsamcik.tracker.game.minigame.ZenWalkConfiguration
import kotlin.math.abs
import kotlin.math.min

/**
 * Zen Walk: maintain speed within a difficulty-selected pace zone.
 * First 2 minutes auto-detect target pace (median). EMA smoothing α=0.15.
 * Pause handling: speed < 0.3 m/s for >10s → freeze timer.
 */
internal class ZenWalkSession(
	private val configuration: ZenWalkConfiguration = MiniGameConfigurations.DEFAULT_ZEN_WALK,
	private val personalBestBeforeRun: Double? = null,
	private val fixedTargetMps: Double? = null,
) : MiniGameSession(configuration, personalBestBeforeRun) {

	private var emaSpeed: Double = 0.0
	private var targetSpeedMps: Double = 0.0
	private val calibrationSpeeds = mutableListOf<Double>()
	private var isCalibrating: Boolean = true
	private var calibrationStartClockMs: Long? = null

	private var zoneSecondsTotal: Double = 0.0
	private var lastClockMs: Long? = null
	private var belowPauseThresholdSinceMs: Long? = null
	private var isPaused: Boolean = false
	private var _state: MiniGameState = MiniGameState.IDLE
	private var lastSignal: MiniGameSignal = MiniGameSignal.UNKNOWN
	private var activeElapsedTimeMs: Long = 0L
	private var hasRuntimeElapsedTime: Boolean = false
	private var feedbackZoneState: Boolean = false
	private var pendingZoneState: Boolean? = null
	private var pendingZoneSinceMs: Long? = null
	private var lastZoneFeedbackMs: Long? = null
	private var goalReached: Boolean = false
	private var personalBestCrossed: Boolean = false
	private var completionFeedbackEmitted: Boolean = false

	override val state: MiniGameState get() = _state
	override val score: Double get() = zoneSecondsTotal
	override val snapshot
		get() = buildSnapshot(
			phase = state.toSnapshotPhase(),
			signal = lastSignal,
			currentScore = score,
			visualPayload = MiniGameVisualPayload.ZenWalk(
				calibrationProgress = calibrationProgress(),
				currentSmoothedPaceMetersPerSecond = if (_state == MiniGameState.IDLE) null else emaSpeed,
				targetPaceZone = if (isCalibrating) null else targetPaceZone(),
				timeInZoneMs = (zoneSecondsTotal * MILLIS_PER_SECOND).toLong(),
			),
		)

	override val statusText: String
		get() {
			val mins = (zoneSecondsTotal / 60).toInt()
			val secs = (zoneSecondsTotal % 60).toInt()
			return "🧘 ${mins}m${secs}s in zone"
		}

	override fun onActiveElapsedTimeChanged(elapsedActiveTimeMs: Long) {
		super.onActiveElapsedTimeChanged(elapsedActiveTimeMs)
		this.activeElapsedTimeMs = elapsedActiveTimeMs
		hasRuntimeElapsedTime = true
	}

	override fun onLocationUpdate(
		latitude: Double,
		longitude: Double,
		speedMps: Float,
		accuracyM: Float,
		timestampMs: Long,
	) {
		if (_state == MiniGameState.FINISHED) return
		if (accuracyM > MAX_ACCURACY_M) {
			lastSignal = MiniGameSignal(MiniGameSignalQuality.POOR, ageMs = 0L, isStale = false)
			return
		}
		lastSignal = MiniGameSignal(MiniGameSignalQuality.GOOD, ageMs = 0L, isStale = false)
		val clockMs = effectiveClock(timestampMs)

		if (_state == MiniGameState.IDLE) {
			_state = MiniGameState.RUNNING
			calibrationStartClockMs = clockMs
			lastClockMs = clockMs
			emaSpeed = speedMps.toDouble()
			if (fixedTargetMps != null) {
				targetSpeedMps = fixedTargetMps
				isCalibrating = false
			}
			return
		}

		// EMA smoothing
		emaSpeed = EMA_ALPHA * speedMps + (1 - EMA_ALPHA) * emaSpeed
		val dtSeconds = ((clockMs - requireNotNull(lastClockMs)).coerceAtLeast(0L)) / MILLIS_PER_SECOND
		lastClockMs = clockMs

		// Calibration phase: collect speeds for first 2 minutes
		if (isCalibrating) {
			if (speedMps > PAUSE_THRESHOLD_MPS) {
				calibrationSpeeds.add(speedMps.toDouble())
			}
			if (
				clockMs - requireNotNull(calibrationStartClockMs) >= CALIBRATION_DURATION_MS &&
				calibrationSpeeds.size >= MIN_CALIBRATION_SAMPLES
			) {
				targetSpeedMps = calibrationSpeeds.sorted()[calibrationSpeeds.size / 2]
				isCalibrating = false
			}
			return
		}

		// A stop freezes scoring only after the documented sustained ten-second pause.
		if (emaSpeed < PAUSE_THRESHOLD_MPS) {
			val pauseStart = belowPauseThresholdSinceMs ?: clockMs.also {
				belowPauseThresholdSinceMs = it
			}
			isPaused = clockMs - pauseStart >= PAUSE_CONFIRMATION_MS
			if (isPaused) {
				updateZoneFeedback(inZone = false, clockMs = clockMs)
				return
			}
		}
		if (emaSpeed >= PAUSE_THRESHOLD_MPS) {
			belowPauseThresholdSinceMs = null
			isPaused = false
		}

		// Zone width is the only gameplay effect of difficulty.
		val deviation = abs(emaSpeed - targetSpeedMps)
		val inZone = !isPaused && deviation <= zoneHalfWidthMps()
		if (inZone) {
			zoneSecondsTotal += dtSeconds
		}
		updateZoneFeedback(inZone, clockMs)
		if (
			!personalBestCrossed &&
			personalBestBeforeRun != null &&
			zoneSecondsTotal - dtSeconds <= personalBestBeforeRun &&
			score > personalBestBeforeRun
		) {
			personalBestCrossed = true
			emitFeedback(MiniGameFeedbackCue.PersonalBestCrossed)
		}
		if (!goalReached && zoneSecondsTotal >= configuration.goal.scoreTarget) {
			goalReached = true
			_state = MiniGameState.FINISHED
			emitFeedback(MiniGameFeedbackCue.GoalReached)
		}
	}

	private fun MiniGameState.toSnapshotPhase(): MiniGamePhase = when (this) {
		MiniGameState.IDLE -> MiniGamePhase.WAITING_TO_START
		MiniGameState.RUNNING -> MiniGamePhase.ACTIVE
		MiniGameState.WARNING -> MiniGamePhase.WARNING
		MiniGameState.FINISHED -> MiniGamePhase.COMPLETED
	}

	override fun onSessionEnd() {
		_state = MiniGameState.FINISHED
		emitCompletionFeedback()
	}

	override fun calculatePoints(): Int {
		if (zoneSecondsTotal <= 0.0) return 0
		val basePoints = 15
		val timeBonus = min(zoneSecondsTotal / 60.0, MAX_TIME_BONUS_MINUTES) * 3
		return basePoints + timeBonus.toInt()
	}

	private fun updateZoneFeedback(inZone: Boolean, clockMs: Long) {
		if (inZone == feedbackZoneState) {
			pendingZoneState = null
			pendingZoneSinceMs = null
			return
		}
		if (pendingZoneState != inZone) {
			pendingZoneState = inZone
			pendingZoneSinceMs = clockMs
			return
		}
		if (clockMs - requireNotNull(pendingZoneSinceMs) < ZONE_TRANSITION_DEBOUNCE_MS) return

		feedbackZoneState = inZone
		pendingZoneState = null
		pendingZoneSinceMs = null
		if (lastZoneFeedbackMs == null || clockMs - lastZoneFeedbackMs!! >= ZONE_FEEDBACK_SUPPRESSION_MS) {
			emitFeedback(
				if (inZone) MiniGameFeedbackCue.ZenZoneEntered else MiniGameFeedbackCue.ZenZoneExited,
			)
			lastZoneFeedbackMs = clockMs
		}
	}

	private fun calibrationProgress(): Double {
		if (!isCalibrating) return 1.0
		val start = calibrationStartClockMs ?: return 0.0
		val now = if (hasRuntimeElapsedTime) activeElapsedTimeMs else lastClockMs ?: start
		return ((now - start).toDouble() / CALIBRATION_DURATION_MS).coerceIn(0.0, 1.0)
	}

	private fun targetPaceZone(): ZenPaceZone =
		ZenPaceZone(
			minimumMetersPerSecond = (targetSpeedMps - zoneHalfWidthMps()).coerceAtLeast(0.0),
			maximumMetersPerSecond = targetSpeedMps + zoneHalfWidthMps(),
		)

	private fun zoneHalfWidthMps(): Double = when (configuration.difficulty) {
		MiniGameDifficulty.EASY -> EASY_ZONE_HALF_WIDTH_MPS
		MiniGameDifficulty.NORMAL -> NORMAL_ZONE_HALF_WIDTH_MPS
		MiniGameDifficulty.HARD -> HARD_ZONE_HALF_WIDTH_MPS
	}

	private fun effectiveClock(timestampMs: Long): Long =
		if (hasRuntimeElapsedTime) activeElapsedTimeMs else timestampMs

	private fun emitCompletionFeedback() {
		if (completionFeedbackEmitted) return
		completionFeedbackEmitted = true
		val outcome = when {
			personalBestBeforeRun == null -> MiniGameCompletionOutcome.FirstRun
			score > personalBestBeforeRun ->
				MiniGameCompletionOutcome.PersonalBest(score - personalBestBeforeRun)
			score == personalBestBeforeRun -> MiniGameCompletionOutcome.TiedBest
			else -> MiniGameCompletionOutcome.BelowBest(personalBestBeforeRun - score)
		}
		emitFeedback(MiniGameFeedbackCue.SessionCompleted(outcome))
	}

	companion object {
		private const val EMA_ALPHA = 0.15
		private const val EASY_ZONE_HALF_WIDTH_MPS = 0.25
		private const val NORMAL_ZONE_HALF_WIDTH_MPS = 0.139 // 0.5 km/h
		private const val HARD_ZONE_HALF_WIDTH_MPS = 0.08
		private const val PAUSE_THRESHOLD_MPS = 0.3
		private const val PAUSE_CONFIRMATION_MS = 10_000L
		private const val CALIBRATION_DURATION_MS = 120_000L // 2 minutes
		private const val MIN_CALIBRATION_SAMPLES = 5
		private const val MAX_ACCURACY_M = 30f
		private const val MAX_TIME_BONUS_MINUTES = 30.0
		private const val MILLIS_PER_SECOND = 1_000.0
		private const val ZONE_TRANSITION_DEBOUNCE_MS = 5_000L
		private const val ZONE_FEEDBACK_SUPPRESSION_MS = 30_000L
	}
}
