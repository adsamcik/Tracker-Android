package com.adsamcik.tracker.tracker.source.ambient.wifi

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
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
			sharedController.reconcileAmbientJoin()
			return AmbientWifiDemandReconciliation.Inactive(policyBlock)
		}
		val approval = requireNotNull(request.retentionApproval)
		return when (val demand = sourceBroker.replaceAmbientWifiDemand(
			consumerId = CONSUMER_ID,
			requested = true,
			retentionApproval = approval.toRuntimeApproval(),
			bootId = boundary.bootId,
			elapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
			wallTimeMs = boundary.wallTimeMs,
		)) {
			is AmbientRadioDemandResult.Inactive ->
				AmbientWifiDemandReconciliation.Inactive(demand.reason.toPublicReason())
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
						runtime.registrationGeneration,
					)
				is AmbientWifiRuntimeJoinResult.Degraded ->
					AmbientWifiDemandReconciliation.Degraded(
						demand.demand.demandId,
						demand.authorityRevision,
						runtime.reasons,
					)
				is AmbientWifiRuntimeJoinResult.Unavailable ->
					AmbientWifiDemandReconciliation.Unavailable(
						runtime.reasons,
						runtime.retryable,
					)
			}
		}
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
		val registrationGeneration: Long?,
	) : AmbientWifiDemandReconciliation

	data class Degraded(
		val demandId: String,
		val authorityRevision: Long,
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
