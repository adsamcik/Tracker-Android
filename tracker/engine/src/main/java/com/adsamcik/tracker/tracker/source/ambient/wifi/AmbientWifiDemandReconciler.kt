package com.adsamcik.tracker.tracker.source.ambient.wifi

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.source.ambient.AmbientRadioReconciliationEvidence
import com.adsamcik.tracker.tracker.source.ambient.AmbientRadioReportPreparation
import com.adsamcik.tracker.tracker.source.ambient.AmbientRadioReportPreparationRejection
import com.adsamcik.tracker.tracker.source.ambient.prepareAmbientRadioReport
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioDemandInactiveReason
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioDemandResult
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioLeaseMutation
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioReconciliationAuthority
import com.adsamcik.tracker.tracker.source.runtime.AmbientWifiRuntimeJoinResult
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SharedWifiSourceController
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class AmbientWifiActivationRequest(val enabled: Boolean)

/**
 * Explicit default-off entry point. Policy and retention are checked before the shared controller
 * can read platform capability or touch the provider.
 */
@Singleton
class AmbientWifiDemandReconciler @Inject constructor(
	private val sourceBroker: SourceBroker,
	private val sharedController: SharedWifiSourceController,
	private val clockDomainProvider: BootClockDomainProvider,
) {
	private val reconciliationAttempts = AtomicLong(0L)

	suspend fun reconcile(
		lease: AmbientReconciliationLease,
		request: AmbientWifiActivationRequest,
	): AmbientWifiOwnerReconciliation {
		require(lease.identity.source == AmbientTrackingSource.WIFI)
		val attempt = reconciliationAttempts.updateAndGet { previous ->
			Math.addExact(previous, 1L)
		}
		val outcome = reconcileOutcome(lease, request, attempt)
		val authority = outcome.reconciliationAuthorityOrNull()
			?: sourceBroker.ambientRadioReconciliationAuthority(SourceKind.WIFI)
		return AmbientWifiOwnerReconciliation(
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
		request: AmbientWifiActivationRequest,
		reconciliationAttempt: Long,
	): AmbientWifiDemandReconciliation =
		when (val guarded = sourceBroker.withAmbientRadioMutationLease(lease.identity) {
			reconcileOutcomeUnderHeldLease(lease, request, reconciliationAttempt)
		}) {
			is AmbientRadioLeaseMutation.Applied -> guarded.value
			AmbientRadioLeaseMutation.Stale -> AmbientWifiDemandReconciliation.Inactive(
				AmbientWifiDemandBlockReason.STALE_RECONCILIATION_LEASE,
			)
		}

	private suspend fun reconcileOutcomeUnderHeldLease(
		lease: AmbientReconciliationLease,
		request: AmbientWifiActivationRequest,
		reconciliationAttempt: Long,
	): AmbientWifiDemandReconciliation {
		val boundary = AmbientWifiDemandBoundary(
			clockDomainProvider.current(),
			Time.elapsedRealtimeNanos,
			Time.nowMillis,
		)
		val demand = sourceBroker.replaceAmbientWifiDemandUnderHeldLease(
			consumerId = CONSUMER_ID,
			requested = request.enabled,
			leaseIdentity = lease.identity,
			reconciliationAttempt = reconciliationAttempt,
			bootId = boundary.bootId,
			elapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
			wallTimeMs = boundary.wallTimeMs,
		)
		return try {
			when (demand) {
			is AmbientRadioDemandResult.Inactive -> {
				val reason = demand.reason.toPublicReason()
				if (demand.reason.isStaleReconciliation()) {
					AmbientWifiDemandReconciliation.Inactive(
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
				is AmbientWifiRuntimeJoinResult.Inactive -> {
					val compensated = compensateRejectedRuntime(
						demand,
						lease,
						reconciliationAttempt,
					)
					AmbientWifiDemandReconciliation.Inactive(
						AmbientWifiDemandBlockReason.RUNTIME_JOIN_RETIRED,
						runtime.providerKey,
						compensated,
						demand.demand.demandId,
					)
				}
				is AmbientWifiRuntimeJoinResult.Active ->
					AmbientWifiDemandReconciliation.Active(
						demand.demand.demandId,
						demand.authorityRevision,
						demand.reconciliationAuthority,
						runtime.sourceInstanceId,
						runtime.registrationGeneration,
					)
				is AmbientWifiRuntimeJoinResult.Degraded ->
					AmbientWifiDemandReconciliation.Degraded(
						demand.demand.demandId,
						demand.authorityRevision,
						demand.reconciliationAuthority,
						runtime.sourceInstanceId,
						runtime.registrationGeneration,
						runtime.reasons,
					)
				is AmbientWifiRuntimeJoinResult.Unavailable -> {
					val compensated = compensateRejectedRuntime(
						demand,
						lease,
						reconciliationAttempt,
					)
					AmbientWifiDemandReconciliation.Unavailable(
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
			compensateFailedReconciliation(demand, lease, reconciliationAttempt, cancelled)
			throw cancelled
		} catch (failure: Exception) {
			compensateFailedReconciliation(demand, lease, reconciliationAttempt, failure)
			throw failure
		}
	}

	private suspend fun compensateFailedReconciliation(
		demand: AmbientRadioDemandResult,
		lease: AmbientReconciliationLease,
		reconciliationAttempt: Long,
		failure: Exception,
	) {
		val active = demand as? AmbientRadioDemandResult.Active ?: return
		withContext(NonCancellable) {
			val compensated = sourceBroker.compensateAmbientWifiDemandUnderHeldLease(
				CONSUMER_ID,
				lease.identity,
				reconciliationAttempt,
				active.demand.demandId,
				clockDomainProvider.current(),
				Time.elapsedRealtimeNanos,
				Time.nowMillis,
			)
			if (compensated == null) {
				failure.addSuppressed(
					IllegalStateException("Unable to compensate exact Ambient Wi-Fi demand"),
				)
				return@withContext
			}

			private suspend fun compensateRejectedRuntime(
				active: AmbientRadioDemandResult.Active,
				lease: AmbientReconciliationLease,
				reconciliationAttempt: Long,
			): AmbientRadioReconciliationAuthority {
				val compensated = requireNotNull(
					sourceBroker.compensateAmbientWifiDemandUnderHeldLease(
						CONSUMER_ID,
						lease.identity,
						reconciliationAttempt,
						active.demand.demandId,
						clockDomainProvider.current(),
						Time.elapsedRealtimeNanos,
						Time.nowMillis,
					),
				) { "Unable to compensate rejected Ambient Wi-Fi runtime reconciliation" }
				sharedController.reconcileAmbientJoin()
				return compensated
			}
			runCatchingNonCancellation {
				sharedController.reconcileAmbientJoin()
			}.exceptionOrNull()?.let(failure::addSuppressed)
		}
	}

	suspend fun reconcilePurposeAvailability(
		lease: AmbientReconciliationLease,
		request: AmbientWifiActivationRequest,
	): AmbientRadioReportPreparation {
		if (lease.identity.source != AmbientTrackingSource.WIFI) {
			return AmbientRadioReportPreparation.Rejected(
				evidence = null,
				reason = AmbientRadioReportPreparationRejection.SOURCE_MISMATCH,
			)
		}
		return reconcile(lease, request).prepareReport(lease)
	}

	private companion object {
		const val CONSUMER_ID = "app:ambient:wifi"
	}
}

data class AmbientWifiOwnerReconciliation(
	val outcome: AmbientWifiDemandReconciliation,
	val evidence: AmbientRadioReconciliationEvidence,
) {
	init {
		require(evidence.source == AmbientTrackingSource.WIFI)
		outcome.authorityRevisionOrNull()?.let { revision ->
			require(evidence.authorityRevision == revision)
		}
	}

	fun prepareReport(
		lease: AmbientReconciliationLease,
	): AmbientRadioReportPreparation = prepareAmbientRadioReport(
		lease,
		evidence,
		outcome.toOperationalAvailability(),
	)
}

private data class AmbientWifiDemandBoundary(
	val bootId: String,
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
)

sealed interface AmbientWifiDemandReconciliation {
	data class Active(
		val demandId: String,
		val authorityRevision: Long,
		val reconciliationAuthority: AmbientRadioReconciliationAuthority,
		val sourceInstanceId: SourceInstanceId?,
		val registrationGeneration: Long?,
	) : AmbientWifiDemandReconciliation {
		init {
			require(reconciliationAuthority.source == SourceKind.WIFI)
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
	) : AmbientWifiDemandReconciliation {
		init {
			require(reconciliationAuthority.source == SourceKind.WIFI)
			require(reconciliationAuthority.authorityRevision == authorityRevision)
		}
	}

	data class Inactive(
		val reason: AmbientWifiDemandBlockReason,
		val providerKey: com.adsamcik.tracker.tracker.source.runtime.SourceProviderKey? = null,
		val reconciliationAuthority: AmbientRadioReconciliationAuthority? = null,
		val demandId: String? = null,
	) : AmbientWifiDemandReconciliation {
		init {
			require(reconciliationAuthority?.source?.let { it == SourceKind.WIFI } != false)
			require(demandId == null || demandId.isNotBlank())
		}
	}

	data class Unavailable(
		val reasons: Set<SourceDegradedReason>,
		val retryable: Boolean,
		val providerKey: com.adsamcik.tracker.tracker.source.runtime.SourceProviderKey? = null,
		val reconciliationAuthority: AmbientRadioReconciliationAuthority? = null,
		val demandId: String? = null,
	) : AmbientWifiDemandReconciliation {
		init {
			require(reconciliationAuthority?.source?.let { it == SourceKind.WIFI } != false)
			require(demandId == null || demandId.isNotBlank())
		}
	}
}

enum class AmbientWifiDemandBlockReason {
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
}

private fun AmbientRadioDemandInactiveReason.toPublicReason(): AmbientWifiDemandBlockReason =
	when (this) {
		AmbientRadioDemandInactiveReason.REQUEST_DISABLED ->
			AmbientWifiDemandBlockReason.REQUEST_DISABLED
		AmbientRadioDemandInactiveReason.AUTHORITY_INACTIVE ->
			AmbientWifiDemandBlockReason.AUTHORITY_INACTIVE
		AmbientRadioDemandInactiveReason.POLICY_MISSING ->
			AmbientWifiDemandBlockReason.POLICY_MISSING
		AmbientRadioDemandInactiveReason.CONSENT_REVOKED ->
			AmbientWifiDemandBlockReason.CONSENT_REVOKED
		AmbientRadioDemandInactiveReason.PERSISTENCE_INELIGIBLE ->
			AmbientWifiDemandBlockReason.PERSISTENCE_INELIGIBLE
		AmbientRadioDemandInactiveReason.ROLLOUT_CONTAINED ->
			AmbientWifiDemandBlockReason.ROLLOUT_CONTAINED
		AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISSING ->
			AmbientWifiDemandBlockReason.RETENTION_APPROVAL_MISSING
		AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISMATCH ->
			AmbientWifiDemandBlockReason.RETENTION_APPROVAL_MISMATCH
		AmbientRadioDemandInactiveReason.SOURCE_EVIDENCE_STATE_MISSING ->
			AmbientWifiDemandBlockReason.SOURCE_EVIDENCE_STATE_MISSING
		AmbientRadioDemandInactiveReason.AUTHORITY_REVISION_EXHAUSTED ->
			AmbientWifiDemandBlockReason.AUTHORITY_REVISION_EXHAUSTED
		AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE ->
			AmbientWifiDemandBlockReason.STALE_RECONCILIATION_LEASE
		AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_ATTEMPT ->
			AmbientWifiDemandBlockReason.STALE_RECONCILIATION_ATTEMPT
		AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT ->
			AmbientWifiDemandBlockReason.OWNERSHIP_CONFLICT
		AmbientRadioDemandInactiveReason.DELETION_AUTHORITY_MISMATCH ->
			AmbientWifiDemandBlockReason.DELETION_AUTHORITY_MISMATCH
	}

private fun AmbientRadioDemandInactiveReason.isStaleReconciliation(): Boolean =
	this == AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE ||
		this == AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_ATTEMPT ||
		this == AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT

private fun AmbientWifiDemandBlockReason.afterAmbientJoinRetirement(
	runtime: AmbientWifiRuntimeJoinResult,
	reconciliationAuthority: AmbientRadioReconciliationAuthority?,
	demandId: String?,
): AmbientWifiDemandReconciliation = when (runtime) {
	is AmbientWifiRuntimeJoinResult.Unavailable ->
		AmbientWifiDemandReconciliation.Unavailable(
			runtime.reasons,
			runtime.retryable,
			runtime.providerKey,
			reconciliationAuthority,
			demandId,
		)
	is AmbientWifiRuntimeJoinResult.Inactive ->
		AmbientWifiDemandReconciliation.Inactive(
			this,
			runtime.providerKey,
			reconciliationAuthority,
			demandId,
		)
	is AmbientWifiRuntimeJoinResult.Active ->
		AmbientWifiDemandReconciliation.Inactive(
			this,
			runtime.providerKeyOrNull(),
			reconciliationAuthority,
			demandId,
		)
	is AmbientWifiRuntimeJoinResult.Degraded ->
		AmbientWifiDemandReconciliation.Inactive(
			this,
			runtime.providerKeyOrNull(),
			reconciliationAuthority,
			demandId,
		)
}

fun AmbientWifiDemandReconciliation.toOperationalAvailability():
	AmbientSourceOperationalAvailability = when (this) {
	is AmbientWifiDemandReconciliation.Active -> if (
		sourceInstanceId != null && registrationGeneration != null
	) {
		ambientWifiAvailability(AmbientSourceOperationalState.READY)
	} else {
		ambientWifiProviderUnavailable()
	}
	is AmbientWifiDemandReconciliation.Degraded -> if (
		sourceInstanceId == null || registrationGeneration == null
	) {
		ambientWifiProviderUnavailable()
	} else if (SourceDegradedReason.PERMISSION_MISSING in reasons) {
		ambientWifiAvailability(
			state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
			reason = AmbientSourceUnavailableReason.WIFI_SCAN_PERMISSION_REQUIRED,
		)
	} else if (reasons.any { it in NONOPERATIONAL_PLATFORM_REASONS }) {
		ambientWifiAvailability(
			state = AmbientSourceOperationalState.UNAVAILABLE,
			reason = AmbientSourceUnavailableReason.PLATFORM_UNAVAILABLE,
		)
	} else {
		ambientWifiAvailability(
			state = AmbientSourceOperationalState.UNAVAILABLE,
			reason = reasons.toAmbientWifiReason(),
		)
	}
	is AmbientWifiDemandReconciliation.Inactive -> when (reason) {
		AmbientWifiDemandBlockReason.RETENTION_APPROVAL_MISSING,
		AmbientWifiDemandBlockReason.RETENTION_APPROVAL_MISMATCH,
		-> ambientWifiRetentionUnavailable()
		AmbientWifiDemandBlockReason.ROLLOUT_CONTAINED -> ambientWifiAvailability(
			state = AmbientSourceOperationalState.UNAVAILABLE,
			reason = AmbientSourceUnavailableReason.ROLLOUT_CONTAINED,
		)
		AmbientWifiDemandBlockReason.AUTHORITY_INACTIVE,
		AmbientWifiDemandBlockReason.POLICY_MISSING,
		AmbientWifiDemandBlockReason.REQUEST_DISABLED,
		AmbientWifiDemandBlockReason.CONSENT_REVOKED,
		AmbientWifiDemandBlockReason.PERSISTENCE_INELIGIBLE,
		AmbientWifiDemandBlockReason.SOURCE_EVIDENCE_STATE_MISSING,
		AmbientWifiDemandBlockReason.AUTHORITY_REVISION_EXHAUSTED,
		AmbientWifiDemandBlockReason.STALE_RECONCILIATION_LEASE,
		AmbientWifiDemandBlockReason.STALE_RECONCILIATION_ATTEMPT,
		AmbientWifiDemandBlockReason.OWNERSHIP_CONFLICT,
		AmbientWifiDemandBlockReason.DELETION_AUTHORITY_MISMATCH,
		-> AmbientSourceOperationalAvailability.reconciliationPending(AmbientTrackingSource.WIFI)
		AmbientWifiDemandBlockReason.RUNTIME_JOIN_RETIRED -> ambientWifiProviderUnavailable()
	}
	is AmbientWifiDemandReconciliation.Unavailable -> reasons.toAmbientWifiUnavailable()
}

private fun Set<SourceDegradedReason>.toAmbientWifiUnavailable():
	AmbientSourceOperationalAvailability {
	if (SourceDegradedReason.PERMISSION_MISSING in this) {
		return ambientWifiAvailability(
			state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
			reason = AmbientSourceUnavailableReason.WIFI_SCAN_PERMISSION_REQUIRED,
		)
	}
	if (isEmpty()) return ambientWifiProviderUnavailable()
	return ambientWifiAvailability(
		state = AmbientSourceOperationalState.UNAVAILABLE,
		reason = toAmbientWifiReason(),
	)
}

private fun Set<SourceDegradedReason>.toAmbientWifiReason(): AmbientSourceUnavailableReason =
	when {
		SourceDegradedReason.PERMISSION_MISSING in this ->
			AmbientSourceUnavailableReason.WIFI_SCAN_PERMISSION_REQUIRED
		any { reason -> reason in PLATFORM_RADIO_REASONS } ->
			AmbientSourceUnavailableReason.PLATFORM_UNAVAILABLE
		else -> AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE
	}

private fun ambientWifiProviderUnavailable() = ambientWifiAvailability(
	state = AmbientSourceOperationalState.UNAVAILABLE,
	reason = AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
)

private fun ambientWifiRetentionUnavailable() = ambientWifiAvailability(
	state = AmbientSourceOperationalState.UNAVAILABLE,
	reason = AmbientSourceUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
)

private fun ambientWifiAvailability(
	state: AmbientSourceOperationalState,
	reason: AmbientSourceUnavailableReason? = null,
) = AmbientSourceOperationalAvailability(
	source = AmbientTrackingSource.WIFI,
	state = state,
	mechanism = when (state) {
		AmbientSourceOperationalState.READY,
		AmbientSourceOperationalState.PERMISSION_REQUIRED,
		-> AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS
		AmbientSourceOperationalState.UNAVAILABLE -> null
		AmbientSourceOperationalState.WAITING -> null
		AmbientSourceOperationalState.DEGRADED ->
			error("The purpose matrix does not permit degraded Ambient Wi-Fi")
	},
	reason = reason,
)

private val PLATFORM_RADIO_REASONS = setOf(
	SourceDegradedReason.HARDWARE_UNAVAILABLE,
	SourceDegradedReason.BACKGROUND_START_ILLEGAL,
	SourceDegradedReason.FOREGROUND_CAPABILITY_MISSING,
	SourceDegradedReason.POWER_SAVER,
	SourceDegradedReason.THERMAL,
	SourceDegradedReason.DOZE,
	SourceDegradedReason.PLATFORM_THROTTLED,
)

private val NONOPERATIONAL_PLATFORM_REASONS = setOf(
	SourceDegradedReason.HARDWARE_UNAVAILABLE,
	SourceDegradedReason.BACKGROUND_START_ILLEGAL,
	SourceDegradedReason.FOREGROUND_CAPABILITY_MISSING,
)

private fun AmbientWifiDemandReconciliation.demandIdOrNull(): String? = when (this) {
	is AmbientWifiDemandReconciliation.Active -> demandId
	is AmbientWifiDemandReconciliation.Degraded -> demandId
	is AmbientWifiDemandReconciliation.Inactive -> demandId
	is AmbientWifiDemandReconciliation.Unavailable -> demandId
}

private fun AmbientWifiDemandReconciliation.sourceInstanceIdOrNull(): SourceInstanceId? =
	when (this) {
		is AmbientWifiDemandReconciliation.Active -> sourceInstanceId
		is AmbientWifiDemandReconciliation.Degraded -> sourceInstanceId
		is AmbientWifiDemandReconciliation.Inactive -> providerKey?.sourceInstanceId
		is AmbientWifiDemandReconciliation.Unavailable -> providerKey?.sourceInstanceId
	}

private fun AmbientWifiDemandReconciliation.registrationGenerationOrNull(): Long? =
	when (this) {
		is AmbientWifiDemandReconciliation.Active -> registrationGeneration
		is AmbientWifiDemandReconciliation.Degraded -> registrationGeneration
		is AmbientWifiDemandReconciliation.Inactive -> providerKey?.registrationGeneration
		is AmbientWifiDemandReconciliation.Unavailable -> providerKey?.registrationGeneration
	}

private fun AmbientWifiDemandReconciliation.reconciliationAuthorityOrNull():
	AmbientRadioReconciliationAuthority? = when (this) {
	is AmbientWifiDemandReconciliation.Active -> reconciliationAuthority
	is AmbientWifiDemandReconciliation.Degraded -> reconciliationAuthority
	is AmbientWifiDemandReconciliation.Inactive -> reconciliationAuthority
	is AmbientWifiDemandReconciliation.Unavailable -> reconciliationAuthority
}

private fun AmbientWifiDemandReconciliation.authorityRevisionOrNull(): Long? = when (this) {
	is AmbientWifiDemandReconciliation.Active -> authorityRevision
	is AmbientWifiDemandReconciliation.Degraded -> authorityRevision
	is AmbientWifiDemandReconciliation.Inactive -> reconciliationAuthority?.authorityRevision
	is AmbientWifiDemandReconciliation.Unavailable -> reconciliationAuthority?.authorityRevision
}

private fun AmbientWifiRuntimeJoinResult.Active.providerKeyOrNull() =
	if (sourceInstanceId != null && registrationGeneration != null) {
		com.adsamcik.tracker.tracker.source.runtime.SourceProviderKey(
			sourceInstanceId,
			registrationGeneration,
		)
	} else {
		null
	}

private fun AmbientWifiRuntimeJoinResult.Degraded.providerKeyOrNull() =
	if (sourceInstanceId != null && registrationGeneration != null) {
		com.adsamcik.tracker.tracker.source.runtime.SourceProviderKey(
			sourceInstanceId,
			registrationGeneration,
		)
	} else {
		null
	}
