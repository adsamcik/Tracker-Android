package com.adsamcik.tracker.tracker.resilience

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.rotateActivityAutomationEpochInTransaction
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.tracker.source.coordinator.LifecycleActionStatus
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
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
	private val clock: Clock,
	private val bootClockDomainProvider: BootClockDomainProvider,
) {
	suspend fun finalize(completedAtMs: Long): ForceStopSourceSessionFinalization {
		require(completedAtMs >= 0L) { "completedAtMs must not be negative" }
		val database = databaseProvider.get()
		val elapsedRealtimeNanos = clock.elapsedRealtimeNanos()
		val bootId = bootClockDomainProvider.current()
		return database.withTransaction {
			val dao = database.sourceSessionDao()
			val sessions = dao.incompleteSessions()
			val pendingAutomaticAction = database.activityAutomaticStartActionDao().current()
				?.status in PENDING_AUTOMATIC_ACTION_STATES
			if (sessions.isNotEmpty() || pendingAutomaticAction) {
					database.rotateActivityAutomationEpochInTransaction(
						reason = FORCE_STOP_COMPLETION_REASON,
						updatedAtMs = completedAtMs,
						bootClockDomainId = bootId,
						effectiveElapsedRealtimeNanos = elapsedRealtimeNanos,
					)
			}
			if (sessions.isEmpty()) {
				return@withTransaction ForceStopSourceSessionFinalization.NO_ACTIVE_SESSION
			}
			val broker = SourceBroker(database)
			sessions.forEach { session ->
				val effectiveCompletedAtMs = completedAtMs.coerceAtLeast(session.startedAtMs)
				broker.retireSessionDemandsInTransaction(
					session.logicalTrackingId,
					bootId,
					elapsedRealtimeNanos,
					effectiveCompletedAtMs,
				)
				dao.lifecycleActions(session.logicalTrackingId)
					.filter { action -> action.status in NONTERMINAL_ACTION_STATES }
					.forEach { action ->
						check(
							dao.updateLifecycleAction(
								action.copy(
									status = LifecycleActionStatus.TERMINAL_FAILURE.name,
									acknowledgedAtMs = effectiveCompletedAtMs,
									acknowledgedElapsedRealtimeNanos =
										if (action.bootId == bootId) elapsedRealtimeNanos
										else action.acknowledgedElapsedRealtimeNanos,
									failureCode = FORCE_STOP_COMPLETION_REASON,
									retryTrigger = null,
								),
							) == 1,
						) { "Force-stop lifecycle action changed during finalization" }
					}

				dao.incompleteServiceRuns(session.logicalTrackingId).forEach { run ->
					check(
						dao.updateServiceRun(
							run.copy(
								state = SessionLifecycleState.FINALIZED.name,
								completedAtMs = effectiveCompletedAtMs.coerceAtLeast(run.startedAtMs),
								completionReason = FORCE_STOP_COMPLETION_REASON,
								runtimeAcknowledgement = "TERMINAL_FAILURE",
								runtimeFailureCode = FORCE_STOP_COMPLETION_REASON,
								runRevision = run.runRevision + 1L,
							),
						) == 1,
					) { "Force-stop source service run changed during finalization" }
				}
				check(
					dao.updateSession(
						session.copy(
							// A force-stop cannot prove source quiescence, projection drain, or
							// completeness. Finalize it as interrupted with an explicit failure code.
							state = SessionLifecycleState.FINALIZED.name,
							lifecycleRevision = session.lifecycleRevision + 1L,
							cutoffAtMs = session.cutoffAtMs ?: effectiveCompletedAtMs,
							// Historical process-exit evidence has no trustworthy elapsed timestamp.
							cutoffElapsedNanos = session.cutoffElapsedNanos,
							completedAtMs = effectiveCompletedAtMs,
							failureCode = FORCE_STOP_COMPLETION_REASON,
						),
					) == 1,
				) { "Force-stop logical tracking session changed during finalization" }
			}
			ForceStopSourceSessionFinalization.FINALIZED
		}
	}

	companion object {
		const val FORCE_STOP_COMPLETION_REASON = "FORCE_STOP_SUPPRESSED"
		private val NONTERMINAL_ACTION_STATES = setOf(
			LifecycleActionStatus.PENDING.name,
			LifecycleActionStatus.APPLYING.name,
			LifecycleActionStatus.TEMPORARILY_ILLEGAL.name,
		)
		private val PENDING_AUTOMATIC_ACTION_STATES = setOf(
			ActivityAutomaticStartActionEntity.STATUS_RESERVED,
			ActivityAutomaticStartActionEntity.STATUS_START_REQUESTED,
		)
	}
}

enum class ForceStopSourceSessionFinalization {
	FINALIZED,
	NO_ACTIVE_SESSION,
}
