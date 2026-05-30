package com.adsamcik.tracker.game.ui.compose

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.minigame.MiniGame
import com.adsamcik.tracker.game.minigame.MiniGameRegistry
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameState
import com.adsamcik.tracker.game.minigame.location.MiniGameLocationSource
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Argument key used by Compose Navigation to populate the saved-state handle.
 */
internal const val MINIGAME_SESSION_GAME_ID_ARG: String = "gameId"

/**
 * UI state for the mini-game session screen.
 *
 *  - [Idle]:             ready to start, waiting for user CTA
 *  - [PermissionNeeded]: location permission missing — UI must request it
 *  - [Active]:           a session is running; receiving location samples
 *  - [Finished]:         user stopped (or session ended); score + points persisted
 */
internal sealed interface MiniGameUiState {
	data object Idle : MiniGameUiState
	data object PermissionNeeded : MiniGameUiState
	data class Active(
		val score: Double,
		val state: MiniGameState,
		val statusText: String,
		val elapsedMs: Long,
	) : MiniGameUiState
	data class Finished(
		val finalScore: Double,
		val pointsEarned: Int,
	) : MiniGameUiState
}

/**
 * Drives a single mini-game session.
 *
 * Lifecycle:
 *  1. UI calls [start] when the user taps the start CTA.
 *  2. VM checks location permission; emits [MiniGameUiState.PermissionNeeded] if missing.
 *  3. VM subscribes to [MiniGameLocationSource], forwards samples to the session,
 *     and emits [MiniGameUiState.Active] on every fix.
 *  4. UI calls [stop] (or [onCleared] cleans up) which:
 *     - cancels the location subscription
 *     - calls [MiniGameSession.onSessionEnd]
 *     - persists score + points to [MiniGameScoreDao]
 *     - credits points via [GameRepository.creditMiniGameXp]
 *     - emits [MiniGameUiState.Finished]
 *  5. UI may call [reset] to play again (creates a fresh [MiniGameSession]).
 *
 * Raw location samples are never persisted: only the final score row goes to disk.
 */
@HiltViewModel
internal class MiniGameSessionViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	application: Application,
	private val miniGameRegistry: MiniGameRegistry,
	private val scoreDao: MiniGameScoreDao,
	private val gameRepository: GameRepository,
	private val locationSource: MiniGameLocationSource,
	private val dispatchers: DispatchersProvider,
) : ViewModel() {

	private val app: Application = application

	/** Stable id of the game being played. Provided via nav arguments. */
	val gameId: String = checkNotNull(savedStateHandle.get<String>(MINIGAME_SESSION_GAME_ID_ARG)) {
		"MiniGameSessionViewModel requires '$MINIGAME_SESSION_GAME_ID_ARG' nav argument"
	}

	/** Resolved [MiniGame]; resolved eagerly so a bad id surfaces immediately. */
	val game: MiniGame = checkNotNull(miniGameRegistry.findById(gameId)) {
		"Unknown mini-game id: $gameId"
	}

	@Volatile
	private var session: MiniGameSession = game.createSession()

	@Volatile
	private var sessionStartedAtMs: Long = 0L
	private var collectionJob: Job? = null

	/**
	 * `0L` while the session is actively running; otherwise the timestamp at
	 * which the host activity stopped being visible. Used by [resume] to decide
	 * between re-subscribing to location updates and auto-finalising the session
	 * after [AUTO_END_AFTER_PAUSE_MS] of inactivity.
	 */
	@Volatile
	private var pausedAtMs: Long = 0L

	/**
	 * Accumulated milliseconds spent paused across the lifetime of the current
	 * session. Subtracted from wall-clock elapsed so the on-screen timer reflects
	 * actual play time, not real time.
	 */
	@Volatile
	private var totalPausedMs: Long = 0L

	/**
	 * Guards [stop]'s finalize path so concurrent callers (Stop button + lifecycle
	 * teardown + auto-end) cannot each persist their own score row. Cleared by
	 * [reset] so the next session can finalize once.
	 */
	private val hasFinalized = AtomicBoolean(false)

	/**
	 * Serialises the finalize body so the [hasFinalized] CAS and the DB write
	 * cannot interleave with [reset] flipping `session` underneath us.
	 */
	private val finalizeMutex = Mutex()

	private val _uiState = MutableStateFlow<MiniGameUiState>(MiniGameUiState.Idle)
	val uiState: StateFlow<MiniGameUiState> = _uiState.asStateFlow()

	/**
	 * Begin streaming location to the active session.
	 * Idempotent: calling while already running is a no-op.
	 */
	fun start() {
		if (collectionJob?.isActive == true) return
		if (_uiState.value is MiniGameUiState.Active) return

		if (!app.hasLocationPermission) {
			_uiState.value = MiniGameUiState.PermissionNeeded
			return
		}

		// Reset the one-shot finalize latch so this fresh session can persist
		// exactly once when the player taps Stop (or auto-ends).
		hasFinalized.set(false)
		// Reset pause bookkeeping for the fresh session so the elapsed clock
		// starts at zero and any stale paused-at timestamp is discarded.
		pausedAtMs = 0L
		totalPausedMs = 0L

		// Begin fresh elapsed clock.
		sessionStartedAtMs = Time.nowMillis
		// Emit an initial Active frame so the UI can render "00:00" while the
		// first fix is pending — otherwise the screen would look frozen on Idle.
		_uiState.value = MiniGameUiState.Active(
			score = session.score,
			state = session.state,
			statusText = session.statusText,
			elapsedMs = 0L,
		)

		startCollection()
	}

	/**
	 * Subscribe to the location source and forward each sample into the active
	 * session, emitting an updated [MiniGameUiState.Active] frame on every fix.
	 *
	 * Extracted so [start] and [resume] can share identical collection logic
	 * without re-emitting the initial Active frame from [start] (which would
	 * spuriously reset the on-screen score the moment the user backgrounds and
	 * returns to the app).
	 */
	private fun startCollection() {
		collectionJob = viewModelScope.launch {
			try {
				locationSource.samples(game.desiredLocationRequest()).collect { sample ->
					session.onLocationUpdate(
						latitude = sample.latitude,
						longitude = sample.longitude,
						speedMps = sample.speedMps,
						accuracyM = sample.accuracyM,
						timestampMs = sample.timestampMs,
					)
					_uiState.value = MiniGameUiState.Active(
						score = session.score,
						state = session.state,
						statusText = session.statusText,
						elapsedMs = currentElapsedMs(),
					)
				}
			} catch (cancellation: CancellationException) {
				throw cancellation
			} catch (security: SecurityException) {
				_uiState.value = MiniGameUiState.PermissionNeeded
			} catch (error: Throwable) {
				// Stay defensive — never let a downstream failure throw across module
				// boundaries; fall back to a clean Finished state with zero points.
				_uiState.value = MiniGameUiState.Finished(
					finalScore = session.score,
					pointsEarned = 0,
				)
				if (error !is RuntimeException && error !is IllegalStateException) {
					throw error
				}
			}
		}
	}

	/**
	 * Compute the player-facing elapsed time, subtracting any pause intervals
	 * (both already-accumulated and a currently in-flight pause) so the clock
	 * reflects actual play time rather than wall-clock time.
	 */
	private fun currentElapsedMs(): Long {
		val pausedAt = pausedAtMs
		val inFlightPauseMs = if (pausedAt > 0L) Time.nowMillis - pausedAt else 0L
		return (Time.nowMillis - sessionStartedAtMs - totalPausedMs - inFlightPauseMs)
			.coerceAtLeast(0L)
	}

	/**
	 * Suspend the active session: cancel the location subscription to release
	 * the FusedLocationProviderClient (and the radio) while the screen is not
	 * visible, and record the pause start so [resume] can either keep playing
	 * or auto-finalise after [AUTO_END_AFTER_PAUSE_MS]. The UI state stays
	 * [MiniGameUiState.Active] — the user has not stopped, just stepped away —
	 * so when they come back within the window they pick up where they left off.
	 *
	 * Safe to call from any UI lifecycle event; a no-op when the session is not
	 * currently running.
	 */
	fun pause() {
		if (_uiState.value !is MiniGameUiState.Active) return
		if (pausedAtMs > 0L) return
		pausedAtMs = Time.nowMillis
		collectionJob?.cancel()
		collectionJob = null
	}

	/**
	 * Reverse of [pause]. Two outcomes depending on how long the screen was
	 * hidden:
	 *  - within [AUTO_END_AFTER_PAUSE_MS]: re-subscribe to location updates and
	 *    add the pause interval to [totalPausedMs] so the elapsed timer is not
	 *    inflated.
	 *  - beyond that window: call [stop] to finalise the session automatically
	 *    so we do not silently bill the player for a five-hour idle session.
	 *
	 * A no-op when the session was not paused.
	 */
	fun resume() {
		val pausedAt = pausedAtMs
		if (pausedAt <= 0L) return
		val pauseDurationMs = Time.nowMillis - pausedAt
		pausedAtMs = 0L
		if (pauseDurationMs > AUTO_END_AFTER_PAUSE_MS) {
			stop()
			return
		}
		totalPausedMs += pauseDurationMs.coerceAtLeast(0L)
		if (_uiState.value is MiniGameUiState.Active && collectionJob?.isActive != true) {
			startCollection()
		}
	}

	/**
	 * Stop the session, persist score + points, and transition to [MiniGameUiState.Finished].
	 *
	 * Idempotent and thread-safe: a single-flight [AtomicBoolean] latch combined
	 * with [finalizeMutex] guarantees that even if the Stop button, lifecycle
	 * teardown, and the auto-end timer all fire within microseconds, exactly one
	 * `scoreDao.insert` + `creditMiniGameXp` pair is issued. Subsequent calls are
	 * silent no-ops. The location subscription is cancelled before any I/O so
	 * the screen stops updating immediately.
	 */
	fun stop() {
		if (_uiState.value is MiniGameUiState.Finished) return

		// Single-flight latch: only the FIRST stop() proceeds to persist.
		if (!hasFinalized.compareAndSet(false, true)) return

		// Snapshot the running session so we can cancel + persist atomically.
		val activeSession = session
		val startedAt = sessionStartedAtMs
		val wasActive = collectionJob?.isActive == true || _uiState.value is MiniGameUiState.Active

		collectionJob?.cancel()
		collectionJob = null
		// Drop any in-flight pause bookkeeping so a follow-up resume() cannot
		// accidentally re-subscribe to the location source on a finished session.
		pausedAtMs = 0L

		if (!wasActive) {
			// User stopped before any frame ever arrived — just go back to Idle UX
			// rather than recording a 0/0 row. Release the latch so the next
			// start() can finalize normally.
			hasFinalized.set(false)
			_uiState.value = MiniGameUiState.Idle
			return
		}

		viewModelScope.launch {
			finalizeMutex.withLock {
				activeSession.onSessionEnd()
				val finalScore = activeSession.score
				val points = activeSession.calculatePoints().coerceAtLeast(0)
				val finishedAt = Time.nowMillis.coerceAtLeast(startedAt)

				withContext(dispatchers.io) {
					scoreDao.insert(
						MiniGameScoreEntity(
							gameId = gameId,
							score = finalScore,
							xpAwarded = points,
							playedAt = finishedAt,
						),
					)
				}
				gameRepository.creditMiniGameXp(
					gameId = gameId,
					xp = points,
					earnedAtMs = finishedAt,
				)

				_uiState.value = MiniGameUiState.Finished(
					finalScore = finalScore,
					pointsEarned = points,
				)
			}
		}
	}

	/**
	 * Discard the finished session and return to Idle with a fresh session
	 * ready to start. Use this for the "Play again" button.
	 */
	fun reset() {
		collectionJob?.cancel()
		collectionJob = null
		session = game.createSession()
		sessionStartedAtMs = 0L
		pausedAtMs = 0L
		totalPausedMs = 0L
		hasFinalized.set(false)
		_uiState.value = MiniGameUiState.Idle
	}

	/**
	 * Called by the UI after the system permission prompt resolves so the VM
	 * can re-evaluate state and auto-start when the user granted access.
	 */
	fun onPermissionResult(granted: Boolean) {
		if (granted) {
			_uiState.value = MiniGameUiState.Idle
			start()
		} else {
			_uiState.value = MiniGameUiState.PermissionNeeded
		}
	}

	override fun onCleared() {
		// Guarantee no rogue location subscription survives the VM. If the user
		// already pressed Stop, this is a no-op; otherwise we still cancel —
		// but we do NOT silently write a partial score row, since the user did
		// not explicitly end the session.
		collectionJob?.cancel()
		collectionJob = null
		super.onCleared()
	}

	companion object {
		/**
		 * If a session is paused longer than this window, [resume] will auto-end
		 * the session instead of resuming. Prevents silent multi-hour idle
		 * sessions from being credited as a single long run when the user simply
		 * forgot the screen was open.
		 */
		const val AUTO_END_AFTER_PAUSE_MS: Long = 5L * 60L * 1000L
	}
}
