package com.adsamcik.tracker.shared.utils.module

import com.adsamcik.tracker.shared.base.data.TrackerSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Shared channel for per-cycle tracker session updates.
 * Replaces broadcast-based [TrackerUpdateReceiver] registration mechanism.
 *
 * - Emitted by TrackerListenerManager after dispatching to legacy listeners.
 * - Observed by consumers (e.g. GoalTracker) for live session data.
 *
 * Provided as a singleton by [AppGraphModule].
 */
class TrackerSessionChannel {
	private val _sessions = MutableSharedFlow<TrackerSession>(extraBufferCapacity = 1)

	/** Flow of per-cycle session updates. Replays the latest emission to new collectors. */
	val sessions: Flow<TrackerSession> = _sessions

	/** Emit a session update. Called per tracking cycle. */
	fun emit(session: TrackerSession) {
		_sessions.tryEmit(session)
	}
}
