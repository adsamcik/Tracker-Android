package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerAcceptanceReceipt
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerGuardRejection
import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerRejectionReason
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.source.model.SourceKind

internal class TestPurposeSourceCallerDemandDispatcher(
	private val broker: SourceBroker,
	private val currentAuthority: suspend (
		com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity,
	) -> Boolean = { true },
	private val retentionSnapshot: suspend (
		SourceKind,
		AmbientReconciliationIdentity,
		String,
		Long,
		Long,
	) -> LiveAmbientRetentionSnapshot? = { _, _, _, _, _ -> null },
) : SourceCallerDemandDispatcher {
	override suspend fun dispatchSession(
		request: SessionSourceDemandDispatchRequest,
	): SessionSourceDemandDispatchResult = SessionSourceDemandDispatchResult.Rejected(
		SourceCallerGuardRejection(SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE),
	)

	override suspend fun replayPreparedSession(
		manifestIdentity: SourceCallerManifestIdentity,
		reference: SourceCallerReplayReference,
		replayKind: SourceCallerReplayKind,
	): SourceCallerGuardResult = SourceCallerGuardResult.Rejected(
		SourceCallerGuardRejection(SourceCallerRejectionReason.REPLAY_AUTHORITY_UNAVAILABLE),
	)

	override suspend fun authenticatePreparedSession(
		manifestIdentity: SourceCallerManifestIdentity,
		reference: SourceCallerReplayReference,
		replayKind: SourceCallerReplayKind,
	): SourceCallerGuardResult = replayPreparedSession(manifestIdentity, reference, replayKind)

	override suspend fun dispatchAutomaticControl(
		request: AutomaticControlDemandDispatchRequest,
	): GuardedPurposeDemandResult<com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity> {
		if (!currentAuthority(request.identity)) {
			return GuardedPurposeDemandResult.Rejected(
				SourceCallerGuardRejection(
					SourceCallerRejectionReason.READINESS_AUTHORITY_MISMATCH,
				),
			)
		}

		val demand = broker.replaceAutomaticControlDemand(
			consumerId = request.consumerId,
			source = SourceKind.ACTIVITY,
			enabled = true,
			bootId = request.bootId,
			elapsedRealtimeNanos = request.elapsedRealtimeNanos,
			wallTimeMs = request.wallTimeMs,
			maximumAgeMs = request.maximumAgeMs,
			desiredLatencyMs = request.desiredLatencyMs,
		) ?: return GuardedPurposeDemandResult.Rejected(
			SourceCallerGuardRejection(SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE),
		)
		return GuardedPurposeDemandResult.Applied(demand, receipt(request.identity))
	}

	override suspend fun retireAutomaticControl(
		consumerId: String,
		source: SourceKind,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		maximumAgeMs: Long,
		desiredLatencyMs: Long,
	): GuardedPurposeDemandResult<Unit> {
		broker.replaceAutomaticControlDemand(
			consumerId,
			source,
			false,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
			maximumAgeMs,
			desiredLatencyMs,
		)
		return GuardedPurposeDemandResult.Applied(Unit, null)
	}

	override suspend fun dispatchAmbientSteps(
		request: AmbientStepsDemandDispatchRequest,
	): GuardedPurposeDemandResult<AmbientStepsDemandResult> {
		if (!currentAuthority(request.identity.purposeLeaseIdentity)) {
			return GuardedPurposeDemandResult.Rejected(
				SourceCallerGuardRejection(
					SourceCallerRejectionReason.READINESS_AUTHORITY_MISMATCH,
				),
			)
		}

		val snapshot = retentionSnapshot(
			SourceKind.STEPS,
			request.identity,
			request.bootId,
			request.elapsedRealtimeNanos,
			request.wallTimeMs,
		)
		val retentionGrant = snapshot?.grants?.get(SourceKind.STEPS)
		if (retentionGrant != null &&
			(retentionGrant.retainedFromMs != request.identity.retainedFromMs ||
				retentionGrant.opaquePolicyId != request.identity.retentionPolicyId ||
				retentionGrant.approvalRevision != request.identity.retentionApprovalRevision)
		) {
			return GuardedPurposeDemandResult.Rejected(
				SourceCallerGuardRejection(
					SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE,
				),
			)
		}
		val result = if (snapshot == null) {
			broker.replaceAmbientStepsDemand(
				request.consumerId,
				request.mechanism,
				request.identity,
				request.bootId,
				request.elapsedRealtimeNanos,
				request.wallTimeMs,
			)
		} else {
			broker.replaceAmbientStepsDemand(
				request.consumerId,
				request.mechanism,
				request.identity,
				request.bootId,
				request.elapsedRealtimeNanos,
				request.wallTimeMs,
				retentionSnapshot = snapshot,
			)
		}
		return GuardedPurposeDemandResult.Applied(
			result,
			receipt(request.identity.purposeLeaseIdentity),
		)
	}

	override suspend fun retireAmbientSteps(
		consumerId: String,
		leaseIdentity: AmbientReconciliationIdentity?,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): GuardedPurposeDemandResult<AmbientStepsDemandResult> {
		val retired = if (leaseIdentity == null) {
			broker.retirePurposeDemand(
				consumerId = consumerId,
				expectedSourceKind = SourceKind.STEPS.stableCode,
				expectedPurpose =
					com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
						.AMBIENT_PRODUCT,
				bootId = bootId,
				elapsedRealtimeNanos = elapsedRealtimeNanos,
				wallTimeMs = wallTimeMs,
			)
		} else {
			broker.retireExactAmbientStepsDemand(
				consumerId = consumerId,
				leaseIdentity = leaseIdentity,
				bootId = bootId,
				elapsedRealtimeNanos = elapsedRealtimeNanos,
				wallTimeMs = wallTimeMs,
			)
		}
		return if (retired) {
			GuardedPurposeDemandResult.Applied(
				AmbientStepsDemandResult.Inactive(
					AmbientStepsDemandInactiveReason.REQUEST_DISABLED,
				),
				null,
			)
		} else {
			GuardedPurposeDemandResult.Stale
		}
	}

	override suspend fun <T> dispatchAmbientRadio(
		request: AmbientRadioDemandDispatchRequest,
		reconcile: suspend (AmbientRadioDemandResult, GuardedAmbientRadioAttempt) -> T,
	): GuardedPurposeDemandResult<T> {
		if (request.requested && !currentAuthority(request.leaseIdentity.purposeLeaseIdentity)) {
			return GuardedPurposeDemandResult.Rejected(
				SourceCallerGuardRejection(
					SourceCallerRejectionReason.READINESS_AUTHORITY_MISMATCH,
				),
			)
		}
		val mutate: suspend (suspend () -> T) -> AmbientRadioLeaseMutation<T> = { operation ->
			if (request.requested) {
				broker.withAmbientRadioMutationLease(request.leaseIdentity, operation)
			} else {
				broker.withAmbientRadioReductionLease(request.leaseIdentity, operation)
			}
		}
		val guarded = mutate {
			val snapshot = retentionSnapshot(
				SourceKind.valueOf(request.source.name),
				request.leaseIdentity.purposeLeaseIdentity,
				request.bootId,
				request.elapsedRealtimeNanos,
				request.wallTimeMs,
			)
			val demand = when (request.source) {
				com.adsamcik.tracker.tracker.api.AmbientTrackingSource.WIFI ->
					if (snapshot == null) {
						broker.replaceAmbientWifiDemandUnderHeldLease(
							request.consumerId,
							request.requested,
							request.leaseIdentity,
							request.reconciliationAttempt,
							request.bootId,
							request.elapsedRealtimeNanos,
							request.wallTimeMs,
						)
					} else {
						broker.replaceAmbientWifiDemandUnderHeldLease(
							request.consumerId,
							request.requested,
							request.leaseIdentity,
							request.reconciliationAttempt,
							request.bootId,
							request.elapsedRealtimeNanos,
							request.wallTimeMs,
							retentionSnapshot = snapshot,
						)
					}

				com.adsamcik.tracker.tracker.api.AmbientTrackingSource.CELL ->
					if (snapshot == null) {
						broker.replaceAmbientCellDemandUnderHeldLease(
							request.consumerId,
							request.requested,
							request.leaseIdentity,
							request.reconciliationAttempt,
							request.bootId,
							request.elapsedRealtimeNanos,
							request.wallTimeMs,
						)
					} else {
						broker.replaceAmbientCellDemandUnderHeldLease(
							request.consumerId,
							request.requested,
							request.leaseIdentity,
							request.reconciliationAttempt,
							request.bootId,
							request.elapsedRealtimeNanos,
							request.wallTimeMs,
							retentionSnapshot = snapshot,
						)
					}

				else -> error("Unsupported test ambient radio source")
			}
			reconcile(demand, GuardedAmbientRadioAttempt(request, null))
		}
		return when (guarded) {
			is AmbientRadioLeaseMutation.Applied ->
				GuardedPurposeDemandResult.Applied(guarded.value, null)
			AmbientRadioLeaseMutation.Stale -> GuardedPurposeDemandResult.Stale
		}
	}

	override suspend fun compensateAmbientRadio(
		attempt: GuardedAmbientRadioAttempt,
		expectedDemandId: String,
	): AmbientRadioReconciliationAuthority? = when (attempt.request.source) {
		com.adsamcik.tracker.tracker.api.AmbientTrackingSource.WIFI ->
			broker.compensateAmbientWifiDemandUnderHeldLease(
				attempt.request.consumerId,
				attempt.request.leaseIdentity,
				attempt.request.reconciliationAttempt,
				expectedDemandId,
				attempt.request.bootId,
				attempt.request.elapsedRealtimeNanos,
				attempt.request.wallTimeMs,
			)
		com.adsamcik.tracker.tracker.api.AmbientTrackingSource.CELL ->
			broker.compensateAmbientCellDemandUnderHeldLease(
				attempt.request.consumerId,
				attempt.request.leaseIdentity,
				attempt.request.reconciliationAttempt,
				expectedDemandId,
				attempt.request.bootId,
				attempt.request.elapsedRealtimeNanos,
				attempt.request.wallTimeMs,
			)
		else -> null
	}

	override suspend fun permitsActivation(
		reference: SourceCallerReplayReference,
		manifestIdentity: SourceCallerManifestIdentity,
		demands: List<com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity>,
	): Boolean = false

	private fun receipt(
		identity: com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity,
	) = SourceCallerAcceptanceReceipt(
		SourceCallerReplayReference(
			"test:${identity.source.name}:${identity.purpose.stableName}:${identity.executionRevision}",
		),
		setOf(SourceCallerDemandIdentity(identity, manifestIdentity = null)),
	)
}

internal object PermissiveTrackingPurposeMutationLeaseGuard :
	TrackingPurposeMutationLeaseGuard {
	override suspend fun <T> mutateIfCurrent(
		identity: AmbientReconciliationIdentity,
		mutation: suspend () -> T,
	): AmbientRadioLeaseMutation<T> = AmbientRadioLeaseMutation.Applied(mutation())

	override suspend fun <T> mutateAutomaticIfCurrent(
		identity: TrackingPurposeLeaseIdentity,
		mutation: suspend () -> T,
	): AmbientRadioLeaseMutation<T> = AmbientRadioLeaseMutation.Applied(mutation())

	override suspend fun <T> mutateAmbientIfCurrent(
		identity: AmbientReconciliationIdentity,
		mutation: suspend () -> T,
	): AmbientRadioLeaseMutation<T> = AmbientRadioLeaseMutation.Applied(mutation())
}
