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
	suspend fun closeForCollectedDataDeletion(): ActivityRegistrationResult
	suspend fun resumeAfterCollectedDataDeletion(): ActivityRegistrationResult
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
	val appliedRevision: Long?,
) {
	init {
		require(sourceInstanceId.isNotBlank())
		require(registrationGeneration >= 0L)
		require(collectedDataEpoch >= 0L)
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

enum class ActivityRegistrationStatus { APPLIED, DEGRADED, BLOCKED, FAILED }

enum class ActivityRegistrationFailureCode {
	PERMISSION_MISSING,
	PROVIDER_UNAVAILABLE,
	PROVIDER_REGISTRATION_FAILED,
	PROVIDER_REMOVAL_FAILED,
	STORAGE_UNAVAILABLE,
	STALE_COLLECTED_DATA_EPOCH,
}
