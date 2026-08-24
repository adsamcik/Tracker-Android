package com.adsamcik.tracker.tracker.resilience

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.rotateActivityAutomationEpochInTransaction
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.tracker.source.coordinator.LifecycleActionStatus
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.source.coordinator.SessionMode
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Finalizes incomplete source authority that cannot be safely resumed by this process.
 *
 * Automatic sessions never survive a process exit. A manual session survives only when the
 * recovery descriptor still grants ACTIVE restart authority in this boot and names the same
 * logical session. In particular, elapsed-time leases and manual authority never cross boots.
 * Session, service-run, lifecycle-action, demand, and authorization changes share one Room
 * transaction; the recovery coordinator separately compare-and-clears the descriptor mirror.
 */
@Singleton
class PreviousExitSourceSessionFinalizer @Inject constructor(
	private val databaseProvider: Provider<AppDatabase>,
	private val clock: Clock,
	private val bootClockDomainProvider: BootClockDomainProvider,
) {
	suspend fun finalizeStaleSessions(
		factualCompletedAtMs: Long? = null,
		recoveryDescriptor: ActiveTrackingSessionDescriptor? = null,
	): PreviousExitSourceSessionFinalization {
		require(factualCompletedAtMs == null || factualCompletedAtMs >= 0L) {
			"factualCompletedAtMs must not be negative"
		}
		val database = databaseProvider.get()
		val recoveryAtMs = clock.currentTimeMillis()
		val elapsedRealtimeNanos = clock.elapsedRealtimeNanos()
		val bootId = bootClockDomainProvider.current()
		val recoveryCandidate = recoveryDescriptor
			?.takeIf { descriptor ->
				descriptor.isUserInitiated && descriptor.isRestartEligibleForBoot(bootId)
			}
		return database.withTransaction {
			val dao = database.sourceSessionDao()
			val recoveryRun = recoveryCandidate?.let { descriptor ->
				dao.serviceRun(descriptor.serviceRunId)
			}
			val stale = dao.incompleteSessions().filter { session ->
				val isRecoverableManual = recoveryCandidate != null &&
					session.sessionMode == SessionMode.MANUAL.name &&
					session.state == SessionLifecycleState.ACTIVE.name &&
					session.completedAtMs == null &&
					session.clockDomainId == bootId &&
					session.lifecycleBootId == bootId &&
					session.logicalTrackingId == recoveryCandidate.logicalTrackingId &&
					recoveryRun != null &&
					recoveryRun.logicalTrackingId == session.logicalTrackingId &&
					recoveryRun.serviceRunId == recoveryCandidate.serviceRunId &&
					recoveryRun.state == SessionLifecycleState.ACTIVE.name &&
					recoveryRun.completedAtMs == null &&
					recoveryRun.bootId == bootId
				!isRecoverableManual
			}
			val pendingAutomaticAction = database.activityAutomaticStartActionDao().current()
				?.status in PENDING_AUTOMATIC_ACTION_STATES
			if (stale.isNotEmpty() || pendingAutomaticAction) {
				database.rotateActivityAutomationEpochInTransaction(
					reason = COMPLETION_REASON,
					updatedAtMs = recoveryAtMs.coerceAtLeast(0L),
					bootClockDomainId = bootId,
					effectiveElapsedRealtimeNanos = elapsedRealtimeNanos,
				)
			}
			val broker = SourceBroker(database)
			stale.forEach { session ->
				val factualCutoffAtMs = factualCompletedAtMs?.let { factualAtMs ->
					clampCompletionWallTime(
						requestedAtMs = factualAtMs,
						sessionStartedAtMs = session.startedAtMs,
						recoveryAtMs = recoveryAtMs,
					)
				}
				val completedAtMs = factualCutoffAtMs ?: maxOf(recoveryAtMs, session.startedAtMs)
				val cutoffAtMs = session.cutoffAtMs ?: factualCutoffAtMs ?: session.startedAtMs
				val cutoffElapsedNanos = session.cutoffElapsedNanos ?: if (
					factualCompletedAtMs == null
				) {
					session.startedElapsedNanos
				} else {
					null
				}
				broker.retireSessionDemandsInTransaction(
					session.logicalTrackingId,
					bootId,
					elapsedRealtimeNanos,
					recoveryAtMs.coerceAtLeast(0L),
				)
				dao.lifecycleActions(session.logicalTrackingId)
					.filter { action -> action.status in NONTERMINAL_ACTION_STATES }
					.forEach { action ->
						check(
							dao.updateLifecycleAction(
								action.copy(
									status = LifecycleActionStatus.TERMINAL_FAILURE.name,
									acknowledgedAtMs = completedAtMs,
									acknowledgedElapsedRealtimeNanos =
										if (action.bootId == bootId) elapsedRealtimeNanos
										else action.acknowledgedElapsedRealtimeNanos,
									failureCode = COMPLETION_REASON,
									retryTrigger = null,
								),
							) == 1,
						) { "Previous-exit lifecycle action changed during finalization" }
					}
				dao.incompleteServiceRuns(session.logicalTrackingId).forEach { run ->
					check(
						dao.updateServiceRun(
							run.copy(
								state = SessionLifecycleState.FINALIZED.name,
								completedAtMs = completedAtMs.coerceAtLeast(run.startedAtMs),
								completionReason = COMPLETION_REASON,
								runtimeAcknowledgement = LifecycleActionStatus.TERMINAL_FAILURE.name,
								runtimeFailureCode = COMPLETION_REASON,
								runRevision = run.runRevision + 1L,
							),
						) == 1,
					) { "Previous-exit source service run changed during finalization" }
				}
				check(
					dao.updateSession(
						session.copy(
							state = SessionLifecycleState.FINALIZED.name,
							lifecycleRevision = session.lifecycleRevision + 1L,
							cutoffAtMs = cutoffAtMs,
							cutoffElapsedNanos = cutoffElapsedNanos,
							completedAtMs = completedAtMs,
							failureCode = COMPLETION_REASON,
						),
					) == 1,
				) { "Previous-exit logical session changed during finalization" }
			}
			PreviousExitSourceSessionFinalization(
				finalizedLogicalTrackingIds = stale.mapTo(linkedSetOf()) { it.logicalTrackingId },
				inspectedLogicalTrackingId = recoveryDescriptor?.logicalTrackingId,
				inspectedSessionExists = recoveryDescriptor?.logicalTrackingId?.let { logicalTrackingId ->
					dao.session(logicalTrackingId) != null
				},
			)
		}
	}

	private fun clampCompletionWallTime(
		requestedAtMs: Long,
		sessionStartedAtMs: Long,
		recoveryAtMs: Long,
	): Long {
		val upperBound = maxOf(sessionStartedAtMs, recoveryAtMs)
		return requestedAtMs.coerceIn(sessionStartedAtMs, upperBound)
	}

	companion object {
		const val COMPLETION_REASON = "PREVIOUS_PROCESS_INTERRUPTED"
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

data class PreviousExitSourceSessionFinalization(
	val finalizedLogicalTrackingIds: Set<String>,
	val inspectedLogicalTrackingId: String? = null,
	val inspectedSessionExists: Boolean? = null,
)
