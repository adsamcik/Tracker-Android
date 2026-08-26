package com.adsamcik.tracker.tracker.resilience

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.rotateActivityAutomationEpochInTransaction
import com.adsamcik.tracker.tracker.source.coordinator.LifecycleActionStatus
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Finalizes the one Room lifecycle explicitly named by a stop request.
 *
 * The exact-ID check prevents a delayed stop from closing a newer logical session. Provider
 * removal remains owned by the runtime adapters; this transaction retires their durable authority.
 */
@Singleton
class ExplicitStopSourceSessionFinalizer @Inject constructor(
	private val databaseProvider: Provider<AppDatabase>,
) {
	suspend fun finalize(
		logicalTrackingId: String,
		expectedServiceRunId: String,
		requestedAtMs: Long,
		requestedBootId: String?,
		requestedElapsedRealtimeNanos: Long?,
		reconciliationAtMs: Long,
		reconciliationElapsedRealtimeNanos: Long,
		reconciliationBootId: String,
		reason: String,
	): ExplicitStopSourceSessionFinalization {
		require(logicalTrackingId.isNotBlank())
		require(expectedServiceRunId.isNotBlank())
		require(requestedAtMs >= 0L)
		require((requestedBootId == null) == (requestedElapsedRealtimeNanos == null))
		require(requestedBootId == null || requestedBootId.isNotBlank())
		require(requestedElapsedRealtimeNanos == null || requestedElapsedRealtimeNanos >= 0L)
		require(reconciliationAtMs >= 0L)
		require(reconciliationElapsedRealtimeNanos >= 0L)
		require(reconciliationBootId.isNotBlank())
		require(reason.isNotBlank())
		val database = databaseProvider.get()
		return database.withTransaction {
			val dao = database.sourceSessionDao()
			val active = dao.activeSession()
			val requested = dao.session(logicalTrackingId)
			if (requested == null) {
				return@withTransaction ExplicitStopSourceSessionFinalization.NO_ROOM_SESSION
			}
			if (active?.logicalTrackingId != logicalTrackingId) {
				return@withTransaction if (requested.state in TERMINAL_SESSION_STATES) {
					ExplicitStopSourceSessionFinalization.ALREADY_FINALIZED
				} else {
					ExplicitStopSourceSessionFinalization.SESSION_MISMATCH
				}
			}
			val currentRun = dao.serviceRunAuthorityForFinalization(requested).exactSoleCurrentRun
				?: return@withTransaction ExplicitStopSourceSessionFinalization.SESSION_MISMATCH
			if (currentRun.serviceRunId != expectedServiceRunId) {
				return@withTransaction ExplicitStopSourceSessionFinalization.SESSION_MISMATCH
			}
			database.rotateActivityAutomationEpochInTransaction(
				reason = "EXPLICIT_TRACKING_INTERRUPTION",
				updatedAtMs = reconciliationAtMs,
				bootClockDomainId = reconciliationBootId,
				effectiveElapsedRealtimeNanos = reconciliationElapsedRealtimeNanos,
			)

			val factualCompletedAtMs = requestedAtMs.coerceIn(
				active.startedAtMs,
				maxOf(active.startedAtMs, reconciliationAtMs),
			)
			val factualCutoffElapsedNanos = requestedElapsedRealtimeNanos?.takeIf { requestedElapsed ->
				requestedBootId == active.clockDomainId &&
					requestedBootId == reconciliationBootId &&
					requestedElapsed >= active.startedElapsedNanos &&
					requestedElapsed <= reconciliationElapsedRealtimeNanos
			}
			SourceBroker(database).retireSessionDemandsInTransaction(
				logicalTrackingId = logicalTrackingId,
				bootId = reconciliationBootId,
				elapsedRealtimeNanos = reconciliationElapsedRealtimeNanos,
				wallTimeMs = reconciliationAtMs,
			)
			dao.lifecycleActions(logicalTrackingId)
				.filter { action ->
					action.serviceRunId == currentRun.serviceRunId && action.status in NONTERMINAL_ACTION_STATES
				}
				.forEach { action ->
					check(
						dao.updateLifecycleAction(
							action.copy(
								status = LifecycleActionStatus.SUPERSEDED.name,
								acknowledgedAtMs = reconciliationAtMs,
								acknowledgedElapsedRealtimeNanos =
									if (action.bootId == reconciliationBootId) {
										reconciliationElapsedRealtimeNanos
									}
									else action.acknowledgedElapsedRealtimeNanos,
								failureCode = reason,
								retryTrigger = null,
							),
						) == 1,
					) { "Explicit-stop lifecycle action changed during finalization" }
				}
			check(
				dao.updateServiceRun(
					currentRun.copy(
							state = SessionLifecycleState.FINALIZED.name,
							completedAtMs = factualCompletedAtMs.coerceAtLeast(currentRun.startedAtMs),
							completionReason = reason,
							runtimeAcknowledgement = LifecycleActionStatus.TERMINAL_FAILURE.name,
							runtimeFailureCode = reason,
							runRevision = currentRun.runRevision + 1L,
						),
				) == 1,
			) { "Explicit-stop service run changed during finalization" }
			check(
				dao.updateSession(
					active.copy(
						state = SessionLifecycleState.FINALIZED.name,
						lifecycleRevision = active.lifecycleRevision + 1L,
						cutoffAtMs = active.cutoffAtMs ?: factualCompletedAtMs,
						cutoffElapsedNanos =
							active.cutoffElapsedNanos ?: factualCutoffElapsedNanos,
						completedAtMs = factualCompletedAtMs,
						currentServiceRunId = null,
					),
				) == 1,
			) { "Explicit-stop logical session changed during finalization" }
			ExplicitStopSourceSessionFinalization.FINALIZED
		}
	}

	private companion object {
		val NONTERMINAL_ACTION_STATES = setOf(
			LifecycleActionStatus.PENDING.name,
			LifecycleActionStatus.APPLYING.name,
			LifecycleActionStatus.CLEANUP_REQUIRED.name,
			LifecycleActionStatus.TEMPORARILY_ILLEGAL.name,
			LifecycleActionStatus.AWAITING_FOREGROUND.name,
		)
		val TERMINAL_SESSION_STATES = setOf(
			SessionLifecycleState.FINALIZED.name,
			"CLOSED",
			SessionLifecycleState.FAILED.name,
		)
	}
}

enum class ExplicitStopSourceSessionFinalization {
	FINALIZED,
	ALREADY_FINALIZED,
	NO_ROOM_SESSION,
	SESSION_MISMATCH,
}
