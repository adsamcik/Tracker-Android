package com.adsamcik.tracker.tracker.source.ambient.wifi

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationResult
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReporter
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioDemandInactiveReason
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioDemandResult
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioRetentionApproval
import com.adsamcik.tracker.tracker.source.runtime.AmbientWifiRuntimeJoinResult
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SharedWifiSourceController
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import javax.inject.Inject
import javax.inject.Singleton

data class AmbientWifiActivationRequest(
	val enabled: Boolean,
	val retentionApproval: AmbientWifiRetentionApproval?,
) {
	init {
		require(enabled || retentionApproval == null) {
			"A disabled Ambient Wi-Fi request cannot retain an activation approval"
		}
	}
}

/** Opaque approval supplied by the parent-owned privacy/retention policy integration. */
data class AmbientWifiRetentionApproval(
	val sourcePolicyRevision: Long,
	val ambientConsentEpoch: Long,
	val opaquePolicyId: String,
	val approvalRevision: Long,
) {
	init {
		require(sourcePolicyRevision > 0L && ambientConsentEpoch >= 0L)
		require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= 256)
		require(approvalRevision > 0L)
	}
}

/**
 * Explicit default-off entry point. Policy and retention are checked before the shared controller
 * can read platform capability or touch the provider.
 */
@Singleton
class AmbientWifiDemandReconciler @Inject constructor(
	private val sourcePolicyRepository: SourcePolicyRepository,
	private val trackingRolloutStateStore: TrackingRolloutStateStore,
	private val sourceBroker: SourceBroker,
	private val sharedController: SharedWifiSourceController,
	private val clockDomainProvider: BootClockDomainProvider,
	private val availabilityReporter: TrackingPurposeAvailabilityReporter,
) {
	suspend fun reconcile(
		request: AmbientWifiActivationRequest,
	): AmbientWifiDemandReconciliation {
		val policyBlock = policyBlockReason(request)
		val boundary = AmbientWifiDemandBoundary(
			clockDomainProvider.current(),
			Time.elapsedRealtimeNanos,
			Time.nowMillis,
		)
		if (policyBlock != null) {
			sourceBroker.replaceAmbientWifiDemand(
				consumerId = CONSUMER_ID,
				requested = false,
				retentionApproval = null,
				bootId = boundary.bootId,
				elapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
				wallTimeMs = boundary.wallTimeMs,
			)
			return report(
				policyBlock.afterAmbientJoinRetirement(
					sharedController.reconcileAmbientJoin(),
				),
			)
		}
		val approval = requireNotNull(request.retentionApproval)
		val result = when (val demand = sourceBroker.replaceAmbientWifiDemand(
			consumerId = CONSUMER_ID,
			requested = true,
			retentionApproval = approval.toRuntimeApproval(),
			bootId = boundary.bootId,
			elapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
			wallTimeMs = boundary.wallTimeMs,
		)) {
			is AmbientRadioDemandResult.Inactive ->
				demand.reason.toPublicReason().afterAmbientJoinRetirement(
					sharedController.reconcileAmbientJoin(),
				)
			is AmbientRadioDemandResult.Active -> when (val runtime =
				sharedController.reconcileAmbientJoin()
			) {
				AmbientWifiRuntimeJoinResult.Inactive ->
					AmbientWifiDemandReconciliation.Inactive(
						AmbientWifiDemandBlockReason.RUNTIME_JOIN_RETIRED,
					)
				is AmbientWifiRuntimeJoinResult.Active ->
					AmbientWifiDemandReconciliation.Active(
						demand.demand.demandId,
						demand.authorityRevision,
						runtime.sourceInstanceId,
						runtime.registrationGeneration,
					)
				is AmbientWifiRuntimeJoinResult.Degraded ->
					AmbientWifiDemandReconciliation.Degraded(
						demand.demand.demandId,
						demand.authorityRevision,
						runtime.sourceInstanceId,
						runtime.registrationGeneration,
						runtime.reasons,
					)
				is AmbientWifiRuntimeJoinResult.Unavailable ->
					AmbientWifiDemandReconciliation.Unavailable(
						runtime.reasons,
						runtime.retryable,
					)
			}
		}
		return report(result)
	}

	suspend fun reconcilePurposeAvailability(
		request: AmbientWifiActivationRequest,
	): AmbientSourceReconciliationResult = reconcile(request).toPurposeAvailabilityResult()

	private fun report(
		result: AmbientWifiDemandReconciliation,
	): AmbientWifiDemandReconciliation = result.also {
		availabilityReporter.reportAmbientSource(it.toOperationalAvailability())
	}

	private suspend fun policyBlockReason(
		request: AmbientWifiActivationRequest,
	): AmbientWifiDemandBlockReason? {
		if (!request.enabled) return AmbientWifiDemandBlockReason.REQUEST_DISABLED
		val authority = sourcePolicyRepository.currentState()
		if (authority !is SourcePolicyAuthorityState.Active) {
			return AmbientWifiDemandBlockReason.AUTHORITY_INACTIVE
		}
		val policy = authority.snapshot[TrackingSourceComponent.WIFI]
		if (policy.ambientConsentEpoch == null) {
			return AmbientWifiDemandBlockReason.CONSENT_REVOKED
		}
		if (!policy.ambientPersistenceEligible) {
			return AmbientWifiDemandBlockReason.PERSISTENCE_INELIGIBLE
		}
		val approval = request.retentionApproval
			?: return AmbientWifiDemandBlockReason.RETENTION_APPROVAL_MISSING
		if (approval.sourcePolicyRevision != policy.policyRevision ||
			approval.ambientConsentEpoch != policy.ambientConsentEpoch
		) {
			return AmbientWifiDemandBlockReason.RETENTION_APPROVAL_MISMATCH
		}
		if (!trackingRolloutStateStore.load().isCaptureReachable(
				SourceKind.WIFI,
				CaptureReachabilityMode.AMBIENT,
			)
		) {
			return AmbientWifiDemandBlockReason.ROLLOUT_CONTAINED
		}
		return null
	}

	private companion object {
		const val CONSUMER_ID = "app:ambient:wifi"
	}
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
		val sourceInstanceId: SourceInstanceId?,
		val registrationGeneration: Long?,
	) : AmbientWifiDemandReconciliation

	data class Degraded(
		val demandId: String,
		val authorityRevision: Long,
		val sourceInstanceId: SourceInstanceId?,
		val registrationGeneration: Long?,
		val reasons: Set<SourceDegradedReason>,
	) : AmbientWifiDemandReconciliation

	data class Inactive(val reason: AmbientWifiDemandBlockReason) :
		AmbientWifiDemandReconciliation

	data class Unavailable(
		val reasons: Set<SourceDegradedReason>,
		val retryable: Boolean,
	) : AmbientWifiDemandReconciliation
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
	RUNTIME_JOIN_RETIRED,
}

private fun AmbientWifiRetentionApproval.toRuntimeApproval() = AmbientRadioRetentionApproval(
	SourceKind.WIFI,
	sourcePolicyRevision,
	ambientConsentEpoch,
	opaquePolicyId,
	approvalRevision,
)

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
	}

private fun AmbientWifiDemandBlockReason.afterAmbientJoinRetirement(
	runtime: AmbientWifiRuntimeJoinResult,
): AmbientWifiDemandReconciliation = when (runtime) {
	is AmbientWifiRuntimeJoinResult.Unavailable ->
		AmbientWifiDemandReconciliation.Unavailable(runtime.reasons, runtime.retryable)
	else -> AmbientWifiDemandReconciliation.Inactive(this)
}

fun AmbientWifiDemandReconciliation.toPurposeAvailabilityResult():
	AmbientSourceReconciliationResult {
	val availability = toOperationalAvailability()
	return if (availability.isOperational) {
		AmbientSourceReconciliationResult.Reconciled(availability)
	} else {
		AmbientSourceReconciliationResult.Unavailable(availability)
	}
}

fun AmbientWifiDemandReconciliation.toOperationalAvailability():
	AmbientSourceOperationalAvailability = when (this) {
	is AmbientWifiDemandReconciliation.Active -> if (
		sourceInstanceId != null && registrationGeneration != null
	) {
		ambientWifiAvailability(AmbientSourceOperationalState.READY)
	} else {
		ambientWifiWaiting()
	}
	is AmbientWifiDemandReconciliation.Degraded -> if (
		sourceInstanceId == null || registrationGeneration == null
	) {
		ambientWifiWaiting()
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
			state = AmbientSourceOperationalState.DEGRADED,
			reason = reasons.toAmbientWifiReason(),
		)
	}
	is AmbientWifiDemandReconciliation.Inactive -> when (reason) {
		AmbientWifiDemandBlockReason.REQUEST_DISABLED,
		AmbientWifiDemandBlockReason.CONSENT_REVOKED,
		AmbientWifiDemandBlockReason.PERSISTENCE_INELIGIBLE,
		AmbientWifiDemandBlockReason.RETENTION_APPROVAL_MISSING,
		AmbientWifiDemandBlockReason.RETENTION_APPROVAL_MISMATCH,
		-> AmbientSourceOperationalAvailability.retentionPolicyUnavailable(
			AmbientTrackingSource.WIFI,
		)
		AmbientWifiDemandBlockReason.ROLLOUT_CONTAINED -> ambientWifiAvailability(
			state = AmbientSourceOperationalState.UNAVAILABLE,
			reason = AmbientSourceUnavailableReason.ROLLOUT_CONTAINED,
		)
		AmbientWifiDemandBlockReason.AUTHORITY_INACTIVE,
		AmbientWifiDemandBlockReason.POLICY_MISSING,
		AmbientWifiDemandBlockReason.SOURCE_EVIDENCE_STATE_MISSING,
		AmbientWifiDemandBlockReason.AUTHORITY_REVISION_EXHAUSTED,
		AmbientWifiDemandBlockReason.RUNTIME_JOIN_RETIRED,
		-> ambientWifiWaiting()
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
	if (isEmpty()) return ambientWifiWaiting()
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

private fun ambientWifiWaiting() = ambientWifiAvailability(
	state = AmbientSourceOperationalState.WAITING,
	reason = AmbientSourceUnavailableReason.RECONCILIATION_PENDING,
)

private fun ambientWifiAvailability(
	state: AmbientSourceOperationalState,
	reason: AmbientSourceUnavailableReason? = null,
) = AmbientSourceOperationalAvailability(
	source = AmbientTrackingSource.WIFI,
	state = state,
	mechanism = AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS,
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
