package com.adsamcik.tracker.tracker.resilience

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.rotateActivityAutomationEpochInTransaction
import com.adsamcik.tracker.shared.base.database.dao.SourceSessionDao
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.tracker.api.TrackingStartFailureDisposition
import com.adsamcik.tracker.tracker.source.coordinator.LifecycleActionStatus
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.source.coordinator.currentRecoverySourceCallerAuthorityCandidate
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Finalizes incomplete source authority that cannot be safely resumed by this process.
 *
 * Automatic sessions never survive a process exit. A manual session survives only when its
 * same-boot descriptor is reconciled to authenticated Room caller authority and names either the
 * exact active run or the exact completed suspend-for-restart run. A committed reconfiguration may
 * be finished by the next recovery owner instead of being mistaken for an intentional stop. In
 * particular, elapsed-time leases and manual authority never cross boots.
 * Session, service-run, lifecycle-action, demand, and authorization changes share one Room
 * transaction; the recovery coordinator separately compare-and-clears the descriptor mirror.
 */
@Singleton
class PreviousExitSourceSessionFinalizer @Inject internal constructor(
	private val databaseProvider: Provider<AppDatabase>,
	private val clock: Clock,
	private val bootClockDomainProvider: BootClockDomainProvider,
	private val callerAuthorityReconciler: ActiveTrackingSessionCallerAuthorityReconciler,
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
		val eligibleRecoveryDescriptor = recoveryDescriptor
			?.takeIf { descriptor ->
				descriptor.isUserInitiated && descriptor.isRestartEligibleForBoot(bootId)
			}
		val reconciliation = eligibleRecoveryDescriptor?.let { descriptor ->
			callerAuthorityReconciler.reconcile(descriptor)
		}
		val recoveryCandidate = when (reconciliation) {
			null -> null
			is ActiveTrackingCallerAuthorityReconciliation.Ready -> reconciliation.descriptor
			is ActiveTrackingCallerAuthorityReconciliation.Blocked -> {
				if (reconciliation.disposition == TrackingStartFailureDisposition.RETRYABLE) {
					throw PreviousExitCallerAuthorityUnavailableException(reconciliation.failureCode)
				}
				null
			}
		}
		val inspectedRecoveryDescriptor = when (reconciliation) {
			null -> recoveryDescriptor
			is ActiveTrackingCallerAuthorityReconciliation.Ready -> reconciliation.descriptor
			is ActiveTrackingCallerAuthorityReconciliation.Blocked -> reconciliation.descriptor
		}
		return database.withTransaction {
			val dao = database.sourceSessionDao()
			val incompleteSessions = dao.incompleteSessions()
			val authorities = buildMap {
				for (session in incompleteSessions) {
					put(session.logicalTrackingId, dao.serviceRunAuthorityForFinalization(session))
				}
			}
			val authenticatedRecoveryAuthority = recoveryCandidate?.let { descriptor ->
				val session = dao.session(descriptor.logicalTrackingId)
				val manifestRevision = session?.currentManifestRevision
				val intentRevision = session?.currentIntentRevision
				currentRecoverySourceCallerAuthorityCandidate(
					logicalTrackingId = descriptor.logicalTrackingId,
					serviceRunId = descriptor.serviceRunId,
					session = session,
					run = dao.serviceRun(descriptor.serviceRunId),
					manifest = manifestRevision
						?.let { revision -> dao.manifest(descriptor.logicalTrackingId, revision) },
					bindings = manifestRevision
						?.let { revision ->
							dao.manifestSources(descriptor.logicalTrackingId, revision)
						}
						.orEmpty(),
					intent = intentRevision
						?.let { revision ->
							dao.lifecycleIntent(descriptor.logicalTrackingId, revision)
						},
				)?.takeIf { authority ->
					authority.reference == descriptor.sourceCallerAuthorityReference &&
						descriptor.pendingRetirementSourceCallerAuthorityReference == null
				}
			}
			val descriptorDisposition = when {
				inspectedRecoveryDescriptor == null ->
					PreviousExitRecoveryDescriptorDisposition.NoDescriptor
				recoveryCandidate != null && authenticatedRecoveryAuthority != null ->
					PreviousExitRecoveryDescriptorDisposition.Preserved(
						inspectedRecoveryDescriptor,
					)
				else -> PreviousExitRecoveryDescriptorDisposition.Clear(
					inspectedRecoveryDescriptor,
				)
			}
			val stale = incompleteSessions.filter { session ->
				val isRecoverableManual = recoveryCandidate != null &&
					session.logicalTrackingId == recoveryCandidate.logicalTrackingId &&
					authenticatedRecoveryAuthority != null
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
			incompleteSessions
				.filterNot { session -> session in stale }
				.forEach { session ->
					terminalizeRuns(
						dao = dao,
						logicalTrackingId = session.logicalTrackingId,
						runs = authorities.getValue(session.logicalTrackingId).orphanRuns,
						completedAtMs = maxOf(recoveryAtMs, session.startedAtMs),
						acknowledgedElapsedRealtimeNanos = elapsedRealtimeNanos,
						bootId = bootId,
					)
				}
			stale.forEach { session ->
				val incompleteRuns = authorities.getValue(session.logicalTrackingId).incompleteRuns
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
				terminalizeRuns(
					dao = dao,
					logicalTrackingId = session.logicalTrackingId,
					runs = incompleteRuns,
					completedAtMs = completedAtMs,
					acknowledgedElapsedRealtimeNanos = elapsedRealtimeNanos,
					bootId = bootId,
				)
				check(
					dao.updateSession(
						session.copy(
							state = SessionLifecycleState.FINALIZED.name,
							lifecycleRevision = session.lifecycleRevision + 1L,
							cutoffAtMs = cutoffAtMs,
							cutoffElapsedNanos = cutoffElapsedNanos,
							completedAtMs = completedAtMs,
							failureCode = COMPLETION_REASON,
							currentServiceRunId = null,
						),
					) == 1,
				) { "Previous-exit logical session changed during finalization" }
			}
			PreviousExitSourceSessionFinalization(
				finalizedLogicalTrackingIds = stale.mapTo(linkedSetOf()) { it.logicalTrackingId },
				descriptorDisposition = descriptorDisposition,
			)
		}
	}

	private suspend fun terminalizeRuns(
		dao: SourceSessionDao,
		logicalTrackingId: String,
		runs: List<SourceServiceRunEntity>,
		completedAtMs: Long,
		acknowledgedElapsedRealtimeNanos: Long,
		bootId: String,
	) {
		val runIds = runs.mapTo(linkedSetOf()) { it.serviceRunId }
		dao.lifecycleActions(logicalTrackingId)
			.filter { action ->
				action.serviceRunId in runIds && action.status in NONTERMINAL_ACTION_STATES
			}
			.forEach { action ->
				check(
					dao.updateLifecycleAction(
						action.copy(
							status = LifecycleActionStatus.TERMINAL_FAILURE.name,
							acknowledgedAtMs = completedAtMs,
							acknowledgedElapsedRealtimeNanos =
								if (action.bootId == bootId) acknowledgedElapsedRealtimeNanos
								else action.acknowledgedElapsedRealtimeNanos,
							failureCode = COMPLETION_REASON,
							retryTrigger = null,
						),
					) == 1,
				) { "Previous-exit lifecycle action changed during finalization" }
			}
		runs.forEach { run ->
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
			LifecycleActionStatus.CLEANUP_REQUIRED.name,
			LifecycleActionStatus.TEMPORARILY_ILLEGAL.name,
			LifecycleActionStatus.AWAITING_FOREGROUND.name,
		)
		private val PENDING_AUTOMATIC_ACTION_STATES = setOf(
			ActivityAutomaticStartActionEntity.STATUS_RESERVED,
			ActivityAutomaticStartActionEntity.STATUS_RETRYABLE,
			ActivityAutomaticStartActionEntity.STATUS_START_REQUESTED,
		)
	}
}

data class PreviousExitSourceSessionFinalization(
	val finalizedLogicalTrackingIds: Set<String>,
	val descriptorDisposition: PreviousExitRecoveryDescriptorDisposition =
		PreviousExitRecoveryDescriptorDisposition.NoDescriptor,
)

sealed interface PreviousExitRecoveryDescriptorDisposition {
	data object NoDescriptor : PreviousExitRecoveryDescriptorDisposition

	data class Preserved(
		val descriptor: ActiveTrackingSessionDescriptor,
	) : PreviousExitRecoveryDescriptorDisposition

	data class Clear(
		val descriptor: ActiveTrackingSessionDescriptor,
	) : PreviousExitRecoveryDescriptorDisposition
}

internal class PreviousExitCallerAuthorityUnavailableException(
	val failureCode: String,
) : IOException(failureCode)
