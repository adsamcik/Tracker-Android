package com.adsamcik.tracker.tracker.api

/**
 * Provider-global cleanup boundary for default-off Ambient Steps collection.
 *
 * The provider subscription may outlive collected Room data, so full deletion must close it before
 * erasing the database. No observation, provider identity, or account data crosses this contract.
 */
interface AmbientStepsProviderLifecycle {
	/**
	 * Retires external Steps collection before data is cleared; incomplete cleanup must remain retryable.
	 */
	suspend fun closeForCollectedDataDeletion(): AmbientStepsProviderCleanupResult
}

/**
 * Whether the selected Steps provider was settled and can currently supply data; retryable failures retain
 * recovery work.
 */
data class AmbientStepsSettingsReconciliationResult(
	val complete: Boolean,
	val operational: Boolean,
	val failure: AmbientStepsSettingsReconciliationFailure? = null,
	val retryable: Boolean = false,
) {
	init {
		require(complete == (failure == null))
		require(!operational || complete)
		require(failure != null || !retryable)
	}
}

/** Why Steps provider reconciliation could not accept or retire its durable registration. */
enum class AmbientStepsSettingsReconciliationFailure {
	DURABLE_AUTHORITY_REJECTED,
	PROVIDER_ACTIVATION_FAILED,
	PROVIDER_REMOVAL_FAILED,
	PROVIDER_IDENTITY_INVALID,
	AUTHORITY_CHANGED_DURING_ACTIVATION,
	CLEANUP_JOURNAL_UNAVAILABLE,
	PROVIDER_STATE_INVALID,
}

/** Cleanup boundary for configurations that own no external Steps registration. */
object NoOpAmbientStepsProviderLifecycle : AmbientStepsProviderLifecycle {
	override suspend fun closeForCollectedDataDeletion() =
		AmbientStepsProviderCleanupResult(complete = true)
}

/**
 * Whether external Steps registrations and their cleanup journal are safe for collected-data deletion.
 */
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

/** Provider or journal failure that prevents acknowledging Steps cleanup. */
enum class AmbientStepsProviderCleanupFailure {
	PROVIDER_REMOVAL_FAILED,
	CLEANUP_JOURNAL_UNAVAILABLE,
	CLEANUP_JOURNAL_INVALID,
	PROVIDER_STATE_INVALID,
}
