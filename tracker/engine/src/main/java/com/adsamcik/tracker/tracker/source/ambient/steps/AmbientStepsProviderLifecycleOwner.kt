package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.api.AmbientStepsProviderCleanupFailure
import com.adsamcik.tracker.tracker.api.AmbientStepsProviderLifecycle
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientStepsSettingsReconciliationFailure
import com.adsamcik.tracker.tracker.api.AmbientStepsSettingsReconciliationResult
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One process owner for Ambient Steps demand and provider registration.
 *
 * A single boundary is used for demand replacement and registration acceptance. The outer mutex
 * also serializes deletion cleanup with the complete demand-to-provider operation; provider APIs
 * remain outside Room transactions inside the registration coordinator.
 */
@Singleton
class AmbientStepsProviderLifecycleOwner internal constructor(
	private val currentBoundary: () -> AmbientStepsDemandBoundary,
	private val reconcileDemand: suspend (
		AmbientStepsDemandBoundary,
		AmbientReconciliationLease,
	) -> AmbientStepsDemandReconciliation,
	private val reconcileSettlementDemand: suspend (
		AmbientStepsDemandBoundary,
		AmbientReconciliationLease,
		String,
	) -> AmbientStepsDemandReconciliation = { boundary, lease, _ ->
		reconcileDemand(boundary, lease)
	},
	private val retireDemandAfterAuthorityFailure: suspend (
		AmbientStepsDemandBoundary,
		AmbientReconciliationLease?,
	) -> AmbientStepsDemandReconciliation = { boundary, lease ->
		reconcileDemand(boundary, requireNotNull(lease))
	},
	private val reconcileRegistration: suspend (
		AmbientStepsDemandReconciliation,
		AmbientStepsDemandBoundary,
	) -> AmbientStepsProviderRegistrationResult,
	private val closeRegistration: suspend () -> AmbientStepsProviderCleanupResult,
) : AmbientStepsProviderLifecycle {
	@Inject
	internal constructor(
		demandReconciler: AmbientStepsDemandReconciler,
		registrationCoordinator: AmbientStepsProviderRegistrationCoordinator,
		bootClockDomainProvider: BootClockDomainProvider,
	) : this(
		currentBoundary = {
			AmbientStepsDemandBoundary(
				bootId = bootClockDomainProvider.current(),
				elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
				wallTimeMs = Time.nowMillis,
			)
		},
		reconcileDemand = demandReconciler::reconcileAt,
		reconcileSettlementDemand = demandReconciler::reconcileForRetentionFloorAt,
		retireDemandAfterAuthorityFailure =
			demandReconciler::retireAfterRetentionAuthorityFailureAt,
		reconcileRegistration = registrationCoordinator::reconcile,
		closeRegistration = registrationCoordinator::closeForCollectedDataDeletion,
	)

	private val mutex = Mutex()

	internal suspend fun reconcile(
		lease: AmbientReconciliationLease,
	): AmbientStepsProviderRegistrationResult = mutex.withLock {
		val boundary = currentBoundary()
		val demand = reconcileDemand(boundary, lease)
		reconcileRegistration(demand, boundary)
	}

	internal suspend fun reconcileForRetentionFloor(
		lease: AmbientReconciliationLease,
		settlementOperationId: String,
	): AmbientStepsProviderRegistrationResult = mutex.withLock {
		require(settlementOperationId.isNotBlank())
		val boundary = currentBoundary()
		val demand = reconcileSettlementDemand(boundary, lease, settlementOperationId)
		reconcileRegistration(demand, boundary)
	}

	internal suspend fun retireAfterRetentionAuthorityFailure(
		lease: AmbientReconciliationLease?,
	): AmbientStepsSettingsReconciliationResult = mutex.withLock {
		val boundary = currentBoundary()
		val retired = retireDemandAfterAuthorityFailure(boundary, lease)
		reconcileRegistration(retired, boundary).toPublicSettingsResult()
	}

	override suspend fun closeForCollectedDataDeletion():
		com.adsamcik.tracker.tracker.api.AmbientStepsProviderCleanupResult = mutex.withLock {
		closeRegistration().toPublicResult()
	}
}

private fun AmbientStepsProviderCleanupResult.toPublicResult():
	com.adsamcik.tracker.tracker.api.AmbientStepsProviderCleanupResult =
	com.adsamcik.tracker.tracker.api.AmbientStepsProviderCleanupResult(
		complete = complete,
		failure = failure?.toPublicFailure(),
		retryable = retryable,
	)

private fun AmbientStepsProviderRegistrationResult.toPublicSettingsResult():
	AmbientStepsSettingsReconciliationResult = when (this) {
	is AmbientStepsProviderRegistrationResult.Active ->
		AmbientStepsSettingsReconciliationResult(complete = true, operational = true)
	is AmbientStepsProviderRegistrationResult.Inactive ->
		AmbientStepsSettingsReconciliationResult(complete = true, operational = false)
	is AmbientStepsProviderRegistrationResult.Degraded ->
		AmbientStepsSettingsReconciliationResult(
			complete = false,
			operational = false,
			failure = failure.toPublicSettingsFailure(),
			retryable = retryable,
		)
	is AmbientStepsProviderRegistrationResult.Failed ->
		AmbientStepsSettingsReconciliationResult(
			complete = false,
			operational = false,
			failure = failure.toPublicSettingsFailure(),
			retryable = retryable,
		)
}

private fun AmbientStepsProviderRegistrationFailure.toPublicSettingsFailure():
	AmbientStepsSettingsReconciliationFailure = when (this) {
	AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED ->
		AmbientStepsSettingsReconciliationFailure.DURABLE_AUTHORITY_REJECTED
	AmbientStepsProviderRegistrationFailure.PROVIDER_ACTIVATION_FAILED ->
		AmbientStepsSettingsReconciliationFailure.PROVIDER_ACTIVATION_FAILED
	AmbientStepsProviderRegistrationFailure.PROVIDER_REMOVAL_FAILED ->
		AmbientStepsSettingsReconciliationFailure.PROVIDER_REMOVAL_FAILED
	AmbientStepsProviderRegistrationFailure.PROVIDER_IDENTITY_INVALID ->
		AmbientStepsSettingsReconciliationFailure.PROVIDER_IDENTITY_INVALID
	AmbientStepsProviderRegistrationFailure.AUTHORITY_CHANGED_DURING_ACTIVATION ->
		AmbientStepsSettingsReconciliationFailure.AUTHORITY_CHANGED_DURING_ACTIVATION
	AmbientStepsProviderRegistrationFailure.CLEANUP_JOURNAL_UNAVAILABLE ->
		AmbientStepsSettingsReconciliationFailure.CLEANUP_JOURNAL_UNAVAILABLE
	AmbientStepsProviderRegistrationFailure.PROVIDER_CLEANUP_STATE_INVALID ->
		AmbientStepsSettingsReconciliationFailure.PROVIDER_STATE_INVALID
}

private fun AmbientStepsProviderRegistrationFailure.toPublicFailure():
	AmbientStepsProviderCleanupFailure = when (this) {
	AmbientStepsProviderRegistrationFailure.PROVIDER_REMOVAL_FAILED ->
		AmbientStepsProviderCleanupFailure.PROVIDER_REMOVAL_FAILED
	AmbientStepsProviderRegistrationFailure.CLEANUP_JOURNAL_UNAVAILABLE ->
		AmbientStepsProviderCleanupFailure.CLEANUP_JOURNAL_UNAVAILABLE
	AmbientStepsProviderRegistrationFailure.PROVIDER_CLEANUP_STATE_INVALID ->
		AmbientStepsProviderCleanupFailure.CLEANUP_JOURNAL_INVALID
	else -> AmbientStepsProviderCleanupFailure.PROVIDER_STATE_INVALID
	}
