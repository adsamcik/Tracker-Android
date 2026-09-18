package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsDemandBlockReason
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsDemandReconciliation
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProvider
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProviderLifecycleOwner
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProviderRegistrationResult
import javax.inject.Inject
import javax.inject.Singleton

internal interface AmbientStepsPurposeOwner {
	suspend fun reconcile(lease: AmbientReconciliationLease): AmbientSourceOperationalAvailability

	suspend fun retireAfterRetentionAuthorityFailure(
		previousLease: AmbientReconciliationLease?,
	): Boolean
}

@Singleton
internal class DefaultAmbientStepsPurposeOwner @Inject constructor(
	private val lifecycleOwner: AmbientStepsProviderLifecycleOwner,
) : AmbientStepsPurposeOwner {
	override suspend fun reconcile(
		lease: AmbientReconciliationLease,
	): AmbientSourceOperationalAvailability {
		require(lease.identity.source == AmbientTrackingSource.STEPS)
		return lifecycleOwner.reconcile().toPurposeAvailability(lease)
	}

	override suspend fun retireAfterRetentionAuthorityFailure(
		previousLease: AmbientReconciliationLease?,
	): Boolean {
		previousLease?.let {
			require(it.identity.source == AmbientTrackingSource.STEPS)
		}
		val result = lifecycleOwner.retireAfterRetentionAuthorityFailure()
		return result.complete && !result.operational
	}
}

private fun AmbientStepsProviderRegistrationResult.toPurposeAvailability(
	lease: AmbientReconciliationLease,
): AmbientSourceOperationalAvailability = when (this) {
	is AmbientStepsProviderRegistrationResult.Active ->
		AmbientSourceOperationalAvailability.ready(
			AmbientTrackingSource.STEPS,
			provider.toPurposeMechanism(),
			lease.purposeLeaseIdentity,
		)
	is AmbientStepsProviderRegistrationResult.Inactive ->
		demandState.toPurposeAvailability(lease)
	is AmbientStepsProviderRegistrationResult.Degraded,
	is AmbientStepsProviderRegistrationResult.Failed,
	-> error("Ambient Steps provider reconciliation remains incomplete")
}

private fun AmbientStepsDemandReconciliation.toPurposeAvailability(
	lease: AmbientReconciliationLease,
): AmbientSourceOperationalAvailability = when (this) {
	is AmbientStepsDemandReconciliation.DemandReady ->
		error("A ready demand must be reconciled to a provider registration")
	is AmbientStepsDemandReconciliation.PermissionRequired ->
		AmbientSourceOperationalAvailability(
			source = AmbientTrackingSource.STEPS,
			state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
			mechanism = provider.toPurposeMechanism(),
			reason = provider.permissionReason(),
			lastIdentity = lease.purposeLeaseIdentity,
		)
	is AmbientStepsDemandReconciliation.PolicyBlocked ->
		AmbientSourceOperationalAvailability.unavailable(
			AmbientTrackingSource.STEPS,
			when (reason) {
				AmbientStepsDemandBlockReason.RETENTION_POLICY_UNAVAILABLE ->
					AmbientSourceUnavailableReason.RETENTION_POLICY_UNAVAILABLE
				AmbientStepsDemandBlockReason.ROLLOUT_CONTAINED ->
					AmbientSourceUnavailableReason.ROLLOUT_CONTAINED
				else -> AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE
			},
			lease.purposeLeaseIdentity,
		)
	is AmbientStepsDemandReconciliation.Unavailable ->
		AmbientSourceOperationalAvailability.unavailable(
			AmbientTrackingSource.STEPS,
			AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
			lease.purposeLeaseIdentity,
		)
}

private fun AmbientStepsProvider.toPurposeMechanism(): AmbientAcquisitionMechanism = when (this) {
	AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS ->
		AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS
	AmbientStepsProvider.LOCAL_RECORDING_STEPS ->
		AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS
}

private fun AmbientStepsProvider.permissionReason(): AmbientSourceUnavailableReason = when (this) {
	AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS ->
		AmbientSourceUnavailableReason.HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED
	AmbientStepsProvider.LOCAL_RECORDING_STEPS ->
		AmbientSourceUnavailableReason.LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED
}
