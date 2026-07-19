package com.adsamcik.tracker.game.minigame.outrun

import com.adsamcik.tracker.game.minigame.MiniGameConfigurations
import com.adsamcik.tracker.game.minigame.MiniGameCompletionOutcome
import com.adsamcik.tracker.game.minigame.MiniGameDifficulty
import com.adsamcik.tracker.game.minigame.MiniGameFeedbackCue
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameSignal
import com.adsamcik.tracker.game.minigame.MiniGameSignalQuality
import com.adsamcik.tracker.game.minigame.MiniGameState
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.game.minigame.OutrunConfiguration
import kotlin.math.max
import kotlin.math.min

/**
 * Outrun session: ghost moves at [ghostPaceMps] m/s.
 * Player distance tracked cumulatively from location updates.
 * Score = max distance player was ahead of ghost.
 */
internal class OutrunSession(
	private val configuration: OutrunConfiguration = MiniGameConfigurations.DEFAULT_OUTRUN,
	private val personalBestBeforeRun: Double? = null,
	private val ghostPaceMps: Double = ghostPaceFor(configuration.difficulty),
) : MiniGameSession(configuration, personalBestBeforeRun) {

	private var lastClockMs: Long? = null
	private var playerDistanceM: Double = 0.0
	private var ghostDistanceM: Double = 0.0
	private var maxAheadM: Double = 0.0
	private var lastLat: Double = 0.0
	private var lastLon: Double = 0.0
	private var hasFirstFix: Boolean = false
	private var raceStarted: Boolean = false
	private var _state: MiniGameState = MiniGameState.IDLE
	private var lastSignal: MiniGameSignal = MiniGameSignal.UNKNOWN
	private var activeElapsedTimeMs: Long = 0L
	private var hasRuntimeElapsedTime: Boolean = false
	private var goalReached: Boolean = false
	private var personalBestCrossed: Boolean = false
	private var completionFeedbackEmitted: Boolean = false
	private var lastEvaluatedGapM: Double? = null

	override val state: MiniGameState get() = _state
	override val score: Double get() = maxAheadM
	override val snapshot
		get() = buildSnapshot(
			phase = state.toSnapshotPhase(),
			signal = lastSignal,
			currentScore = score,
			visualPayload = MiniGameVisualPayload.Outrun(
				currentGapMeters = playerDistanceM - ghostDistanceM,
				warningThresholdMeters = WARNING_THRESHOLD_M,
				bestThisRunMeters = maxAheadM,
				personalBestMeters = personalBestBeforeRun,
				hasRaceStarted = raceStarted,
			),
		)

	override val statusText: String
		get() {
			val ahead = (playerDistanceM - ghostDistanceM).toInt()
			return if (ahead >= 0) "👻 +${ahead}m" else "👻 ${ahead}m"
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

		if (!hasFirstFix) {
			hasFirstFix = true
			lastClockMs = clockMs
			lastLat = latitude
			lastLon = longitude
			_state = MiniGameState.RUNNING
			return
		}

		if (speedMps >= VEHICLE_SPEED_THRESHOLD_MPS) {
			lastClockMs = clockMs
			lastLat = latitude
			lastLon = longitude
			if (raceStarted) updateStateAndFeedback()
			return
		}

		// Player distance (haversine approximation for short distances)
		val dist = approximateDistance(lastLat, lastLon, latitude, longitude)
		if (dist > MIN_MOVE_M) {
			playerDistanceM += dist
			lastLat = latitude
			lastLon = longitude
			if (!raceStarted) {
				raceStarted = true
				lastClockMs = clockMs
				updateStateAndFeedback()
				return
			}
		} else if (!raceStarted) {
			lastClockMs = clockMs
			return
		}

		// Ghost distance uses service-owned active time when it is available.
		val dtSeconds = ((clockMs - requireNotNull(lastClockMs)).coerceAtLeast(0L)) / 1000.0
		ghostDistanceM += ghostPaceMps * dtSeconds
		lastClockMs = clockMs

		updateStateAndFeedback()
	}

	private fun updateStateAndFeedback() {
		val ahead = playerDistanceM - ghostDistanceM
		val isLosingGround = lastEvaluatedGapM?.let { previousGap ->
			ahead < previousGap - GAP_TREND_EPSILON_M
		} == true
		val previousBest = maxAheadM
		maxAheadM = max(maxAheadM, ahead)
		if (
			!personalBestCrossed &&
			personalBestBeforeRun != null &&
			previousBest <= personalBestBeforeRun &&
			maxAheadM > personalBestBeforeRun
		) {
			personalBestCrossed = true
			emitFeedback(MiniGameFeedbackCue.PersonalBestCrossed)
		}
		if (!goalReached && maxAheadM >= configuration.goal.meters) {
			goalReached = true
			_state = MiniGameState.FINISHED
			emitFeedback(MiniGameFeedbackCue.GoalReached)
			return
		}

		val previousState = _state
		_state = when {
			ahead < -CAUGHT_THRESHOLD_M -> MiniGameState.FINISHED
			ahead < WARNING_THRESHOLD_M && isLosingGround -> MiniGameState.WARNING
			else -> MiniGameState.RUNNING
		}
		lastEvaluatedGapM = ahead
		if (_state == MiniGameState.FINISHED && previousState != MiniGameState.FINISHED) {
			emitCompletionFeedback()
		} else if (_state == MiniGameState.WARNING && previousState == MiniGameState.RUNNING) {
			emitFeedback(MiniGameFeedbackCue.OutrunDanger)
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
		if (!raceStarted) return 0
		val basePoints = 30
		val distanceBonus = min(maxAheadM / 10.0, 170.0).toInt()
		return basePoints + distanceBonus
	}

	private fun effectiveClock(timestampMs: Long): Long =
		if (hasRuntimeElapsedTime) activeElapsedTimeMs else timestampMs

	private fun emitCompletionFeedback() {
		if (completionFeedbackEmitted) return
		completionFeedbackEmitted = true
		val outcome = when {
			personalBestBeforeRun == null -> MiniGameCompletionOutcome.FirstRun
			maxAheadM > personalBestBeforeRun ->
				MiniGameCompletionOutcome.PersonalBest(maxAheadM - personalBestBeforeRun)
			maxAheadM == personalBestBeforeRun -> MiniGameCompletionOutcome.TiedBest
			else -> MiniGameCompletionOutcome.BelowBest(personalBestBeforeRun - maxAheadM)
		}
		emitFeedback(MiniGameFeedbackCue.SessionCompleted(outcome))
	}

	private fun approximateDistance(
		lat1: Double, lon1: Double, lat2: Double, lon2: Double,
	): Double {
		val dLat = Math.toRadians(lat2 - lat1)
		val dLon = Math.toRadians(lon2 - lon1)
		val avgLat = Math.toRadians((lat1 + lat2) / 2.0)
		val x = dLon * kotlin.math.cos(avgLat)
		return kotlin.math.sqrt(dLat * dLat + x * x) * EARTH_RADIUS_M
	}

	companion object {
		private const val DEFAULT_GHOST_PACE_MPS = 1.25 // ~4.5 km/h
		private const val MAX_ACCURACY_M = 30f
		private const val MIN_MOVE_M = 2.0
		private const val VEHICLE_SPEED_THRESHOLD_MPS = 8f // ~29 km/h
		private const val WARNING_THRESHOLD_M = 20.0
		private const val CAUGHT_THRESHOLD_M = 0.0
		private const val GAP_TREND_EPSILON_M = 0.25
		private const val EARTH_RADIUS_M = 6_371_000.0

		private fun ghostPaceFor(difficulty: MiniGameDifficulty): Double = when (difficulty) {
			MiniGameDifficulty.EASY -> 1.0
			MiniGameDifficulty.NORMAL -> DEFAULT_GHOST_PACE_MPS
			MiniGameDifficulty.HARD -> 1.5
		}
	}
}
