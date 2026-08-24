package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Single durable reservation for an Activity-Transition automatic foreground-service start.
 *
 * This is deliberately not a generic desired-action or receipt platform. Android only grants the
 * background-start opportunity to the currently executing transition callback, so the app keeps
 * one exact start envelope and never replays the external call from durable storage. The source
 * outbox is completed only after a matching lifecycle intent has been committed.
 */
@Entity(
	tableName = "activity_automatic_start_action",
	indices = [
		Index(value = ["trigger_id"], unique = true, name = "idx_activity_auto_start_trigger"),
		Index(value = ["effect_stable_id"], unique = true, name = "idx_activity_auto_start_effect"),
	],
)
data class ActivityAutomaticStartActionEntity(
	@PrimaryKey
	@ColumnInfo(name = "slot_id")
	val slotId: Int = SINGLETON_ID,
	@ColumnInfo(name = "trigger_id") val triggerId: String,
	@ColumnInfo(name = "effect_stable_id") val effectStableId: String,
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "trigger_kind") val triggerKind: String,
	@ColumnInfo(name = "boot_id") val bootId: String,
	@ColumnInfo(name = "observed_elapsed_realtime_nanos") val observedElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "received_elapsed_realtime_nanos") val receivedElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "expires_elapsed_realtime_nanos") val expiresElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "automation_epoch") val automationEpoch: Long,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long,
	@ColumnInfo(name = "control_consent_epoch") val controlConsentEpoch: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "requested_capture_source_mask") val requestedCaptureSourceMask: Long,
	@ColumnInfo(name = "intended_capture_source_mask") val intendedCaptureSourceMask: Long,
	@ColumnInfo(name = "intended_fgs_type_mask") val intendedForegroundServiceTypeMask: Long,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "authorization_revision") val authorizationRevision: Long,
	@ColumnInfo(name = "authorization_fingerprint") val authorizationFingerprint: String,
	@ColumnInfo(name = "start_origin") val startOrigin: String,
	@ColumnInfo(name = "status") val status: String,
	@ColumnInfo(name = "reserved_at_ms") val reservedAtMs: Long,
	@ColumnInfo(name = "start_requested_at_ms") val startRequestedAtMs: Long?,
	@ColumnInfo(name = "lifecycle_intent_accepted_at_ms") val lifecycleIntentAcceptedAtMs: Long?,
	@ColumnInfo(name = "accepted_logical_tracking_id") val acceptedLogicalTrackingId: String?,
	@ColumnInfo(name = "accepted_intent_revision") val acceptedIntentRevision: Long?,
	@ColumnInfo(name = "terminal_at_ms") val terminalAtMs: Long?,
	@ColumnInfo(name = "terminal_reason") val terminalReason: String?,
) {
	init {
		require(slotId == SINGLETON_ID) { "Only the Activity automatic-start singleton slot is valid" }
		require(triggerId.isNotBlank())
		require(effectStableId.isNotBlank())
		require(admissionOrdinal > 0L)
		require(triggerKind.isNotBlank())
		require(bootId.isNotBlank())
		require(observedElapsedRealtimeNanos >= 0L)
		require(receivedElapsedRealtimeNanos >= observedElapsedRealtimeNanos)
		require(expiresElapsedRealtimeNanos >= receivedElapsedRealtimeNanos)
		require(automationEpoch > 0L)
		require(sourcePolicyRevision > 0L)
		require(controlConsentEpoch >= 0L)
		require(collectedDataEpoch >= 0L)
		require(requestedCaptureSourceMask > 0L)
		require(intendedCaptureSourceMask > 0L)
		require(intendedCaptureSourceMask and requestedCaptureSourceMask == intendedCaptureSourceMask)
		require(intendedForegroundServiceTypeMask >= 0L)
		require(registrationGeneration > 0L)
		require(authorizationRevision > 0L)
		require(authorizationFingerprint.isNotBlank())
		require(startOrigin == START_ORIGIN_ACTIVITY_TRANSITION_CALLBACK)
		require(status in VALID_STATUSES)
		require(reservedAtMs >= 0L)
		// Wall time is display/audit metadata, not an ordering clock. The user may change it, or the
		// platform may correct it, between otherwise valid elapsed-realtime-fenced transitions.
		require(startRequestedAtMs == null || startRequestedAtMs >= 0L)
		require(lifecycleIntentAcceptedAtMs == null || lifecycleIntentAcceptedAtMs >= 0L)
		require(terminalAtMs == null || terminalAtMs >= 0L)
	}

	companion object {
		const val SINGLETON_ID = 1
		const val STATUS_RESERVED = "RESERVED"
		const val STATUS_START_REQUESTED = "START_REQUESTED"
		const val STATUS_LIFECYCLE_INTENT_ACCEPTED = "LIFECYCLE_INTENT_ACCEPTED"
		const val STATUS_TERMINAL = "TERMINAL"
		const val START_ORIGIN_ACTIVITY_TRANSITION_CALLBACK = "ACTIVITY_TRANSITION_CALLBACK"

		private val VALID_STATUSES = setOf(
			STATUS_RESERVED,
			STATUS_START_REQUESTED,
			STATUS_LIFECYCLE_INTENT_ACCEPTED,
			STATUS_TERMINAL,
		)
	}
}
