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
abstract class MiniGameSession {
	/** Current game state. */
	abstract val state: MiniGameState

	/** Current score value. */
	abstract val score: Double

	/** Human-readable status for floating UI chip. */
	abstract val statusText: String

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
enum class MiniGameState {
	IDLE,
	RUNNING,
	WARNING,
	FINISHED,
}
