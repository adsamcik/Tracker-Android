package com.adsamcik.tracker.tracker.resilience

import com.adsamcik.tracker.stats.api.PolicyTier

/**
 * Durable description of the tracking session Android should recover after involuntary teardown.
 */
data class ActiveTrackingSessionDescriptor(
	val isUserInitiated: Boolean,
	val isAmbient: Boolean,
	val policyTier: PolicyTier,
)

sealed interface ActiveTrackingSessionStoreResult {
	data class Success(
		val descriptor: ActiveTrackingSessionDescriptor?,
	) : ActiveTrackingSessionStoreResult

	data class Failure(
		val cause: Throwable,
	) : ActiveTrackingSessionStoreResult
}

interface ActiveTrackingSessionStore {
	suspend fun read(): ActiveTrackingSessionStoreResult

	suspend fun save(descriptor: ActiveTrackingSessionDescriptor): ActiveTrackingSessionStoreResult

	suspend fun clear(): ActiveTrackingSessionStoreResult
}

