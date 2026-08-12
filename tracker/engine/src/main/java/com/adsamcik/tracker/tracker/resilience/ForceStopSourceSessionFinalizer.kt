package com.adsamcik.tracker.tracker.resilience

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Makes the Room-owned source session terminal after an intentional process force-stop.
 *
 * Room is the canonical lifecycle store; the DataStore descriptor is only a recovery mirror.
 * Android's force-stop guarantee means the prior tracker service no longer owns the active row,
 * and app startup suppresses automatic recovery before this transaction runs.
 */
@Singleton
class ForceStopSourceSessionFinalizer @Inject constructor(
	private val databaseProvider: Provider<AppDatabase>,
) {
	suspend fun finalize(completedAtMs: Long): ForceStopSourceSessionFinalization {
		require(completedAtMs >= 0L) { "completedAtMs must not be negative" }
		val database = databaseProvider.get()
		return database.withTransaction {
			val dao = database.sourceSessionDao()
			val session = dao.activeSession()
				?: return@withTransaction ForceStopSourceSessionFinalization.NO_ACTIVE_SESSION

			dao.incompleteServiceRuns(session.logicalTrackingId).forEach { run ->
				check(
					dao.updateServiceRun(
						run.copy(
							state = SessionLifecycleState.CLOSED.name,
							completedAtMs = completedAtMs,
							completionReason = FORCE_STOP_COMPLETION_REASON,
						),
					) == 1,
				) { "Force-stop source service run changed during finalization" }
			}
			check(
				dao.updateSession(
					session.copy(
						// A force-stop cannot prove source quiescence, projection drain, or
						// completeness, so do not claim the normally finalized CLOSED state.
						state = SessionLifecycleState.FAILED.name,
						lifecycleRevision = session.lifecycleRevision + 1L,
						completedAtMs = completedAtMs,
						failureCode = FORCE_STOP_COMPLETION_REASON,
					),
				) == 1,
			) { "Force-stop logical tracking session changed during finalization" }
			ForceStopSourceSessionFinalization.FINALIZED
		}
	}

	companion object {
		const val FORCE_STOP_COMPLETION_REASON = "FORCE_STOP_SUPPRESSED"
	}
}

enum class ForceStopSourceSessionFinalization {
	FINALIZED,
	NO_ACTIVE_SESSION,
}
