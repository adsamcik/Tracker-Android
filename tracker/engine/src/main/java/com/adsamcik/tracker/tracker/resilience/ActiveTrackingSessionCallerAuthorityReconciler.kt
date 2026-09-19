package com.adsamcik.tracker.tracker.resilience

import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.SourceCallerRejectionReason
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.api.TrackingStartFailureDisposition
import com.adsamcik.tracker.tracker.api.isRetryable
import com.adsamcik.tracker.tracker.failure.isTrackingOperationalFailure
import com.adsamcik.tracker.tracker.source.coordinator.AuthoritativeSessionCoordinator
import com.adsamcik.tracker.tracker.source.coordinator.CurrentRecoverySourceCallerAuthorityResult
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerAuthorityRetirementOutcome
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerAuthorityRetirementRetryReason
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerDemandDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal sealed interface ActiveTrackingCallerAuthorityReconciliation {
	data class Ready(
		val descriptor: ActiveTrackingSessionDescriptor,
	) : ActiveTrackingCallerAuthorityReconciliation

	data class Blocked(
		val failureCode: String,
		val disposition: TrackingStartFailureDisposition,
		val descriptor: ActiveTrackingSessionDescriptor,
	) : ActiveTrackingCallerAuthorityReconciliation
}

/**
 * Repairs the non-transactional Room-to-DataStore caller-authority handoff before recovery.
 *
 * Room remains authoritative. The descriptor first records an exact predecessor debt, then the
 * current reference is replayed, then predecessor retirement and debt clearance are retried
 * idempotently.
 */
@Singleton
internal class ActiveTrackingSessionCallerAuthorityReconciler @Inject constructor(
	private val activeTrackingSessionStore: ActiveTrackingSessionStore,
	private val authoritativeSessionCoordinator: AuthoritativeSessionCoordinator,
	private val sourceCallerDemandDispatcher: SourceCallerDemandDispatcher,
) {
	suspend fun reconcile(
		descriptor: ActiveTrackingSessionDescriptor,
	): ActiveTrackingCallerAuthorityReconciliation {
		var reconciled = descriptor
		return try {
			val authority = when (
				val current = authoritativeSessionCoordinator.currentRecoverySourceCallerAuthority(
					descriptor.logicalTrackingId,
					descriptor.serviceRunId,
				)
			) {
				is CurrentRecoverySourceCallerAuthorityResult.Available -> current.authority
				is CurrentRecoverySourceCallerAuthorityResult.Rejected ->
					return ActiveTrackingCallerAuthorityReconciliation.Blocked(
						failureCode = current.failureCode,
						disposition = current.disposition,
						descriptor = reconciled,
					)
			}
			if (reconciled.sourceCallerAuthorityReference != authority.reference) {
				val predecessor = reconciled.sourceCallerAuthorityReference
					?: return ActiveTrackingCallerAuthorityReconciliation.Blocked(
						failureCode = "RECOVERY_SOURCE_CALLER_DESCRIPTOR_REFERENCE_MISSING",
						disposition = TrackingStartFailureDisposition.TERMINAL,
						descriptor = reconciled,
					)
				if (reconciled.pendingRetirementSourceCallerAuthorityReference != null) {
					return ActiveTrackingCallerAuthorityReconciliation.Blocked(
						failureCode = "RECOVERY_SOURCE_CALLER_RETIREMENT_AMBIGUOUS",
						disposition = TrackingStartFailureDisposition.TERMINAL,
						descriptor = reconciled,
					)
				}
				val replacement = reconciled.copy(
					sourceCallerAuthorityReference = authority.reference,
					pendingRetirementSourceCallerAuthorityReference = predecessor,
				)
				reconciled = when (
					val persisted = activeTrackingSessionStore.replaceExact(reconciled, replacement)
				) {
					is ActiveTrackingSessionStoreResult.Failure ->
						return ActiveTrackingCallerAuthorityReconciliation.Blocked(
							failureCode = "RECOVERY_SOURCE_CALLER_DESCRIPTOR_UPDATE_FAILED",
							disposition = persisted.kind.toFailureDisposition(),
							descriptor = reconciled,
						)
					is ActiveTrackingSessionStoreResult.Success -> {
						val current = persisted.descriptor
						?.takeIf { candidate ->
							candidate.logicalTrackingId == reconciled.logicalTrackingId &&
								candidate.serviceRunId == reconciled.serviceRunId
						}
						?: reconciled
						if (current != replacement) {
							return ActiveTrackingCallerAuthorityReconciliation.Blocked(
								failureCode = "RECOVERY_SOURCE_CALLER_DESCRIPTOR_CHANGED",
								disposition = TrackingStartFailureDisposition.RETRYABLE,
								descriptor = current,
							)
						}
						current
					}
				}
			}
			when (val replay = sourceCallerDemandDispatcher.replayPreparedSession(
				manifestIdentity = authority.manifestIdentity,
				reference = authority.reference,
				replayKind = SourceCallerReplayKind.PROCESS_RECOVERY,
			)) {
				is SourceCallerGuardResult.Permitted -> Unit
				is SourceCallerGuardResult.Rejected ->
					return ActiveTrackingCallerAuthorityReconciliation.Blocked(
						failureCode = "RECOVERY_SOURCE_CALLER_${replay.rejection.reason.name}",
						disposition = replay.rejection.reason.toFailureDisposition(),
						descriptor = reconciled,
					)
			}
			val predecessor = reconciled.pendingRetirementSourceCallerAuthorityReference
				?: return ActiveTrackingCallerAuthorityReconciliation.Ready(reconciled)
			when (val retirement =
				authoritativeSessionCoordinator.retireSupersededSourceCallerAuthority(
					logicalTrackingId = reconciled.logicalTrackingId,
					currentReference = authority.reference,
					supersededReference = predecessor,
					wallTimeMs = System.currentTimeMillis(),
				)) {
				SourceCallerAuthorityRetirementOutcome.Completed -> Unit
				is SourceCallerAuthorityRetirementOutcome.Retryable ->
					return ActiveTrackingCallerAuthorityReconciliation.Blocked(
						failureCode = retirement.failureCode(),
						disposition = TrackingStartFailureDisposition.RETRYABLE,
						descriptor = reconciled,
					)
				SourceCallerAuthorityRetirementOutcome.TerminalMissing,
				SourceCallerAuthorityRetirementOutcome.TerminalCorrupt,
				SourceCallerAuthorityRetirementOutcome.TerminalInvariant,
				SourceCallerAuthorityRetirementOutcome.TerminalAmbiguous,
				-> return ActiveTrackingCallerAuthorityReconciliation.Blocked(
					failureCode = retirement.failureCode(),
					disposition = TrackingStartFailureDisposition.TERMINAL,
					descriptor = reconciled,
				)
			}
			val cleared = reconciled.copy(
				pendingRetirementSourceCallerAuthorityReference = null,
			)
			when (val persisted = activeTrackingSessionStore.replaceExact(reconciled, cleared)) {
				is ActiveTrackingSessionStoreResult.Failure ->
					ActiveTrackingCallerAuthorityReconciliation.Blocked(
						failureCode = "RECOVERY_SOURCE_CALLER_RETIREMENT_DEBT_CLEAR_FAILED",
						disposition = persisted.kind.toFailureDisposition(),
						descriptor = reconciled,
					)
				is ActiveTrackingSessionStoreResult.Success ->
					if (persisted.descriptor == cleared) {
						ActiveTrackingCallerAuthorityReconciliation.Ready(cleared)
					} else {
						ActiveTrackingCallerAuthorityReconciliation.Blocked(
							failureCode = "RECOVERY_SOURCE_CALLER_DESCRIPTOR_CHANGED",
							disposition = TrackingStartFailureDisposition.RETRYABLE,
							descriptor = persisted.descriptor
								?.takeIf { candidate ->
									candidate.logicalTrackingId == reconciled.logicalTrackingId &&
										candidate.serviceRunId == reconciled.serviceRunId
								}
								?: reconciled,
						)
					}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			val operational = failure.isTrackingOperationalFailure()
			ActiveTrackingCallerAuthorityReconciliation.Blocked(
				failureCode = if (operational) {
					"RECOVERY_SOURCE_CALLER_RECONCILIATION_UNAVAILABLE"
				} else {
					"RECOVERY_SOURCE_CALLER_RECONCILIATION_INVARIANT"
				},
				disposition = if (operational) {
					TrackingStartFailureDisposition.RETRYABLE
				} else {
					TrackingStartFailureDisposition.TERMINAL
				},
				descriptor = reconciled,
			)
		}
	}
}

private fun SourceCallerAuthorityRetirementOutcome.failureCode(): String = when (this) {
	SourceCallerAuthorityRetirementOutcome.Completed ->
		error("Completed retirement has no failure code")
	is SourceCallerAuthorityRetirementOutcome.Retryable -> when (reason) {
		SourceCallerAuthorityRetirementRetryReason.STORAGE_UNAVAILABLE ->
			"RECOVERY_SOURCE_CALLER_RETIREMENT_STORAGE_UNAVAILABLE"
		SourceCallerAuthorityRetirementRetryReason.COMPARE_AND_SET_FAILED ->
			"RECOVERY_SOURCE_CALLER_RETIREMENT_CAS_FAILED"
		SourceCallerAuthorityRetirementRetryReason.CURRENT_AUTHORITY_CHANGED ->
			"RECOVERY_SOURCE_CALLER_RETIREMENT_CURRENT_CHANGED"
	}
	SourceCallerAuthorityRetirementOutcome.TerminalMissing ->
		"RECOVERY_SOURCE_CALLER_RETIREMENT_MISSING"
	SourceCallerAuthorityRetirementOutcome.TerminalCorrupt ->
		"RECOVERY_SOURCE_CALLER_RETIREMENT_CORRUPT"
	SourceCallerAuthorityRetirementOutcome.TerminalInvariant ->
		"RECOVERY_SOURCE_CALLER_RETIREMENT_INVARIANT"
	SourceCallerAuthorityRetirementOutcome.TerminalAmbiguous ->
		"RECOVERY_SOURCE_CALLER_RETIREMENT_AMBIGUOUS"
}

private fun ActiveTrackingSessionStoreFailureKind.toFailureDisposition(): TrackingStartFailureDisposition =
	when (this) {
		ActiveTrackingSessionStoreFailureKind.UNAVAILABLE -> TrackingStartFailureDisposition.RETRYABLE
		ActiveTrackingSessionStoreFailureKind.CORRUPT -> TrackingStartFailureDisposition.TERMINAL
	}

private fun SourceCallerRejectionReason.toFailureDisposition(): TrackingStartFailureDisposition =
	if (isRetryable) {
		TrackingStartFailureDisposition.RETRYABLE
	} else {
		TrackingStartFailureDisposition.TERMINAL
	}
