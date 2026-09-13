package com.adsamcik.tracker.activity.api.registration

import com.adsamcik.tracker.activity.ActivityTransitionData
import java.security.MessageDigest

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
	/** Exact captured-product plan; control-only owners must leave this null. */
	val capturedPlan: ActivityRegistrationPlanAttribution? = null,
) {
	init {
		require(continuousRecognitionIntervalSeconds == null || continuousRecognitionIntervalSeconds > 0)
		require(capturedPlan == null || planRevision == capturedPlan.configurationRevision) {
			"Captured Activity plan revision must match the registration demand"
		}
	}

	val enabled: Boolean
		get() = continuousRecognitionIntervalSeconds != null || transitions.isNotEmpty()
}

/**
 * Immutable serialized plan authority supplied by the session runtime to the physical arbiter.
 *
 * This API-owned value keeps the provider module independent of tracker-engine plan classes while
 * still allowing the arbiter to commit the exact applied bytes with the accepted registration.
 */
class ActivityRegistrationPlanAttribution(
	val configurationRevision: Long,
	val payloadVersion: Int,
	payload: ByteArray,
	val payloadChecksum: String,
	val physicalConfigurationFingerprint: String,
) {
	private val storedPayload: ByteArray = payload.copyOf()
	val payload: ByteArray
		get() = storedPayload.copyOf()

	init {
		require(configurationRevision > 0L)
		require(payloadVersion > 0)
		require(storedPayload.isNotEmpty() && storedPayload.size <= MAX_PLAN_PAYLOAD_BYTES)
		require(payloadChecksum == sha256(storedPayload)) { "Activity plan payload checksum does not match" }
		require(physicalConfigurationFingerprint.isNotBlank())
	}

	fun hasSameValueAs(other: ActivityRegistrationPlanAttribution): Boolean =
		configurationRevision == other.configurationRevision &&
			payloadVersion == other.payloadVersion &&
			storedPayload.contentEquals(other.storedPayload) && payloadChecksum == other.payloadChecksum &&
			physicalConfigurationFingerprint == other.physicalConfigurationFingerprint

	companion object {
		const val MAX_PLAN_PAYLOAD_BYTES = 64 * 1_024

		private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
			.digest(bytes)
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}
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
	MISSING_CAPTURE_PLAN_BINDING,
	STORAGE_UNAVAILABLE,
	STALE_COLLECTED_DATA_EPOCH,
}
