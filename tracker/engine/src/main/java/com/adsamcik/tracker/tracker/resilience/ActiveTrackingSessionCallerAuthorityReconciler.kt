package com.adsamcik.tracker.tracker.resilience

import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.source.coordinator.AuthoritativeSessionCoordinator
import com.adsamcik.tracker.tracker.source.coordinator.CurrentRecoverySourceCallerAuthorityResult
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
						current.failureCode,
					)
			}
			var reconciled = descriptor
			if (reconciled.sourceCallerAuthorityReference != authority.reference) {
				val predecessor = reconciled.sourceCallerAuthorityReference
					?: return ActiveTrackingCallerAuthorityReconciliation.Blocked(
						"RECOVERY_SOURCE_CALLER_DESCRIPTOR_REFERENCE_MISSING",
					)
				if (reconciled.pendingRetirementSourceCallerAuthorityReference != null) {
					return ActiveTrackingCallerAuthorityReconciliation.Blocked(
						"RECOVERY_SOURCE_CALLER_RETIREMENT_AMBIGUOUS",
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
							"RECOVERY_SOURCE_CALLER_DESCRIPTOR_UPDATE_FAILED",
						)
					is ActiveTrackingSessionStoreResult.Success -> persisted.descriptor
						?.takeIf { it == replacement }
						?: return ActiveTrackingCallerAuthorityReconciliation.Blocked(
							"RECOVERY_SOURCE_CALLER_DESCRIPTOR_CHANGED",
						)
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
					)
			}
			val predecessor = reconciled.pendingRetirementSourceCallerAuthorityReference
				?: return ActiveTrackingCallerAuthorityReconciliation.Ready(reconciled)
			if (!authoritativeSessionCoordinator.retireSupersededSourceCallerAuthority(
					logicalTrackingId = reconciled.logicalTrackingId,
					currentReference = authority.reference,
					supersededReference = predecessor,
					wallTimeMs = System.currentTimeMillis(),
				)
			) {
				return ActiveTrackingCallerAuthorityReconciliation.Blocked(
					"RECOVERY_SOURCE_CALLER_RETIREMENT_PENDING",
				)
			}
			val cleared = reconciled.copy(
				pendingRetirementSourceCallerAuthorityReference = null,
			)
			when (val persisted = activeTrackingSessionStore.replaceExact(reconciled, cleared)) {
				is ActiveTrackingSessionStoreResult.Failure ->
					ActiveTrackingCallerAuthorityReconciliation.Blocked(
						"RECOVERY_SOURCE_CALLER_RETIREMENT_DEBT_CLEAR_FAILED",
					)
				is ActiveTrackingSessionStoreResult.Success ->
					if (persisted.descriptor == cleared) {
						ActiveTrackingCallerAuthorityReconciliation.Ready(cleared)
					} else {
						ActiveTrackingCallerAuthorityReconciliation.Blocked(
							"RECOVERY_SOURCE_CALLER_DESCRIPTOR_CHANGED",
						)
					}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			ActiveTrackingCallerAuthorityReconciliation.Blocked(
				"RECOVERY_SOURCE_CALLER_RECONCILIATION_UNAVAILABLE",
			)
		}
	}
}
