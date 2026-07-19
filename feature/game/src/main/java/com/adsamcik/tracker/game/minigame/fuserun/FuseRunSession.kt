package com.adsamcik.tracker.game.minigame.fuserun

import com.adsamcik.tracker.game.minigame.MiniGameCompletionOutcome
import com.adsamcik.tracker.game.minigame.FuseRunConfiguration
import com.adsamcik.tracker.game.minigame.FuseRunVisualPayload
import com.adsamcik.tracker.game.minigame.MiniGameDifficulty
import com.adsamcik.tracker.game.minigame.MiniGameFeedbackCue
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameSignal
import com.adsamcik.tracker.game.minigame.MiniGameSignalQuality
import com.adsamcik.tracker.game.minigame.MiniGameState
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fuse Run plants a virtual charge at the player's latest accepted position.
 * The player must escape an escalating blast radius before its fuse expires.
 *
 * The participation guard requires at least one real defusal. A successful
 * defusal itself proves meaningful on-foot movement:
 * even the easiest blast radius requires 18 metres of session-relative
 * displacement. Merely acquiring a fix, waiting out fuses, or ending immediately
 * therefore cannot award points.
 *
 * Integration should add game-specific `FuseCritical` and `FuseDefused` feedback
 * cues. Borrowing Outrun/Territory cues would produce incorrect TalkBack copy, so
 * this standalone engine deliberately waits for the shared sealed types to be
 * extended in the coordinated integration pass.
 */
internal class FuseRunSession(
	private val configuration: FuseRunConfiguration = FuseRunConfiguration(),
	private val personalBestBeforeRun: Double? = null,
) : MiniGameSession(
	configuration = configuration,
	personalBestBeforeRun = personalBestBeforeRun,
) {
	private var _state: MiniGameState = MiniGameState.IDLE
	private var lastSignal: MiniGameSignal = MiniGameSignal.UNKNOWN
	private var serviceElapsedTimeMs: Long = 0L
	private var hasRuntimeElapsedTime: Boolean = false
	private var hasFirstFix: Boolean = false
	private var lastLatitude: Double = 0.0
	private var lastLongitude: Double = 0.0
	private var chargeLatitude: Double = 0.0
	private var chargeLongitude: Double = 0.0
	private var roundStartClockMs: Long = 0L
	private var roundDurationMs: Long = roundDurationFor(defusedCharges = 0)
	private var currentDistanceFromChargeM: Double = 0.0
	private var roundAcceptedDistanceM: Double = 0.0
	private var defusedCharges: Int = 0
	private var expiredCharges: Int = 0
	private var currentStreak: Int = 0
	private var bestStreak: Int = 0
	private var timeBonusTicks: Int = 0
	private var personalBestCrossed: Boolean = false
	private var goalReached: Boolean = false
	private var completionFeedbackEmitted: Boolean = false

	override val state: MiniGameState get() = _state
	override val score: Double get() = defusedCharges.toDouble()

	internal val visualPayload: FuseRunVisualPayload
		get() = FuseRunVisualPayload(
			hasStarted = hasFirstFix,
			defusedCharges = defusedCharges,
			targetCharges = configuration.goal.charges,
			roundNumber = max(
				1,
				defusedCharges + expiredCharges + if (goalReached) 0 else 1,
			),
			distanceFromChargeMeters = currentDistanceFromChargeM,
			requiredDistanceMeters = requiredDistanceFor(
				if (goalReached) defusedCharges - 1 else defusedCharges,
			),
			remainingTimeMs = remainingTimeMs(),
			roundDurationMs = roundDurationMs,
			currentStreak = currentStreak,
			bestStreak = bestStreak,
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
				target = configuration.goal.charges.toDouble(),
			),
		)

	override val statusText: String
		get() = when {
			goalReached -> "Fuse Run complete"
			!hasFirstFix -> "Waiting for GPS"
			_state == MiniGameState.WARNING -> "Fuse critical"
			else -> "$defusedCharges/${configuration.goal.charges} charges defused"
		}

	override fun onActiveElapsedTimeChanged(elapsedActiveTimeMs: Long) {
		super.onActiveElapsedTimeChanged(elapsedActiveTimeMs)
		val isFirstRuntimeClock = !hasRuntimeElapsedTime
		serviceElapsedTimeMs = elapsedActiveTimeMs
		hasRuntimeElapsedTime = true
		if (isFirstRuntimeClock && hasFirstFix && _state != MiniGameState.FINISHED) {
			roundStartClockMs = elapsedActiveTimeMs
		}
		evaluateFuse(elapsedActiveTimeMs)
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
		val clockMs = effectiveClock(timestampMs)

		if (speedMps > MAX_ON_FOOT_SPEED_MPS) {
			if (hasFirstFix) {
				rearmAt(latitude, longitude, clockMs)
			}
			return
		}

		if (!hasFirstFix) {
			hasFirstFix = true
			lastLatitude = latitude
			lastLongitude = longitude
			rearmAt(latitude, longitude, clockMs)
			return
		}

		evaluateFuse(clockMs)
		val segmentDistance = approximateDistance(
			lastLatitude,
			lastLongitude,
			latitude,
			longitude,
		)
		if (segmentDistance < MIN_MOVE_M) return

		lastLatitude = latitude
		lastLongitude = longitude
		roundAcceptedDistanceM += segmentDistance
		currentDistanceFromChargeM = approximateDistance(
			chargeLatitude,
			chargeLongitude,
			latitude,
			longitude,
		)

		val requiredDistance = requiredDistanceFor(defusedCharges)
		if (
			currentDistanceFromChargeM >= requiredDistance &&
			roundAcceptedDistanceM >= requiredDistance * MIN_PATH_TO_RADIUS_RATIO
		) {
			defuseCharge(latitude, longitude, clockMs)
		}
	}

	override fun onSessionEnd() {
		_state = MiniGameState.FINISHED
		emitCompletionFeedback()
	}

	override fun calculatePoints(): Int {
		if (defusedCharges == 0) return 0
		val defusePoints = min(defusedCharges * POINTS_PER_DEFUSAL, MAX_DEFUSE_POINTS)
		val streakPoints = min(bestStreak * POINTS_PER_STREAK, MAX_STREAK_POINTS)
		val timePoints = min(timeBonusTicks * POINTS_PER_TIME_TICK, MAX_TIME_POINTS)
		return min(BASE_POINTS + defusePoints + streakPoints + timePoints, MAX_TOTAL_POINTS)
	}

	private fun defuseCharge(
		latitude: Double,
		longitude: Double,
		clockMs: Long,
	) {
		val previousScore = score
		timeBonusTicks += (remainingTimeMs(clockMs) / TIME_BONUS_TICK_MS).toInt()
		defusedCharges += 1
		currentStreak += 1
		bestStreak = max(bestStreak, currentStreak)
		val completesGoal = defusedCharges >= configuration.goal.charges
		if (!completesGoal) {
			emitFeedback(MiniGameFeedbackCue.FuseDefused)
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

		if (completesGoal) {
			goalReached = true
			_state = MiniGameState.FINISHED
			currentDistanceFromChargeM = requiredDistanceFor(defusedCharges - 1)
			emitFeedback(MiniGameFeedbackCue.GoalReached)
			return
		}

		rearmAt(latitude, longitude, clockMs)
	}

	private fun evaluateFuse(clockMs: Long) {
		if (!hasFirstFix || _state == MiniGameState.FINISHED) return
		val remainingTimeMs = remainingTimeMs(clockMs)
		if (remainingTimeMs == 0L) {
			expiredCharges += 1
			currentStreak = 0
			rearmAt(lastLatitude, lastLongitude, clockMs)
			return
		}
		val wasWarning = _state == MiniGameState.WARNING
		_state = if (remainingTimeMs <= WARNING_TIME_MS) {
			MiniGameState.WARNING
		} else {
			MiniGameState.RUNNING
		}
		if (!wasWarning && _state == MiniGameState.WARNING) {
			emitFeedback(MiniGameFeedbackCue.FuseCritical)
		}
	}

	private fun rearmAt(
		latitude: Double,
		longitude: Double,
		clockMs: Long,
	) {
		lastLatitude = latitude
		lastLongitude = longitude
		chargeLatitude = latitude
		chargeLongitude = longitude
		roundStartClockMs = clockMs
		roundDurationMs = roundDurationFor(defusedCharges)
		currentDistanceFromChargeM = 0.0
		roundAcceptedDistanceM = 0.0
		_state = MiniGameState.RUNNING
	}

	private fun remainingTimeMs(clockMs: Long = currentClockMs()): Long {
		if (!hasFirstFix) return roundDurationMs
		return (roundDurationMs - (clockMs - roundStartClockMs).coerceAtLeast(0L))
			.coerceIn(0L, roundDurationMs)
	}

	private fun currentClockMs(): Long =
		if (hasRuntimeElapsedTime) serviceElapsedTimeMs else roundStartClockMs

	private fun effectiveClock(timestampMs: Long): Long =
		if (hasRuntimeElapsedTime) serviceElapsedTimeMs else timestampMs

	private fun requiredDistanceFor(defusedCharges: Int): Double {
		val (base, increase, cap) = when (configuration.difficulty) {
			MiniGameDifficulty.EASY -> Triple(18.0, 1.5, 28.0)
			MiniGameDifficulty.NORMAL -> Triple(22.0, 2.0, 34.0)
			MiniGameDifficulty.HARD -> Triple(26.0, 2.5, 40.0)
		}
		return min(base + defusedCharges * increase, cap)
	}

	private fun roundDurationFor(defusedCharges: Int): Long {
		val (base, reduction, floor) = when (configuration.difficulty) {
			MiniGameDifficulty.EASY -> Triple(35_000L, 1_000L, 28_000L)
			MiniGameDifficulty.NORMAL -> Triple(30_000L, 1_000L, 22_000L)
			MiniGameDifficulty.HARD -> Triple(25_000L, 1_000L, 18_000L)
		}
		return max(base - defusedCharges * reduction, floor)
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

	private fun approximateDistance(
		lat1: Double,
		lon1: Double,
		lat2: Double,
		lon2: Double,
	): Double {
		val dLat = Math.toRadians(lat2 - lat1)
		val dLon = Math.toRadians(lon2 - lon1)
		val averageLatitude = Math.toRadians((lat1 + lat2) / 2.0)
		val east = dLon * cos(averageLatitude)
		return sqrt(dLat * dLat + east * east) * EARTH_RADIUS_M
	}

	private companion object {
		const val EARTH_RADIUS_M: Double = 6_371_000.0
		const val MAX_ACCURACY_M: Float = 30f
		const val MAX_ON_FOOT_SPEED_MPS: Float = 8f
		const val MIN_MOVE_M: Double = 2.0
		const val MIN_PATH_TO_RADIUS_RATIO: Double = 0.8
		const val WARNING_TIME_MS: Long = 8_000L
		const val TIME_BONUS_TICK_MS: Long = 10_000L
		const val BASE_POINTS: Int = 15
		const val POINTS_PER_DEFUSAL: Int = 12
		const val MAX_DEFUSE_POINTS: Int = 96
		const val POINTS_PER_STREAK: Int = 4
		const val MAX_STREAK_POINTS: Int = 32
		const val POINTS_PER_TIME_TICK: Int = 2
		const val MAX_TIME_POINTS: Int = 50
		const val MAX_TOTAL_POINTS: Int = 180
	}
}
