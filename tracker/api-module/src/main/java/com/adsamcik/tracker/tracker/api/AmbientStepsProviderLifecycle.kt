package com.adsamcik.tracker.tracker.api

/**
 * Provider-global cleanup boundary for default-off Ambient Steps collection.
 *
 * The provider subscription may outlive collected Room data, so full deletion must close it before
 * erasing the database. No observation, provider identity, or account data crosses this contract.
 */
interface AmbientStepsProviderLifecycle {
	suspend fun reconcileAfterSettingsChange(): AmbientStepsSettingsReconciliationResult
	suspend fun retireAfterRetentionAuthorityFailure(): AmbientStepsSettingsReconciliationResult
	suspend fun closeForCollectedDataDeletion(): AmbientStepsProviderCleanupResult
}

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

enum class AmbientStepsSettingsReconciliationFailure {
	DURABLE_AUTHORITY_REJECTED,
	PROVIDER_ACTIVATION_FAILED,
	PROVIDER_REMOVAL_FAILED,
	PROVIDER_IDENTITY_INVALID,
	AUTHORITY_CHANGED_DURING_ACTIVATION,
	CLEANUP_JOURNAL_UNAVAILABLE,
	PROVIDER_STATE_INVALID,
}

object NoOpAmbientStepsProviderLifecycle : AmbientStepsProviderLifecycle {
	override suspend fun reconcileAfterSettingsChange() =
		AmbientStepsSettingsReconciliationResult(complete = true, operational = false)

	override suspend fun retireAfterRetentionAuthorityFailure() =
		AmbientStepsSettingsReconciliationResult(complete = true, operational = false)

	override suspend fun closeForCollectedDataDeletion() =
		AmbientStepsProviderCleanupResult(complete = true)
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
