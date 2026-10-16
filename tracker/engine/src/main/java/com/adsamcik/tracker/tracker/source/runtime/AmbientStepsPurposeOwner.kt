package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsDemandBlockReason
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsDemandReconciliation
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsImportAccess
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsPermission
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProvider
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProviderLifecycleOwner
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProviderRegistrationResult
import com.adsamcik.tracker.tracker.worker.AmbientStepsImportWorkScheduler
import javax.inject.Inject
import javax.inject.Singleton

internal interface AmbientStepsPurposeOwner {
	suspend fun reconcile(lease: AmbientReconciliationLease): AmbientSourceOperationalAvailability

	suspend fun reconcileForRetentionFloor(
		lease: AmbientReconciliationLease,
		settlementOperationId: String,
	): AmbientSourceOperationalAvailability {
		require(settlementOperationId.isNotBlank())
		return reconcile(lease)
	}

	suspend fun retireAfterRetentionAuthorityFailure(
		previousLease: AmbientReconciliationLease?,
	): Boolean

	suspend fun closeForCollectedDataDeletion(
		previousLease: AmbientReconciliationLease?,
	): Boolean
}

@Singleton
internal class DefaultAmbientStepsPurposeOwner @Inject constructor(
	private val lifecycleOwner: AmbientStepsProviderLifecycleOwner,
	private val importScheduler: AmbientStepsImportWorkScheduler,
) : AmbientStepsPurposeOwner {
	private var scheduledRegistrationGeneration: Long? = null

	override suspend fun reconcile(
		lease: AmbientReconciliationLease,
	): AmbientSourceOperationalAvailability {
		require(lease.identity.source == AmbientTrackingSource.STEPS)
		val result = lifecycleOwner.reconcile(lease)
		updateImportWork(result)
		return result.toPurposeAvailability(lease)
	}

	override suspend fun reconcileForRetentionFloor(
		lease: AmbientReconciliationLease,
		settlementOperationId: String,
	): AmbientSourceOperationalAvailability {
		require(lease.identity.source == AmbientTrackingSource.STEPS)
		require(settlementOperationId.isNotBlank())
		val result = lifecycleOwner.reconcileForRetentionFloor(
			lease,
			settlementOperationId,
		)
		updateImportWork(result)
		return result.toPurposeAvailability(lease)
	}

	override suspend fun retireAfterRetentionAuthorityFailure(
		previousLease: AmbientReconciliationLease?,
	): Boolean {
		previousLease?.let { require(it.identity.source == AmbientTrackingSource.STEPS) }
		val result = lifecycleOwner.retireAfterRetentionAuthorityFailure(previousLease)
		importScheduler.cancel()
		scheduledRegistrationGeneration = null
		return result.complete && !result.operational
	}

	override suspend fun closeForCollectedDataDeletion(
		previousLease: AmbientReconciliationLease?,
	): Boolean {
		importScheduler.cancel()
		scheduledRegistrationGeneration = null
		return lifecycleOwner.closeForCollectedDataDeletion().complete
	}

	private suspend fun updateImportWork(result: AmbientStepsProviderRegistrationResult) {
		when (result) {
			is AmbientStepsProviderRegistrationResult.Active -> {
				importScheduler.reconcile(
					backgroundAllowed = result.importAccess == AmbientStepsImportAccess.BACKGROUND_ALLOWED,
					requestNow = scheduledRegistrationGeneration != result.registrationGeneration ||
						result.rearmedInThisProcess,
				)
				scheduledRegistrationGeneration = result.registrationGeneration
			}
			is AmbientStepsProviderRegistrationResult.Inactive -> {
				importScheduler.cancel()
				scheduledRegistrationGeneration = null
			}
			is AmbientStepsProviderRegistrationResult.Degraded,
			is AmbientStepsProviderRegistrationResult.Failed -> Unit
		}
	}
}

private fun AmbientStepsProviderRegistrationResult.toPurposeAvailability(
	lease: AmbientReconciliationLease,
): AmbientSourceOperationalAvailability = when (this) {
	is AmbientStepsProviderRegistrationResult.Active -> if (
		provider == AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS &&
		importAccess == AmbientStepsImportAccess.FOREGROUND_ONLY &&
		AmbientStepsPermission.HEALTH_CONNECT_BACKGROUND_READ in optionalPermissions
	) {
		AmbientSourceOperationalAvailability(
			source = AmbientTrackingSource.STEPS,
			state = AmbientSourceOperationalState.DEGRADED,
			mechanism = AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
			reason = AmbientSourceUnavailableReason.HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL,
			operationalIdentity = lease.purposeLeaseIdentity,
		)
	} else {
		AmbientSourceOperationalAvailability.ready(
			AmbientTrackingSource.STEPS,
			provider.toPurposeMechanism(),
			lease.purposeLeaseIdentity,
		)
	}
	is AmbientStepsProviderRegistrationResult.Inactive ->
		demandState.toPurposeAvailability(lease)
	is AmbientStepsProviderRegistrationResult.Degraded,
	is AmbientStepsProviderRegistrationResult.Failed,
	-> error("Ambient Steps provider reconciliation remains incomplete")
}

// Map completed demand outcomes to availability; preserve the explicit incomplete-retirement errors.
@Suppress("CyclomaticComplexMethod")
private fun AmbientStepsDemandReconciliation.toPurposeAvailability(
	lease: AmbientReconciliationLease,
): AmbientSourceOperationalAvailability = when (this) {
	is AmbientStepsDemandReconciliation.DemandReady ->
		error("A ready demand must be reconciled to a provider registration")
	is AmbientStepsDemandReconciliation.PermissionRequired ->
		if (!retirementComplete) {
			error("Exact Ambient Steps demand retirement was rejected")
		} else {
			AmbientSourceOperationalAvailability(
			source = AmbientTrackingSource.STEPS,
			state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
			mechanism = provider.toPurposeMechanism(),
			reason = provider.permissionReason(),
			lastIdentity = lease.purposeLeaseIdentity,
		)
		}
	is AmbientStepsDemandReconciliation.PolicyBlocked ->
		if (!retirementComplete) {
			error("Exact Ambient Steps demand retirement was rejected")
		} else {
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
		}
	is AmbientStepsDemandReconciliation.Unavailable ->
		if (!retirementComplete) {
			error("Exact Ambient Steps demand retirement was rejected")
		} else {
			AmbientSourceOperationalAvailability.unavailable(
			AmbientTrackingSource.STEPS,
			AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
			lease.purposeLeaseIdentity,
		)
		}
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
