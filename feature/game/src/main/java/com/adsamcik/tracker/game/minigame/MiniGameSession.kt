package com.adsamcik.tracker.game.minigame

/**
 * Abstract base for an active mini-game session.
 *
 * Lifecycle contract:
 *  - Created by [MiniGame.createSession] when the player taps Start.
 *  - [onLocationUpdate] is invoked between Start and Stop with every
 *    GPS sample emitted by the active location source. Calls are serialized
 *    on a single coroutine, so implementations do not need to be thread-safe.
 *  - [onSessionEnd] is called exactly once when the player taps Stop (or the
 *    session is otherwise finalized). It MAY be called before any location
 *    sample has been delivered — implementations must handle the "no samples"
 *    case gracefully.
 *  - [calculatePoints] is read once after [onSessionEnd] to determine the
 *    points awarded for this run. It MUST be deterministic for a given
 *    session state.
 *
 * Score & points are conceptually distinct:
 *  - [score] is the in-game metric (meters ahead, cells claimed, seconds in zone).
 *  - [calculatePoints] converts the score into the global gamification ledger
 *    (the same "points" surfaced by the dashboard and player profile). The
 *    historical name was "XP" — that nomenclature is deprecated; mini-games
 *    credit the points ledger only, not a separate XP currency.
 *
 * No raw GPS samples are persisted by this class. Only the final score row
 * and points credit reach the database.
 */
internal abstract class MiniGameSession(
	private val configuration: MiniGameConfiguration? = null,
	private val personalBestBeforeRun: Double? = null,
) {
	private var activeElapsedTimeMs: Long = 0L
	private var nextFeedbackEventId: Long = 1L
	private var latestFeedback: MiniGameFeedback? = null

	/** Current game state. */
	abstract val state: MiniGameState

	/** Current score value. */
	abstract val score: Double

	/**
	 * Privacy-safe state consumed by the service/controller/UI pipeline.
	 *
	 * Existing engines receive a common-only compatibility snapshot until they
	 * override this property with their game-specific visual payload.
	 */
	open val snapshot: MiniGameSnapshot
		get() = buildSnapshot(
			phase = state.toSnapshotPhase(),
			signal = MiniGameSignal.UNKNOWN,
			currentScore = score,
			visualPayload = MiniGameVisualPayload.Pending,
		)

	/** Human-readable status for floating UI chip. */
	abstract val statusText: String

	/**
	 * Supplies service-owned active time. Values are monotonic and exclude
	 * explicit pauses; accepting them does not alter legacy score calculation.
	 */
	open fun onActiveElapsedTimeChanged(elapsedActiveTimeMs: Long) {
		require(elapsedActiveTimeMs >= this.activeElapsedTimeMs) {
			"Active elapsed time cannot move backwards"
		}
		this.activeElapsedTimeMs = elapsedActiveTimeMs
	}

	/**
	 * Publish a one-shot cue with a session-local, strictly increasing id.
	 */
	protected fun emitFeedback(cue: MiniGameFeedbackCue) {
		check(nextFeedbackEventId < Long.MAX_VALUE) { "Feedback event id exhausted" }
		latestFeedback = MiniGameFeedback(
			eventId = MiniGameFeedbackEventId(nextFeedbackEventId++),
			cue = cue,
		)
	}

	/**
	 * Builds the common portion of a typed snapshot for engine overrides.
	 */
	protected fun buildSnapshot(
		phase: MiniGamePhase,
		signal: MiniGameSignal,
		currentScore: Double,
		visualPayload: MiniGameVisualPayload,
	): MiniGameSnapshot {
		require(currentScore.isFinite() && currentScore >= 0.0) {
			"Snapshot score must be finite and non-negative"
		}
		return MiniGameSnapshot(
			phase = phase,
			signal = signal,
			elapsedActiveTimeMs = activeElapsedTimeMs,
			goalProgress = configuration?.let {
				MiniGameGoalProgress.Tracked(
					current = currentScore,
					target = it.goal.scoreTarget,
				)
			} ?: MiniGameGoalProgress.NotConfigured,
			personalBest = MiniGamePersonalBest.compare(
				currentScore = currentScore,
				scoreBeforeRun = personalBestBeforeRun,
			),
			latestFeedback = latestFeedback,
			visualPayload = visualPayload,
		)
	}

	/**
	 * Invoked for every GPS sample observed between Start and Stop.
	 *
	 * Implementations must be tolerant of bursts (samples may arrive faster
	 * than 1 Hz) and gaps (the location source may pause briefly during
	 * permission prompts or activity transitions).
	 *
	 * @param latitude current latitude in decimal degrees
	 * @param longitude current longitude in decimal degrees
	 * @param speedMps current speed in m/s (may be 0 if unavailable)
	 * @param accuracyM horizontal accuracy in meters
	 * @param timestampMs location timestamp in wall-clock millis
	 */
	abstract fun onLocationUpdate(
		latitude: Double,
		longitude: Double,
		speedMps: Float,
		accuracyM: Float,
		timestampMs: Long,
	)

	/**
	 * Called exactly once when the session ends, before [calculatePoints].
	 * Implementations should freeze internal state so subsequent reads of
	 * [score] and [calculatePoints] are stable.
	 */
	abstract fun onSessionEnd()

	/**
	 * Points awarded for this run, credited into the global points ledger.
	 *
	 * Called after [onSessionEnd]; must be deterministic for the frozen
	 * session state. Implementations should return 0 for no-data runs.
	 */
	abstract fun calculatePoints(): Int
}

/** Mini-game lifecycle states. */
internal enum class MiniGameState {
	IDLE,
	RUNNING,
	WARNING,
	FINISHED,
}

private fun MiniGameState.toSnapshotPhase(): MiniGamePhase = when (this) {
	MiniGameState.IDLE -> MiniGamePhase.WAITING_TO_START
	MiniGameState.RUNNING -> MiniGamePhase.ACTIVE
	MiniGameState.WARNING -> MiniGamePhase.WARNING
	MiniGameState.FINISHED -> MiniGamePhase.COMPLETED
}
