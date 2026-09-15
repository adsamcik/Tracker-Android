package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.api.AmbientStepsProviderCleanupFailure
import com.adsamcik.tracker.tracker.api.AmbientStepsProviderLifecycle
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
	) -> AmbientStepsDemandReconciliation,
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
		reconcileRegistration = registrationCoordinator::reconcile,
		closeRegistration = registrationCoordinator::closeForCollectedDataDeletion,
	)

	private val mutex = Mutex()

	internal suspend fun reconcile(): AmbientStepsProviderRegistrationResult = mutex.withLock {
		val boundary = currentBoundary()
		val demand = reconcileDemand(boundary)
		reconcileRegistration(demand, boundary)
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
