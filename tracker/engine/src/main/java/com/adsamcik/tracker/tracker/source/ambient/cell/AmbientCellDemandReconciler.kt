package com.adsamcik.tracker.tracker.source.ambient.cell

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.source.ambient.AmbientRadioReconciliationEvidence
import com.adsamcik.tracker.tracker.source.ambient.AmbientRadioReportPreparation
import com.adsamcik.tracker.tracker.source.ambient.AmbientRadioReportPreparationRejection
import com.adsamcik.tracker.tracker.source.ambient.prepareAmbientRadioReport
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.AmbientCellRuntimeJoinResult
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioDemandInactiveReason
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioDemandResult
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioDemandDispatchRequest
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioReconciliationAuthority
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioRetirementPlan
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.GuardedAmbientRadioAttempt
import com.adsamcik.tracker.tracker.source.runtime.GuardedPurposeDemandResult
import com.adsamcik.tracker.tracker.source.runtime.SharedCellSourceController
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerDemandDispatcher
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class AmbientCellActivationRequest(val enabled: Boolean)

/** Default-off Cell gate. No subscription or Telephony API is read until every policy check wins. */
@Singleton
class AmbientCellDemandReconciler @Inject constructor(
	private val sourceBroker: SourceBroker,
	private val sourceCallerDemandDispatcher: SourceCallerDemandDispatcher,
	private val sharedController: SharedCellSourceController,
	private val clockDomainProvider: BootClockDomainProvider,
) {
	private val reconciliationAttempts = AtomicLong(0L)

	suspend fun reconcile(
		lease: AmbientReconciliationLease,
		request: AmbientCellActivationRequest,
	): AmbientCellOwnerReconciliation {
		require(lease.identity.source == AmbientTrackingSource.CELL)
		val attempt = reconciliationAttempts.updateAndGet { previous ->
			Math.addExact(previous, 1L)
		}
		val outcome = reconcileOutcome(lease, request, attempt)
		val authority = outcome.reconciliationAuthorityOrNull()
			?: sourceBroker.ambientRadioReconciliationAuthority(SourceKind.CELL)
		return AmbientCellOwnerReconciliation(
			outcome = outcome,
			evidence = AmbientRadioReconciliationEvidence.from(
				authority = authority,
				reconciliationAttempt = attempt,
				demandId = outcome.demandIdOrNull(),
				sourceInstanceId = outcome.sourceInstanceIdOrNull(),
				registrationGeneration = outcome.registrationGenerationOrNull(),
			),
		)
	}

	private suspend fun reconcileOutcome(
		lease: AmbientReconciliationLease,
		request: AmbientCellActivationRequest,
		reconciliationAttempt: Long,
	): AmbientCellDemandReconciliation {
		val bootId = clockDomainProvider.current()
		val elapsedRealtimeNanos = Time.elapsedRealtimeNanos
		val wallTimeMs = Time.nowMillis
		var providerReconciliationEntered = false
		val guarded = try {
			sourceCallerDemandDispatcher.dispatchAmbientRadio(
				AmbientRadioDemandDispatchRequest(
					source = AmbientTrackingSource.CELL,
					leaseIdentity = lease.identity,
					consumerId = CONSUMER_ID,
					requested = request.enabled,
					reconciliationAttempt = reconciliationAttempt,
					bootId = bootId,
					elapsedRealtimeNanos = elapsedRealtimeNanos,
					wallTimeMs = wallTimeMs,
				),
			) { demand, attempt ->
				providerReconciliationEntered = true
				reconcileOutcomeUnderHeldLease(demand, attempt)
			}
		} catch (cancelled: CancellationException) {
			if (!providerReconciliationEntered) {
				withContext(NonCancellable) { sharedController.reconcileAmbientJoin() }
			}
			throw cancelled
		} catch (failure: Exception) {
			if (providerReconciliationEntered) throw failure
			return AmbientCellDemandBlockReason.STALE_RECONCILIATION_LEASE
				.afterAmbientJoinRetirement(
					sharedController.reconcileAmbientJoin(),
					reconciliationAuthority = null,
					demandId = null,
				)
		}
		return when (guarded) {
			is GuardedPurposeDemandResult.Applied -> guarded.value
			is GuardedPurposeDemandResult.Rejected,
			GuardedPurposeDemandResult.Stale,
			-> AmbientCellDemandBlockReason.STALE_RECONCILIATION_LEASE
				.afterAmbientJoinRetirement(
					sharedController.reconcileAmbientJoin(),
					reconciliationAuthority = null,
					demandId = null,
				)
		}
	}

	private suspend fun reconcileOutcomeUnderHeldLease(
		demand: AmbientRadioDemandResult,
		attempt: GuardedAmbientRadioAttempt,
	): AmbientCellDemandReconciliation {
		return try {
			when (demand) {
			is AmbientRadioDemandResult.Inactive -> {
				val reason = demand.reason.toPublicReason()
				if (demand.reason.isStaleReconciliation()) {
					AmbientCellDemandReconciliation.Inactive(
						reason,
						reconciliationAuthority = demand.reconciliationAuthority,
						demandId = demand.retiredDemandId,
					)
				} else {
					reason.afterAmbientJoinRetirement(
						sharedController.reconcileAmbientJoin(),
						demand.reconciliationAuthority,
						demand.retiredDemandId,
					)
				}
			}
			is AmbientRadioDemandResult.Active -> when (val runtime =
				sharedController.reconcileAmbientJoin()
			) {
				is AmbientCellRuntimeJoinResult.Inactive -> {
					val compensated = compensateRejectedRuntime(
						demand,
						attempt,
					)
					AmbientCellDemandReconciliation.Inactive(
						AmbientCellDemandBlockReason.RUNTIME_JOIN_RETIRED,
						runtime.providerKey,
						compensated,
						demand.demand.demandId,
					)
				}
				is AmbientCellRuntimeJoinResult.Active ->
					AmbientCellDemandReconciliation.Active(
						demand.demand.demandId,
						demand.authorityRevision,
						demand.reconciliationAuthority,
						runtime.sourceInstanceId,
						runtime.registrationGeneration,
					)
				is AmbientCellRuntimeJoinResult.Degraded ->
					AmbientCellDemandReconciliation.Degraded(
						demand.demand.demandId,
						demand.authorityRevision,
						demand.reconciliationAuthority,
						runtime.sourceInstanceId,
						runtime.registrationGeneration,
						runtime.reasons,
					)
				is AmbientCellRuntimeJoinResult.Unavailable -> {
					val compensated = compensateRejectedRuntime(
						demand,
						attempt,
					)
					AmbientCellDemandReconciliation.Unavailable(
						runtime.reasons,
						runtime.retryable,
						runtime.providerKey,
						compensated,
						demand.demand.demandId,
					)
				}
			}
			}
		} catch (cancelled: CancellationException) {
			compensateFailedReconciliation(demand, attempt, cancelled)
			throw cancelled
		} catch (failure: Exception) {
			compensateFailedReconciliation(demand, attempt, failure)
			throw failure
		}
	}

	private suspend fun compensateFailedReconciliation(
		demand: AmbientRadioDemandResult,
		attempt: GuardedAmbientRadioAttempt,
		failure: Exception,
	) {
		val active = demand as? AmbientRadioDemandResult.Active ?: return
		withContext(NonCancellable) {
			val compensated = try {
				sourceCallerDemandDispatcher.compensateAmbientRadio(
					attempt,
					active.demand.demandId,
				)
			} catch (@Suppress("TooGenericExceptionCaught") compensationFailure: Throwable) {
				if (compensationFailure !== failure) failure.addSuppressed(compensationFailure)
				return@withContext
			}
			if (compensated == null) {
				failure.addSuppressed(
					IllegalStateException("Unable to compensate exact Ambient Cell demand"),
				)
				return@withContext
			}
			try {
				sharedController.reconcileAmbientJoin()
			} catch (@Suppress("TooGenericExceptionCaught") cleanupFailure: Throwable) {
				if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
			}
		}
	}

	private suspend fun compensateRejectedRuntime(
		active: AmbientRadioDemandResult.Active,
		attempt: GuardedAmbientRadioAttempt,
	): AmbientRadioReconciliationAuthority = withContext(NonCancellable) {
		val compensated = requireNotNull(
			sourceCallerDemandDispatcher.compensateAmbientRadio(
				attempt,
				active.demand.demandId,
			),
		) { "Unable to compensate rejected Ambient Cell runtime reconciliation" }
		sharedController.reconcileAmbientJoin()
		compensated
	}

	suspend fun reconcilePurposeAvailability(
		lease: AmbientReconciliationLease,
		request: AmbientCellActivationRequest,
	): AmbientRadioReportPreparation {
		if (lease.identity.source != AmbientTrackingSource.CELL) {
			return AmbientRadioReportPreparation.Rejected(
				evidence = null,
				reason = AmbientRadioReportPreparationRejection.SOURCE_MISMATCH,
			)
		}
		return reconcile(lease, request).prepareReport(lease)
	}

	suspend fun retireAfterRetentionAuthorityFailure(
		previousLease: AmbientReconciliationLease?,
	): Boolean {
		val lease = previousLease ?: when (
			val plan = sourceBroker.ambientRadioRetirementPlan(SourceKind.CELL, CONSUMER_ID)
		) {
			AmbientRadioRetirementPlan.AlreadyRetired -> {
				val demandRetired = retireMalformedDemand()
				val providerRetired =
					sharedController.reconcileAmbientJoin() is AmbientCellRuntimeJoinResult.Inactive
				return demandRetired && providerRetired
			}
			is AmbientRadioRetirementPlan.Required -> {
				reconciliationAttempts.updateAndGet { current ->
					maxOf(current, plan.previousReconciliationAttempt)
				}
				plan.lease
			}
			AmbientRadioRetirementPlan.Unverifiable -> {
				val demandRetired = retireMalformedDemand()
				val providerRetired =
					sharedController.reconcileAmbientJoin() is AmbientCellRuntimeJoinResult.Inactive
				return demandRetired && providerRetired
			}
		}
		val outcome = reconcile(
			lease,
			AmbientCellActivationRequest(enabled = false),
		).outcome
		return outcome is AmbientCellDemandReconciliation.Inactive &&
			outcome.reason == AmbientCellDemandBlockReason.REQUEST_DISABLED
	}

	suspend fun closeForCollectedDataDeletion(): Boolean {
		val plan = sourceBroker.ambientRadioRetirementPlan(SourceKind.CELL, CONSUMER_ID)
		if (plan is AmbientRadioRetirementPlan.Unverifiable) {
			val demandRetired = retireMalformedDemand()
			val providerRetired = sharedController.closeAmbientForCollectedDataDeletion()
			return demandRetired && providerRetired
		}
		if (plan is AmbientRadioRetirementPlan.AlreadyRetired) {
			val demandRetired = retireMalformedDemand()
			val providerRetired = sharedController.closeAmbientForCollectedDataDeletion()
			return demandRetired && providerRetired
		}
		plan as AmbientRadioRetirementPlan.Required
		val attempt = reconciliationAttempts.updateAndGet { current ->
			Math.addExact(maxOf(current, plan.previousReconciliationAttempt), 1L)
		}
		val bootId = clockDomainProvider.current()
		val elapsedRealtimeNanos = Time.elapsedRealtimeNanos
		val wallTimeMs = Time.nowMillis
		val retired = sourceBroker.replaceAmbientCellDemandUnderHeldLease(
			consumerId = CONSUMER_ID,
			requested = false,
			leaseIdentity = plan.lease.identity,
			reconciliationAttempt = attempt,
			bootId = bootId,
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			wallTimeMs = wallTimeMs,
		)
		return retired is AmbientRadioDemandResult.Inactive &&
			sharedController.closeAmbientForCollectedDataDeletion()
	}

	private suspend fun retireMalformedDemand(): Boolean =
		sourceBroker.retirePurposeDemand(
			consumerId = CONSUMER_ID,
			expectedSourceKind = SourceKind.CELL.stableCode,
			expectedPurpose =
				com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose.AMBIENT_PRODUCT,
			bootId = clockDomainProvider.current(),
			elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
			wallTimeMs = Time.nowMillis,
		)

	private companion object {
		const val CONSUMER_ID = "app:ambient:cell"
	}
}

data class AmbientCellOwnerReconciliation(
	val outcome: AmbientCellDemandReconciliation,
	val evidence: AmbientRadioReconciliationEvidence,
) {
	init {
		require(evidence.source == AmbientTrackingSource.CELL)
		outcome.authorityRevisionOrNull()?.let { revision ->
			require(evidence.authorityRevision == revision)
		}
	}

	fun prepareReport(
		lease: AmbientReconciliationLease,
	): AmbientRadioReportPreparation = prepareAmbientRadioReport(
		lease,
		evidence,
		outcome.toOperationalAvailability(lease.purposeLeaseIdentity),
	)
}

sealed interface AmbientCellDemandReconciliation {
	data class Active(
		val demandId: String,
		val authorityRevision: Long,
		val reconciliationAuthority: AmbientRadioReconciliationAuthority,
		val sourceInstanceId: SourceInstanceId?,
		val registrationGeneration: Long?,
	) : AmbientCellDemandReconciliation {
		init {
			require(reconciliationAuthority.source == SourceKind.CELL)
			require(reconciliationAuthority.authorityRevision == authorityRevision)
		}
	}
	data class Degraded(
		val demandId: String,
		val authorityRevision: Long,
		val reconciliationAuthority: AmbientRadioReconciliationAuthority,
		val sourceInstanceId: SourceInstanceId?,
		val registrationGeneration: Long?,
		val reasons: Set<SourceDegradedReason>,
	) : AmbientCellDemandReconciliation {
		init {
			require(reconciliationAuthority.source == SourceKind.CELL)
			require(reconciliationAuthority.authorityRevision == authorityRevision)
		}
	}
	data class Inactive(
		val reason: AmbientCellDemandBlockReason,
		val providerKey: com.adsamcik.tracker.tracker.source.runtime.SourceProviderKey? = null,
		val reconciliationAuthority: AmbientRadioReconciliationAuthority? = null,
		val demandId: String? = null,
	) : AmbientCellDemandReconciliation {
		init {
			require(reconciliationAuthority?.source?.let { it == SourceKind.CELL } != false)
			require(demandId == null || demandId.isNotBlank())
		}
	}
	data class Unavailable(
		val reasons: Set<SourceDegradedReason>,
		val retryable: Boolean,
		val providerKey: com.adsamcik.tracker.tracker.source.runtime.SourceProviderKey? = null,
		val reconciliationAuthority: AmbientRadioReconciliationAuthority? = null,
		val demandId: String? = null,
	) : AmbientCellDemandReconciliation {
		init {
			require(reconciliationAuthority?.source?.let { it == SourceKind.CELL } != false)
			require(demandId == null || demandId.isNotBlank())
		}
	}
}

enum class AmbientCellDemandBlockReason {
	REQUEST_DISABLED,
	AUTHORITY_INACTIVE,
	POLICY_MISSING,
	CONSENT_REVOKED,
	PERSISTENCE_INELIGIBLE,
	ROLLOUT_CONTAINED,
	RETENTION_APPROVAL_MISSING,
	RETENTION_APPROVAL_MISMATCH,
	SOURCE_EVIDENCE_STATE_MISSING,
	AUTHORITY_REVISION_EXHAUSTED,
	STALE_RECONCILIATION_LEASE,
	STALE_RECONCILIATION_ATTEMPT,
	OWNERSHIP_CONFLICT,
	DELETION_AUTHORITY_MISMATCH,
	RUNTIME_JOIN_RETIRED,
	RUNTIME_JOIN_NOT_RETIRED,
}

private fun AmbientRadioDemandInactiveReason.toPublicReason(): AmbientCellDemandBlockReason =
	when (this) {
		AmbientRadioDemandInactiveReason.REQUEST_DISABLED ->
			AmbientCellDemandBlockReason.REQUEST_DISABLED
		AmbientRadioDemandInactiveReason.AUTHORITY_INACTIVE ->
			AmbientCellDemandBlockReason.AUTHORITY_INACTIVE
		AmbientRadioDemandInactiveReason.POLICY_MISSING ->
			AmbientCellDemandBlockReason.POLICY_MISSING
		AmbientRadioDemandInactiveReason.CONSENT_REVOKED ->
			AmbientCellDemandBlockReason.CONSENT_REVOKED
		AmbientRadioDemandInactiveReason.PERSISTENCE_INELIGIBLE ->
			AmbientCellDemandBlockReason.PERSISTENCE_INELIGIBLE
		AmbientRadioDemandInactiveReason.ROLLOUT_CONTAINED ->
			AmbientCellDemandBlockReason.ROLLOUT_CONTAINED
		AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISSING ->
			AmbientCellDemandBlockReason.RETENTION_APPROVAL_MISSING
		AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISMATCH ->
			AmbientCellDemandBlockReason.RETENTION_APPROVAL_MISMATCH
		AmbientRadioDemandInactiveReason.SOURCE_EVIDENCE_STATE_MISSING ->
			AmbientCellDemandBlockReason.SOURCE_EVIDENCE_STATE_MISSING
		AmbientRadioDemandInactiveReason.AUTHORITY_REVISION_EXHAUSTED ->
			AmbientCellDemandBlockReason.AUTHORITY_REVISION_EXHAUSTED
		AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE ->
			AmbientCellDemandBlockReason.STALE_RECONCILIATION_LEASE
		AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_ATTEMPT ->
			AmbientCellDemandBlockReason.STALE_RECONCILIATION_ATTEMPT
		AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT ->
			AmbientCellDemandBlockReason.OWNERSHIP_CONFLICT
		AmbientRadioDemandInactiveReason.DELETION_AUTHORITY_MISMATCH ->
			AmbientCellDemandBlockReason.DELETION_AUTHORITY_MISMATCH
	}

private fun AmbientRadioDemandInactiveReason.isStaleReconciliation(): Boolean =
	this == AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE ||
		this == AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_ATTEMPT ||
		this == AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT

private fun AmbientCellDemandBlockReason.afterAmbientJoinRetirement(
	runtime: AmbientCellRuntimeJoinResult,
	reconciliationAuthority: AmbientRadioReconciliationAuthority?,
	demandId: String?,
): AmbientCellDemandReconciliation = when (runtime) {
	is AmbientCellRuntimeJoinResult.Unavailable ->
		AmbientCellDemandReconciliation.Unavailable(
			runtime.reasons,
			runtime.retryable,
			runtime.providerKey,
			reconciliationAuthority,
			demandId,
		)
	is AmbientCellRuntimeJoinResult.Inactive ->
		AmbientCellDemandReconciliation.Inactive(
			this,
			runtime.providerKey,
			reconciliationAuthority,
			demandId,
		)
	is AmbientCellRuntimeJoinResult.Active ->
		AmbientCellDemandReconciliation.Inactive(
			AmbientCellDemandBlockReason.RUNTIME_JOIN_NOT_RETIRED,
			runtime.providerKeyOrNull(),
			reconciliationAuthority,
			demandId,
		)
	is AmbientCellRuntimeJoinResult.Degraded ->
		AmbientCellDemandReconciliation.Inactive(
			AmbientCellDemandBlockReason.RUNTIME_JOIN_NOT_RETIRED,
			runtime.providerKeyOrNull(),
			reconciliationAuthority,
			demandId,
		)
}

fun AmbientCellDemandReconciliation.toOperationalAvailability(
	identity: TrackingPurposeLeaseIdentity,
):
	AmbientSourceOperationalAvailability = when (this) {
	is AmbientCellDemandReconciliation.Active -> if (
		sourceInstanceId != null && registrationGeneration != null
	) {
		if (identity.executionRevision > 0L) {
			ambientCellAvailability(AmbientSourceOperationalState.READY, identity = identity)
		} else {
			AmbientSourceOperationalAvailability.reconciliationPending(
				AmbientTrackingSource.CELL,
				identity,
			)
		}
	} else {
		ambientCellProviderUnavailable(identity)
	}
	is AmbientCellDemandReconciliation.Degraded -> if (
		sourceInstanceId == null || registrationGeneration == null
	) {
		ambientCellProviderUnavailable(identity)
	} else if (SourceDegradedReason.PERMISSION_MISSING in reasons) {
		ambientCellAvailability(
			state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
			reason = AmbientSourceUnavailableReason.CELL_SCAN_PERMISSION_REQUIRED,
			identity = identity,
		)
	} else if (reasons.any { it in CELL_NONOPERATIONAL_PLATFORM_REASONS }) {
		ambientCellAvailability(
			state = AmbientSourceOperationalState.UNAVAILABLE,
			reason = AmbientSourceUnavailableReason.PLATFORM_UNAVAILABLE,
			identity = identity,
		)
	} else {
		ambientCellAvailability(
			state = AmbientSourceOperationalState.UNAVAILABLE,
			reason = reasons.toAmbientCellReason(),
			identity = identity,
		)
	}
	is AmbientCellDemandReconciliation.Inactive -> when (reason) {
		AmbientCellDemandBlockReason.RETENTION_APPROVAL_MISSING,
		AmbientCellDemandBlockReason.RETENTION_APPROVAL_MISMATCH,
		-> ambientCellRetentionUnavailable(identity)
		AmbientCellDemandBlockReason.ROLLOUT_CONTAINED -> ambientCellAvailability(
			state = AmbientSourceOperationalState.UNAVAILABLE,
			reason = AmbientSourceUnavailableReason.ROLLOUT_CONTAINED,
			identity = identity,
		)
		AmbientCellDemandBlockReason.AUTHORITY_INACTIVE,
		AmbientCellDemandBlockReason.POLICY_MISSING,
		AmbientCellDemandBlockReason.REQUEST_DISABLED,
		AmbientCellDemandBlockReason.CONSENT_REVOKED,
		AmbientCellDemandBlockReason.PERSISTENCE_INELIGIBLE,
		AmbientCellDemandBlockReason.SOURCE_EVIDENCE_STATE_MISSING,
		AmbientCellDemandBlockReason.AUTHORITY_REVISION_EXHAUSTED,
		AmbientCellDemandBlockReason.STALE_RECONCILIATION_LEASE,
		AmbientCellDemandBlockReason.STALE_RECONCILIATION_ATTEMPT,
		AmbientCellDemandBlockReason.OWNERSHIP_CONFLICT,
		AmbientCellDemandBlockReason.DELETION_AUTHORITY_MISMATCH,
		-> AmbientSourceOperationalAvailability.reconciliationPending(
			AmbientTrackingSource.CELL,
			identity,
		)
		AmbientCellDemandBlockReason.RUNTIME_JOIN_RETIRED -> ambientCellProviderUnavailable(identity)
		AmbientCellDemandBlockReason.RUNTIME_JOIN_NOT_RETIRED ->
			ambientCellProviderUnavailable(identity)
	}
	is AmbientCellDemandReconciliation.Unavailable -> reasons.toAmbientCellUnavailable(identity)
}

private fun Set<SourceDegradedReason>.toAmbientCellUnavailable(
	identity: TrackingPurposeLeaseIdentity,
):
	AmbientSourceOperationalAvailability {
	if (SourceDegradedReason.PERMISSION_MISSING in this) {
		return ambientCellAvailability(
			state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
			reason = AmbientSourceUnavailableReason.CELL_SCAN_PERMISSION_REQUIRED,
			identity = identity,
		)
	}
	if (isEmpty()) return ambientCellProviderUnavailable(identity)
	return ambientCellAvailability(
		state = AmbientSourceOperationalState.UNAVAILABLE,
		reason = toAmbientCellReason(),
		identity = identity,
	)
}

private fun Set<SourceDegradedReason>.toAmbientCellReason(): AmbientSourceUnavailableReason =
	when {
		SourceDegradedReason.PERMISSION_MISSING in this ->
			AmbientSourceUnavailableReason.CELL_SCAN_PERMISSION_REQUIRED
		any { reason -> reason in CELL_PLATFORM_RADIO_REASONS } ->
			AmbientSourceUnavailableReason.PLATFORM_UNAVAILABLE
		else -> AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE
	}

private fun ambientCellProviderUnavailable(
	identity: TrackingPurposeLeaseIdentity,
) = ambientCellAvailability(
	state = AmbientSourceOperationalState.UNAVAILABLE,
	reason = AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
	identity = identity,
)

private fun ambientCellRetentionUnavailable(
	identity: TrackingPurposeLeaseIdentity,
) = ambientCellAvailability(
	state = AmbientSourceOperationalState.UNAVAILABLE,
	reason = AmbientSourceUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
	identity = identity,
)

private fun ambientCellAvailability(
	state: AmbientSourceOperationalState,
	reason: AmbientSourceUnavailableReason? = null,
	identity: TrackingPurposeLeaseIdentity,
) = AmbientSourceOperationalAvailability(
	source = AmbientTrackingSource.CELL,
	state = state,
	mechanism = when (state) {
		AmbientSourceOperationalState.READY,
		AmbientSourceOperationalState.PERMISSION_REQUIRED,
		-> AmbientAcquisitionMechanism.CELL_CHANGE_CALLBACKS
		AmbientSourceOperationalState.UNAVAILABLE -> null
		AmbientSourceOperationalState.WAITING -> null
		AmbientSourceOperationalState.DEGRADED ->
			error("The purpose matrix does not permit degraded Ambient Cell")
	},
	reason = reason,
	operationalIdentity = identity.takeIf {
		state == AmbientSourceOperationalState.READY ||
			state == AmbientSourceOperationalState.DEGRADED
	},
	lastIdentity = identity.takeUnless {
		state == AmbientSourceOperationalState.READY ||
			state == AmbientSourceOperationalState.DEGRADED
	},
)

private val CELL_PLATFORM_RADIO_REASONS = setOf(
	SourceDegradedReason.HARDWARE_UNAVAILABLE,
	SourceDegradedReason.BACKGROUND_START_ILLEGAL,
	SourceDegradedReason.FOREGROUND_CAPABILITY_MISSING,
	SourceDegradedReason.POWER_SAVER,
	SourceDegradedReason.THERMAL,
	SourceDegradedReason.DOZE,
	SourceDegradedReason.PLATFORM_THROTTLED,
)

private val CELL_NONOPERATIONAL_PLATFORM_REASONS = setOf(
	SourceDegradedReason.HARDWARE_UNAVAILABLE,
	SourceDegradedReason.BACKGROUND_START_ILLEGAL,
	SourceDegradedReason.FOREGROUND_CAPABILITY_MISSING,
)

private fun AmbientCellDemandReconciliation.demandIdOrNull(): String? = when (this) {
	is AmbientCellDemandReconciliation.Active -> demandId
	is AmbientCellDemandReconciliation.Degraded -> demandId
	is AmbientCellDemandReconciliation.Inactive -> demandId
	is AmbientCellDemandReconciliation.Unavailable -> demandId
}

private fun AmbientCellDemandReconciliation.sourceInstanceIdOrNull(): SourceInstanceId? =
	when (this) {
		is AmbientCellDemandReconciliation.Active -> sourceInstanceId
		is AmbientCellDemandReconciliation.Degraded -> sourceInstanceId
		is AmbientCellDemandReconciliation.Inactive -> providerKey?.sourceInstanceId
		is AmbientCellDemandReconciliation.Unavailable -> providerKey?.sourceInstanceId
	}

private fun AmbientCellDemandReconciliation.registrationGenerationOrNull(): Long? =
	when (this) {
		is AmbientCellDemandReconciliation.Active -> registrationGeneration
		is AmbientCellDemandReconciliation.Degraded -> registrationGeneration
		is AmbientCellDemandReconciliation.Inactive -> providerKey?.registrationGeneration
		is AmbientCellDemandReconciliation.Unavailable -> providerKey?.registrationGeneration
	}

private fun AmbientCellDemandReconciliation.reconciliationAuthorityOrNull():
	AmbientRadioReconciliationAuthority? = when (this) {
	is AmbientCellDemandReconciliation.Active -> reconciliationAuthority
	is AmbientCellDemandReconciliation.Degraded -> reconciliationAuthority
	is AmbientCellDemandReconciliation.Inactive -> reconciliationAuthority
	is AmbientCellDemandReconciliation.Unavailable -> reconciliationAuthority
}

private fun AmbientCellDemandReconciliation.authorityRevisionOrNull(): Long? = when (this) {
	is AmbientCellDemandReconciliation.Active -> authorityRevision
	is AmbientCellDemandReconciliation.Degraded -> authorityRevision
	is AmbientCellDemandReconciliation.Inactive -> reconciliationAuthority?.authorityRevision
	is AmbientCellDemandReconciliation.Unavailable -> reconciliationAuthority?.authorityRevision
}

private fun AmbientCellRuntimeJoinResult.Active.providerKeyOrNull() =
	if (sourceInstanceId != null && registrationGeneration != null) {
		com.adsamcik.tracker.tracker.source.runtime.SourceProviderKey(
			sourceInstanceId,
			registrationGeneration,
		)
	} else {
		null
	}

private fun AmbientCellRuntimeJoinResult.Degraded.providerKeyOrNull() =
	if (sourceInstanceId != null && registrationGeneration != null) {
		com.adsamcik.tracker.tracker.source.runtime.SourceProviderKey(
			sourceInstanceId,
			registrationGeneration,
		)
	} else {
		null
	}
