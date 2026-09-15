package com.adsamcik.tracker.tracker.source.ambient.cell

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.AmbientCellRuntimeJoinResult
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioDemandInactiveReason
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioDemandResult
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioRetentionApproval
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SharedCellSourceController
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import javax.inject.Inject
import javax.inject.Singleton

data class AmbientCellActivationRequest(
	val enabled: Boolean,
	val retentionApproval: AmbientCellRetentionApproval?,
) {
	init {
		require(enabled || retentionApproval == null)
	}
}

data class AmbientCellRetentionApproval(
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

/** Default-off Cell gate. No subscription or Telephony API is read until every policy check wins. */
@Singleton
class AmbientCellDemandReconciler @Inject constructor(
	private val sourcePolicyRepository: SourcePolicyRepository,
	private val trackingRolloutStateStore: TrackingRolloutStateStore,
	private val sourceBroker: SourceBroker,
	private val sharedController: SharedCellSourceController,
	private val clockDomainProvider: BootClockDomainProvider,
) {
	suspend fun reconcile(
		request: AmbientCellActivationRequest,
	): AmbientCellDemandReconciliation {
		val policyBlock = policyBlockReason(request)
		val bootId = clockDomainProvider.current()
		val elapsedRealtimeNanos = Time.elapsedRealtimeNanos
		val wallTimeMs = Time.nowMillis
		if (policyBlock != null) {
			sourceBroker.replaceAmbientCellDemand(
				consumerId = CONSUMER_ID,
				requested = false,
				retentionApproval = null,
				bootId = bootId,
				elapsedRealtimeNanos = elapsedRealtimeNanos,
				wallTimeMs = wallTimeMs,
			)
			sharedController.reconcileAmbientJoin()
			return AmbientCellDemandReconciliation.Inactive(policyBlock)
		}
		val approval = requireNotNull(request.retentionApproval)
		return when (val demand = sourceBroker.replaceAmbientCellDemand(
			consumerId = CONSUMER_ID,
			requested = true,
			retentionApproval = approval.toRuntimeApproval(),
			bootId = bootId,
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			wallTimeMs = wallTimeMs,
		)) {
			is AmbientRadioDemandResult.Inactive ->
				AmbientCellDemandReconciliation.Inactive(demand.reason.toPublicReason())
			is AmbientRadioDemandResult.Active -> when (val runtime =
				sharedController.reconcileAmbientJoin()
			) {
				AmbientCellRuntimeJoinResult.Inactive ->
					AmbientCellDemandReconciliation.Inactive(
						AmbientCellDemandBlockReason.RUNTIME_JOIN_RETIRED,
					)
				is AmbientCellRuntimeJoinResult.Active ->
					AmbientCellDemandReconciliation.Active(
						demand.demand.demandId,
						demand.authorityRevision,
						runtime.registrationGeneration,
					)
				is AmbientCellRuntimeJoinResult.Degraded ->
					AmbientCellDemandReconciliation.Degraded(
						demand.demand.demandId,
						demand.authorityRevision,
						runtime.reasons,
					)
				is AmbientCellRuntimeJoinResult.Unavailable ->
					AmbientCellDemandReconciliation.Unavailable(
						runtime.reasons,
						runtime.retryable,
					)
			}
		}
	}

	private suspend fun policyBlockReason(
		request: AmbientCellActivationRequest,
	): AmbientCellDemandBlockReason? {
		if (!request.enabled) return AmbientCellDemandBlockReason.REQUEST_DISABLED
		val authority = sourcePolicyRepository.currentState()
		if (authority !is SourcePolicyAuthorityState.Active) {
			return AmbientCellDemandBlockReason.AUTHORITY_INACTIVE
		}
		val policy = authority.snapshot[TrackingSourceComponent.CELL]
		if (policy.ambientConsentEpoch == null) {
			return AmbientCellDemandBlockReason.CONSENT_REVOKED
		}
		if (!policy.ambientPersistenceEligible) {
			return AmbientCellDemandBlockReason.PERSISTENCE_INELIGIBLE
		}
		val approval = request.retentionApproval
			?: return AmbientCellDemandBlockReason.RETENTION_APPROVAL_MISSING
		if (approval.sourcePolicyRevision != policy.policyRevision ||
			approval.ambientConsentEpoch != policy.ambientConsentEpoch
		) {
			return AmbientCellDemandBlockReason.RETENTION_APPROVAL_MISMATCH
		}
		if (!trackingRolloutStateStore.load().isCaptureReachable(
				SourceKind.CELL,
				CaptureReachabilityMode.AMBIENT,
			)
		) {
			return AmbientCellDemandBlockReason.ROLLOUT_CONTAINED
		}
		return null
	}

	private companion object {
		const val CONSUMER_ID = "app:ambient:cell"
	}
}

sealed interface AmbientCellDemandReconciliation {
	data class Active(
		val demandId: String,
		val authorityRevision: Long,
		val registrationGeneration: Long?,
	) : AmbientCellDemandReconciliation
	data class Degraded(
		val demandId: String,
		val authorityRevision: Long,
		val reasons: Set<SourceDegradedReason>,
	) : AmbientCellDemandReconciliation
	data class Inactive(val reason: AmbientCellDemandBlockReason) :
		AmbientCellDemandReconciliation
	data class Unavailable(
		val reasons: Set<SourceDegradedReason>,
		val retryable: Boolean,
	) : AmbientCellDemandReconciliation
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
	RUNTIME_JOIN_RETIRED,
}

private fun AmbientCellRetentionApproval.toRuntimeApproval() = AmbientRadioRetentionApproval(
	SourceKind.CELL,
	sourcePolicyRevision,
	ambientConsentEpoch,
	opaquePolicyId,
	approvalRevision,
)

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
	}
