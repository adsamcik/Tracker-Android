package com.adsamcik.tracker.tracker.api

/**
 * Provider-global cleanup boundary for default-off Ambient Steps collection.
 *
 * The provider subscription may outlive collected Room data, so full deletion must close it before
 * erasing the database. No observation, provider identity, or account data crosses this contract.
 */
interface AmbientStepsProviderLifecycle {
	suspend fun closeForCollectedDataDeletion(): AmbientStepsProviderCleanupResult
}

data class AmbientStepsProviderCleanupResult(
	val complete: Boolean,
	val failure: AmbientStepsProviderCleanupFailure? = null,
	val retryable: Boolean = false,
) {
	init {
		require(complete == (failure == null))
		require(failure != null || !retryable)
	}
}

enum class AmbientStepsProviderCleanupFailure {
	PROVIDER_REMOVAL_FAILED,
	CLEANUP_JOURNAL_UNAVAILABLE,
	CLEANUP_JOURNAL_INVALID,
	PROVIDER_STATE_INVALID,
}
