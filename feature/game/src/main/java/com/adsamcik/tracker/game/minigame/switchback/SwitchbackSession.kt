package com.adsamcik.tracker.game.minigame.switchback

import com.adsamcik.tracker.game.minigame.MiniGameCompletionOutcome
import com.adsamcik.tracker.game.minigame.MiniGameDifficulty
import com.adsamcik.tracker.game.minigame.MiniGameFeedbackCue
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameSignal
import com.adsamcik.tracker.game.minigame.MiniGameSignalQuality
import com.adsamcik.tracker.game.minigame.MiniGameState
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.game.minigame.SwitchbackConfiguration
import com.adsamcik.tracker.game.minigame.SwitchbackVisualPayload
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

internal enum class SwitchbackTurnDirection {
	LEFT,
	RIGHT,
	;

	val opposite: SwitchbackTurnDirection
		get() = if (this == LEFT) RIGHT else LEFT
}

/**
 * Carves a privacy-safe trail from meaningful route turns.
 *
 * A leg must meet the difficulty-selected distance before its direction is
 * accepted. Every sufficiently sharp corner scores; alternating left/right
 * corners build a flow combo. Raw fixes and absolute headings remain private.
 */
internal class SwitchbackSession(
	private val configuration: SwitchbackConfiguration = SwitchbackConfiguration(),
	private val personalBestBeforeRun: Double? = null,
) : MiniGameSession(
	configuration = configuration,
	personalBestBeforeRun = personalBestBeforeRun,
) {
	private var routeAnchor: PrivateFix? = null
	private var previousLeg: PrivateVector? = null
	private var hasFirstLeg = false
	private var turnsCompleted = 0
	private var currentCombo = 0
	private var bestCombo = 0
	private var lastTurnDirection: SwitchbackTurnDirection? = null
	private val recentTurns = ArrayDeque<SwitchbackTurnDirection>()
	private var _state = MiniGameState.IDLE
	private var lastSignal = MiniGameSignal.UNKNOWN
	private var goalReached = false
	private var personalBestCrossed = false
	private var completionFeedbackEmitted = false

	override val state: MiniGameState get() = _state
	override val score: Double get() = turnsCompleted.toDouble()
	override val statusText: String
		get() = "↯ $turnsCompleted / ${configuration.goal.turns} turns"

	internal val visualPayload: SwitchbackVisualPayload
		get() = SwitchbackVisualPayload(
			turnsCompleted = turnsCompleted,
			targetTurns = configuration.goal.turns,
			currentCombo = currentCombo,
			bestCombo = bestCombo,
			expectedTurn = lastTurnDirection?.opposite,
			recentTurns = recentTurns.toList(),
			hasFirstLeg = hasFirstLeg,
			minimumLegMeters = minimumLegMeters(),
			goalReached = goalReached,
		)

	override val snapshot
		get() = buildSnapshot(
			phase = state.toSnapshotPhase(),
			signal = lastSignal,
			currentScore = score,
			visualPayload = visualPayload,
		).copy(
			goalProgress = MiniGameGoalProgress.Tracked(
				current = score,
				target = configuration.goal.turns.toDouble(),
			),
		)

	override fun onLocationUpdate(
		latitude: Double,
		longitude: Double,
		speedMps: Float,
		accuracyM: Float,
		timestampMs: Long,
	) {
		if (_state == MiniGameState.FINISHED) return
		if (!isUsableFix(latitude, longitude, accuracyM, timestampMs)) {
			lastSignal = MiniGameSignal(
				quality = MiniGameSignalQuality.POOR,
				ageMs = 0L,
				isStale = false,
			)
			return
		}
		if (!speedMps.isFinite() || speedMps < 0f) {
			lastSignal = MiniGameSignal(
				quality = MiniGameSignalQuality.POOR,
				ageMs = 0L,
				isStale = false,
			)
			return
		}
		lastSignal = MiniGameSignal(
			quality = MiniGameSignalQuality.GOOD,
			ageMs = 0L,
			isStale = false,
		)

		val current = PrivateFix(latitude, longitude, timestampMs)
		if (_state == MiniGameState.IDLE) {
			if (speedMps > MAX_ON_FOOT_SPEED_MPS) return
			_state = MiniGameState.RUNNING
			routeAnchor = current
			return
		}

		val anchor = routeAnchor ?: current.also { routeAnchor = it }
		if (timestampMs <= anchor.timestampMs) return
		if (speedMps > MAX_ON_FOOT_SPEED_MPS) {
			resetLegDetectionAt(current)
			return
		}

		val candidateLeg = vectorBetween(anchor, current)
		val distanceM = candidateLeg.length
		val inferredSpeedMps = distanceM / ((timestampMs - anchor.timestampMs) / MILLIS_PER_SECOND)
		if (inferredSpeedMps > MAX_ON_FOOT_SPEED_MPS) {
			resetLegDetectionAt(current)
			return
		}
		if (distanceM < minimumLegMeters()) return

		routeAnchor = current
		val priorLeg = previousLeg
		previousLeg = candidateLeg
		if (priorLeg == null) {
			hasFirstLeg = true
			return
		}

		val signedTurnDegrees = Math.toDegrees(
			atan2(
				priorLeg.eastM * candidateLeg.northM - priorLeg.northM * candidateLeg.eastM,
				priorLeg.eastM * candidateLeg.eastM + priorLeg.northM * candidateLeg.northM,
			),
		)
		if (abs(signedTurnDegrees) < minimumTurnDegrees()) return

		val direction = if (signedTurnDegrees > 0.0) {
			SwitchbackTurnDirection.LEFT
		} else {
			SwitchbackTurnDirection.RIGHT
		}
		recordTurn(direction)
	}

	override fun onSessionEnd() {
		_state = MiniGameState.FINISHED
		emitCompletionFeedback()
	}

	/**
	 * A fix-only or straight-line run earns nothing. At least one measured
	 * corner requires two full legs, making the participation reward resistant
	 * to stationary GPS drift and accidental session starts.
	 */
	override fun calculatePoints(): Int {
		if (turnsCompleted == 0) return 0
		val turnBonus = min(turnsCompleted, FULL_VALUE_TURN_CAP) * POINTS_PER_TURN
		val comboBonus = min(bestCombo, COMBO_BONUS_CAP) * POINTS_PER_COMBO_STEP
		return BASE_POINTS + turnBonus + comboBonus
	}

	private fun recordTurn(direction: SwitchbackTurnDirection) {
		val previousScore = score
		currentCombo = if (lastTurnDirection == null || direction != lastTurnDirection) {
			currentCombo + 1
		} else {
			1
		}
		bestCombo = maxOf(bestCombo, currentCombo)
		lastTurnDirection = direction
		turnsCompleted += 1
		if (recentTurns.size == MAX_RECENT_TURNS) recentTurns.removeFirst()
		recentTurns.addLast(direction)
		val completesGoal = turnsCompleted >= configuration.goal.turns
		if (!completesGoal) {
			emitFeedback(MiniGameFeedbackCue.SwitchbackTurnCarved)
		}

		if (
			!personalBestCrossed &&
			personalBestBeforeRun != null &&
			previousScore <= personalBestBeforeRun &&
			score > personalBestBeforeRun
		) {
			personalBestCrossed = true
			emitFeedback(MiniGameFeedbackCue.PersonalBestCrossed)
		}
		if (!goalReached && completesGoal) {
			goalReached = true
			_state = MiniGameState.FINISHED
			emitFeedback(MiniGameFeedbackCue.GoalReached)
		}
	}

	private fun resetLegDetectionAt(fix: PrivateFix) {
		routeAnchor = fix
		previousLeg = null
		hasFirstLeg = false
	}

	private fun isUsableFix(
		latitude: Double,
		longitude: Double,
		accuracyM: Float,
		timestampMs: Long,
	): Boolean =
		latitude.isFinite() &&
			longitude.isFinite() &&
			latitude in -90.0..90.0 &&
			longitude in -180.0..180.0 &&
			accuracyM.isFinite() &&
			accuracyM in 0f..MAX_ACCURACY_M &&
			timestampMs >= 0L

	private fun vectorBetween(
		from: PrivateFix,
		to: PrivateFix,
	): PrivateVector {
		val northM = Math.toRadians(to.latitude - from.latitude) * EARTH_RADIUS_M
		val averageLatitude = Math.toRadians((from.latitude + to.latitude) / 2.0)
		val eastM = Math.toRadians(to.longitude - from.longitude) *
			EARTH_RADIUS_M * cos(averageLatitude)
		return PrivateVector(eastM = eastM, northM = northM)
	}

	private fun minimumLegMeters(): Double = when (configuration.difficulty) {
		MiniGameDifficulty.EASY -> EASY_MINIMUM_LEG_M
		MiniGameDifficulty.NORMAL -> NORMAL_MINIMUM_LEG_M
		MiniGameDifficulty.HARD -> HARD_MINIMUM_LEG_M
	}

	private fun minimumTurnDegrees(): Double = when (configuration.difficulty) {
		MiniGameDifficulty.EASY -> EASY_MINIMUM_TURN_DEGREES
		MiniGameDifficulty.NORMAL -> NORMAL_MINIMUM_TURN_DEGREES
		MiniGameDifficulty.HARD -> HARD_MINIMUM_TURN_DEGREES
	}

	private fun MiniGameState.toSnapshotPhase(): MiniGamePhase = when (this) {
		MiniGameState.IDLE -> MiniGamePhase.WAITING_TO_START
		MiniGameState.RUNNING -> MiniGamePhase.ACTIVE
		MiniGameState.WARNING -> MiniGamePhase.WARNING
		MiniGameState.FINISHED -> MiniGamePhase.COMPLETED
	}

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

	private data class PrivateFix(
		val latitude: Double,
		val longitude: Double,
		val timestampMs: Long,
	)

	private data class PrivateVector(
		val eastM: Double,
		val northM: Double,
	) {
		val length: Double get() = sqrt(eastM * eastM + northM * northM)
	}

	private companion object {
		const val EARTH_RADIUS_M = 6_371_000.0
		const val MILLIS_PER_SECOND = 1_000.0
		const val MAX_ACCURACY_M = 25f
		const val MAX_ON_FOOT_SPEED_MPS = 8f
		const val EASY_MINIMUM_LEG_M = 24.0
		const val NORMAL_MINIMUM_LEG_M = 32.0
		const val HARD_MINIMUM_LEG_M = 40.0
		const val EASY_MINIMUM_TURN_DEGREES = 55.0
		const val NORMAL_MINIMUM_TURN_DEGREES = 70.0
		const val HARD_MINIMUM_TURN_DEGREES = 85.0
		const val MAX_RECENT_TURNS = 8
		const val BASE_POINTS = 20
		const val POINTS_PER_TURN = 8
		const val POINTS_PER_COMBO_STEP = 4
		const val FULL_VALUE_TURN_CAP = 20
		const val COMBO_BONUS_CAP = 8
	}
}
