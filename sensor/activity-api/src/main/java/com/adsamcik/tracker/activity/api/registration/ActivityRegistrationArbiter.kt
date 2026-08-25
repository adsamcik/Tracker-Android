package com.adsamcik.tracker.activity.api.registration

import com.adsamcik.tracker.activity.ActivityTransitionData

/**
 * Sole logical entry point for activity-recognition registration ownership.
 *
 * The arbiter combines independent application and session demands into one physical provider
 * registration. Removing one owner must never remove a registration still needed by another.
 */
interface ActivityRegistrationArbiter {
	suspend fun setDemand(owner: ActivityRegistrationOwner, demand: ActivityRegistrationDemand): ActivityRegistrationResult
	suspend fun clearDemand(owner: ActivityRegistrationOwner): ActivityRegistrationResult
	/** Reconciles an unchanged physical request after its durable purpose/consent vector changes. */
	suspend fun reconcileDurableDemands(): ActivityRegistrationResult
	suspend fun closeForCollectedDataDeletion(): ActivityRegistrationResult
	suspend fun resumeAfterCollectedDataDeletion(): ActivityRegistrationResult
	/**
	 * Retries physical provider cleanup recorded outside collected-data storage.
	 *
	 * This operation must not require Room: the registration rows may already have been deleted.
	 */
	suspend fun retryPendingProviderCleanup(): ActivityProviderCleanupResult
	fun snapshot(): ActivityRegistrationSnapshot
}

enum class ActivityRegistrationOwner {
	AUTOMATIC_START_MONITOR,
	ACTIVE_SESSION,
	LEGACY_REQUEST_MANAGER,
}

data class ActivityRegistrationDemand(
	val continuousRecognitionIntervalSeconds: Int? = null,
	val transitions: Set<ActivityTransitionData> = emptySet(),
	val planRevision: Long? = null,
) {
	init {
		require(continuousRecognitionIntervalSeconds == null || continuousRecognitionIntervalSeconds > 0)
	}

	val enabled: Boolean
		get() = continuousRecognitionIntervalSeconds != null || transitions.isNotEmpty()
}

data class ActivityRegistrationIdentity(
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val collectedDataEpoch: Long,
	val clockDomainId: String,
	val physicalConfigurationFingerprint: String,
) {
	init {
		require(sourceInstanceId.isNotBlank())
		require(registrationGeneration > 0L)
		require(collectedDataEpoch >= 0L)
		require(clockDomainId.isNotBlank())
		require(physicalConfigurationFingerprint.isNotBlank())
	}
}

data class ActivityRegistrationSnapshot(
	val active: Boolean,
	val identity: ActivityRegistrationIdentity?,
	val owners: Set<ActivityRegistrationOwner>,
	val continuousRecognitionIntervalSeconds: Int?,
	val transitions: Set<ActivityTransitionData>,
)

data class ActivityRegistrationResult(
	val status: ActivityRegistrationStatus,
	val snapshot: ActivityRegistrationSnapshot,
	val failureCode: ActivityRegistrationFailureCode? = null,
	val retryable: Boolean = false,
)

data class ActivityProviderCleanupResult(
	val complete: Boolean,
	val pendingCount: Int,
	val retryable: Boolean,
	val failureCode: ActivityRegistrationFailureCode? = null,
) {
	init {
		require(pendingCount >= 0)
		require(complete == (pendingCount == 0 && failureCode == null))
	}

	companion object {
		val COMPLETE = ActivityProviderCleanupResult(
			complete = true,
			pendingCount = 0,
			retryable = false,
		)
	}
}

enum class ActivityRegistrationStatus { APPLIED, DEGRADED, BLOCKED, FAILED }

enum class ActivityRegistrationFailureCode {
	STARTUP_RECOVERY_NOT_READY,
	PERMISSION_MISSING,
	MISSING_DURABLE_DEMAND,
	PROVIDER_UNAVAILABLE,
	PROVIDER_REGISTRATION_FAILED,
	CALLBACK_METADATA_UPDATE_FAILED,
	AUTHORIZATION_REFRESH_PENDING,
	PROVIDER_REMOVAL_FAILED,
	PROVIDER_CLEANUP_PENDING,
	PROVIDER_CLEANUP_STATE_INVALID,
	CALLBACK_DRAIN_PENDING,
	STORAGE_UNAVAILABLE,
	STALE_COLLECTED_DATA_EPOCH,
}
