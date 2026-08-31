package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** Released-v27 completeness rows had no physical service-run attribution. */
const val LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID = "__LEGACY_V27_UNATTRIBUTED__"

@Entity(
	tableName = "logical_tracking_session",
	primaryKeys = ["logical_tracking_id"],
	indices = [Index(value = ["state", "started_at_ms"], name = "idx_logical_tracking_session_state")],
)
data class LogicalTrackingSessionEntity(
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "state") val state: String,
	@ColumnInfo(name = "lifecycle_revision") val lifecycleRevision: Long,
	@ColumnInfo(name = "desired_plan_revision") val desiredPlanRevision: Long,
	@ColumnInfo(name = "rollout_revision") val rolloutRevision: Long,
	@ColumnInfo(name = "start_origin") val startOrigin: String,
	@ColumnInfo(name = "clock_domain_id") val clockDomainId: String,
	@ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
	@ColumnInfo(name = "started_elapsed_nanos") val startedElapsedNanos: Long,
	@ColumnInfo(name = "cutoff_at_ms") val cutoffAtMs: Long?,
	@ColumnInfo(name = "cutoff_elapsed_nanos") val cutoffElapsedNanos: Long?,
	@ColumnInfo(name = "completed_at_ms") val completedAtMs: Long?,
	@ColumnInfo(name = "final_admission_ordinal") val finalAdmissionOrdinal: Long?,
	@ColumnInfo(name = "failure_code") val failureCode: String?,
	@ColumnInfo(name = "session_mode", defaultValue = "'LEGACY_UNKNOWN'")
	val sessionMode: String = "LEGACY_UNKNOWN",
	@ColumnInfo(name = "current_manifest_revision") val currentManifestRevision: Long? = null,
	@ColumnInfo(name = "current_intent_revision") val currentIntentRevision: Long? = null,
	@ColumnInfo(name = "current_service_run_id") val currentServiceRunId: String? = null,
	@ColumnInfo(name = "lifecycle_lease_generation", defaultValue = "0")
	val lifecycleLeaseGeneration: Long = 0L,
	@ColumnInfo(name = "lifecycle_boot_id") val lifecycleBootId: String? = null,
	@ColumnInfo(name = "automation_epoch") val automationEpoch: Long? = null,
)

@Entity(
	tableName = "source_service_run",
	primaryKeys = ["service_run_id"],
	indices = [
		Index(value = ["logical_tracking_id", "started_at_ms"], name = "idx_source_service_run_tracking"),
		Index(value = ["start_delivery_token"], unique = true, name = "idx_source_service_run_delivery_token"),
		Index(
			value = ["session_segment_id"],
			unique = true,
			name = "idx_source_service_run_session_segment",
		),
	],
)
data class SourceServiceRunEntity(
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "state") val state: String,
	@ColumnInfo(name = "desired_plan_revision") val desiredPlanRevision: Long,
	@ColumnInfo(name = "rollout_revision") val rolloutRevision: Long,
	@ColumnInfo(name = "foreground_capability_flags") val foregroundCapabilityFlags: Long,
	@ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
	@ColumnInfo(name = "started_elapsed_nanos") val startedElapsedNanos: Long,
	@ColumnInfo(name = "completed_at_ms") val completedAtMs: Long?,
	@ColumnInfo(name = "completion_reason") val completionReason: String?,
	@ColumnInfo(name = "boot_id", defaultValue = "'LEGACY_UNKNOWN'") val bootId: String = "LEGACY_UNKNOWN",
	@ColumnInfo(name = "lease_generation", defaultValue = "0") val leaseGeneration: Long = 0L,
	@ColumnInfo(name = "start_origin", defaultValue = "'LEGACY_UNKNOWN'")
	val startOrigin: String = "LEGACY_UNKNOWN",
	@ColumnInfo(name = "desired_foreground_capability_flags", defaultValue = "0")
	val desiredForegroundCapabilityFlags: Long = 0L,
	@ColumnInfo(name = "applied_foreground_capability_flags") val appliedForegroundCapabilityFlags: Long? = null,
	@ColumnInfo(name = "runtime_acknowledgement", defaultValue = "'PENDING'")
	val runtimeAcknowledgement: String = "PENDING",
	@ColumnInfo(name = "runtime_failure_code") val runtimeFailureCode: String? = null,
	@ColumnInfo(name = "run_revision", defaultValue = "0") val runRevision: Long = 0L,
	/** Opaque identity carried by Android; null only for released-v27 migration facts. */
	@ColumnInfo(name = "start_delivery_token") val startDeliveryToken: String? = null,
	@ColumnInfo(name = "start_command_generation", defaultValue = "0")
	val startCommandGeneration: Long = 0L,
	@ColumnInfo(name = "prepared_manifest_revision", defaultValue = "0")
	val preparedManifestRevision: Long = 0L,
	@ColumnInfo(name = "prepared_intent_revision", defaultValue = "0")
	val preparedIntentRevision: Long = 0L,
	@ColumnInfo(name = "android_delivery_state", defaultValue = "'LEGACY_UNKNOWN'")
	val androidDeliveryState: String = "LEGACY_UNKNOWN",
	@ColumnInfo(name = "android_delivery_updated_at_ms")
	val androidDeliveryUpdatedAtMs: Long? = null,
	@ColumnInfo(name = "start_is_user_initiated", defaultValue = "0")
	val startIsUserInitiated: Boolean = false,
	@ColumnInfo(name = "start_is_ambient", defaultValue = "0")
	val startIsAmbient: Boolean = false,
	/** Exact source-neutral presentation segment owned by this physical service run. */
	@ColumnInfo(name = "session_segment_id")
	val sessionSegmentId: Long? = null,
	/**
	 * Durable proof that every presentation writer for this run has stopped.
	 *
	 * Released-v27 rows are explicitly unverifiable. New v28 runs always insert [PRESENTATION_PENDING]
	 * and may advance to [PRESENTATION_QUIESCED] only through the exact Room lifecycle boundary.
	 * Quiescence is never evidence that the segment is empty, retained, materialized, or deletable.
	 */
	@ColumnInfo(
		name = "presentation_acknowledgement",
		defaultValue = "'LEGACY_UNVERIFIABLE'",
	)
	val presentationAcknowledgement: String = PRESENTATION_PENDING,
	@ColumnInfo(name = "presentation_acknowledged_at_ms")
	val presentationAcknowledgedAtMs: Long? = null,
) {
	init {
		require(serviceRunId.isNotBlank()) { "Service run id must not be blank" }
		require(serviceRunId != LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID) {
			"The legacy completeness sentinel cannot identify a physical service run"
		}
		require(sessionSegmentId == null || sessionSegmentId > 0L) {
			"Session segment id must be positive when present"
		}
		require(presentationAcknowledgement in PRESENTATION_ACKNOWLEDGEMENTS) {
			"Unknown presentation acknowledgement $presentationAcknowledgement"
		}
		require(presentationAcknowledgedAtMs == null || presentationAcknowledgedAtMs >= 0L)
		if (presentationAcknowledgement == PRESENTATION_QUIESCED) {
			require(sessionSegmentId != null) { "Quiesced presentation requires exact segment ownership" }
			require(presentationAcknowledgedAtMs != null)
		} else {
			require(presentationAcknowledgedAtMs == null)
		}
	}

	/** Stable acknowledgement values persisted by Room. */
	companion object {
		const val PRESENTATION_PENDING = "PENDING"
		const val PRESENTATION_QUIESCED = "QUIESCED"
		const val PRESENTATION_LEGACY_UNVERIFIABLE = "LEGACY_UNVERIFIABLE"
		private val PRESENTATION_ACKNOWLEDGEMENTS = setOf(
			PRESENTATION_PENDING,
			PRESENTATION_QUIESCED,
			PRESENTATION_LEGACY_UNVERIFIABLE,
		)
	}
}

/**
 * Immutable intent for one effective portion of a logical tracking session.
 *
 * A version has no update DAO. Its effective end is the next version's start (or the logical
 * session's terminal boundary), so policy changes never rewrite prior intent.
 */
@Entity(
	tableName = "session_manifest_version",
	primaryKeys = ["logical_tracking_id", "manifest_revision"],
	indices = [
		Index(
			value = ["logical_tracking_id", "effective_elapsed_realtime_nanos"],
			name = "idx_session_manifest_effective",
		),
		Index(value = ["source_policy_revision"], name = "idx_session_manifest_policy"),
		Index(
			value = ["service_run_id", "manifest_revision"],
			name = "idx_session_manifest_service_run",
			unique = true,
		),
	],
)
data class SessionManifestVersionEntity(
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "manifest_revision") val manifestRevision: Long,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "session_mode") val sessionMode: String,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long,
	@ColumnInfo(name = "acquisition_plan_revision") val acquisitionPlanRevision: Long,
	@ColumnInfo(name = "rollout_revision") val rolloutRevision: Long,
	@ColumnInfo(name = "start_origin") val startOrigin: String,
	@ColumnInfo(name = "effective_boot_id") val effectiveBootId: String,
	@ColumnInfo(name = "effective_elapsed_realtime_nanos") val effectiveElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "effective_wall_time_ms") val effectiveWallTimeMs: Long,
	@ColumnInfo(name = "zone_id") val zoneId: String,
	@ColumnInfo(name = "automation_epoch") val automationEpoch: Long?,
	@ColumnInfo(name = "change_reason") val changeReason: String,
	@ColumnInfo(name = "manifest_checksum") val manifestChecksum: String,
) {
	init {
		require(serviceRunId.isNotBlank()) { "Manifest service run id must not be blank" }
		require(serviceRunId != LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID) {
			"The legacy completeness sentinel cannot own a manifest"
		}
	}
}

/** Stable persisted purpose vocabulary for immutable session-manifest membership. */
object SessionManifestPurposeCode {
	const val SESSION_CAPTURE = SourceBrokerPurpose.SESSION_CAPTURE
	const val CONTROL = "CONTROL"

	val ALL = setOf(SESSION_CAPTURE, CONTROL)
}

/** Source/purpose membership of an immutable manifest version. */
@Entity(
	tableName = "session_manifest_source",
	primaryKeys = ["logical_tracking_id", "manifest_revision", "source_kind", "purpose"],
	indices = [
		Index(
			value = ["logical_tracking_id", "source_kind", "purpose", "manifest_revision"],
			name = "idx_session_manifest_source_lookup",
		),
	],
)
data class SessionManifestSourceEntity(
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "manifest_revision") val manifestRevision: Long,
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "purpose") val purpose: String,
	@ColumnInfo(name = "consent_epoch") val consentEpoch: Long,
	@ColumnInfo(name = "persistence_eligible") val persistenceEligible: Boolean,
	@ColumnInfo(name = "qos_code") val qosCode: Int,
	@ColumnInfo(name = "output_destination") val outputDestination: String? = null,
	@ColumnInfo(name = "writer_owner") val writerOwner: String? = null,
	@ColumnInfo(name = "writer_owner_generation") val writerOwnerGeneration: Long? = null,
	@ColumnInfo(name = "writer_projection_id") val writerProjectionId: String? = null,
	@ColumnInfo(name = "writer_projection_version") val writerProjectionVersion: Int? = null,
	@ColumnInfo(name = "writer_binding_generation") val writerBindingGeneration: Long? = null,
) {
	init {
		val writerProvenance = listOf(outputDestination, writerOwner, writerOwnerGeneration)
		require(writerProvenance.all { it == null } || writerProvenance.all { it != null }) {
			"Writer destination, owner, and generation must be supplied together"
		}
		require(outputDestination == null || outputDestination.isNotBlank())
		require(writerOwner == null || writerOwner.isNotBlank())
		require(writerOwnerGeneration == null || writerOwnerGeneration > 0L)

		val projectionProvenance = listOf(
			writerProjectionId,
			writerProjectionVersion,
			writerBindingGeneration,
		)
		require(projectionProvenance.all { it == null } || projectionProvenance.all { it != null }) {
			"Writer projection id, version, and binding generation must be supplied together"
		}
		require(writerProjectionId == null || writerProjectionId.isNotBlank())
		require(writerProjectionVersion == null || writerProjectionVersion > 0)
		require(writerBindingGeneration == null || writerBindingGeneration > 0L)
		require(writerProjectionId == null || outputDestination != null) {
			"Projection provenance requires destination-owner provenance"
		}
		val isPersistenceEligibleStepsCapture =
			sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
			purpose == SourceBrokerPurpose.SESSION_CAPTURE && persistenceEligible
		if (isPersistenceEligibleStepsCapture) {
			require(outputDestination != null) {
				"Persistence-eligible Steps capture requires immutable writer provenance"
			}
			require(outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS) {
				"Steps capture must target the permanent session Steps destination"
			}
			require(
				writerOwner == SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL ||
					writerOwner == SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
			) { "Steps capture must name a permanent destination owner" }
			if (writerOwner == SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL) {
				require(writerProjectionId == null) {
					"Legacy Steps ownership must not claim candidate projection provenance"
				}
			} else {
				require(writerProjectionId != null) {
					"Candidate Steps ownership requires complete projection provenance"
				}
			}
		} else {
			require(outputDestination == null && writerProjectionId == null) {
				"Only persistence-eligible Steps capture may carry writer provenance"
			}
		}
	}
}

/** Append-only logical lifecycle intent; execution progress lives in desired-action rows. */
@Entity(
	tableName = "session_lifecycle_intent_version",
	primaryKeys = ["logical_tracking_id", "intent_revision"],
	indices = [
		Index(
			value = ["logical_tracking_id", "requested_elapsed_realtime_nanos"],
			name = "idx_session_lifecycle_intent_requested",
		),
	],
)
data class SessionLifecycleIntentVersionEntity(
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "intent_revision") val intentRevision: Long,
	@ColumnInfo(name = "manifest_revision") val manifestRevision: Long,
	@ColumnInfo(name = "desired_state") val desiredState: String,
	@ColumnInfo(name = "start_origin") val startOrigin: String,
	@ColumnInfo(name = "request_boot_id") val requestBootId: String,
	@ColumnInfo(name = "requested_elapsed_realtime_nanos") val requestedElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "requested_wall_time_ms") val requestedWallTimeMs: Long,
	@ColumnInfo(name = "automation_epoch") val automationEpoch: Long?,
	@ColumnInfo(name = "trigger_id") val triggerId: String?,
	@ColumnInfo(name = "trigger_kind") val triggerKind: String?,
	@ColumnInfo(name = "trigger_boot_id") val triggerBootId: String?,
	@ColumnInfo(name = "trigger_observed_elapsed_realtime_nanos")
	val triggerObservedElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "trigger_received_elapsed_realtime_nanos")
	val triggerReceivedElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "trigger_expires_elapsed_realtime_nanos")
	val triggerExpiresElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "stop_reason") val stopReason: String?,
	@ColumnInfo(name = "stop_deadline_boot_id") val stopDeadlineBootId: String?,
	@ColumnInfo(name = "stop_deadline_elapsed_realtime_nanos") val stopDeadlineElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "intent_checksum") val intentChecksum: String,
	/** Data-generation fence copied from an automatic trigger; null for manual/recovery intent. */
	@ColumnInfo(name = "trigger_collected_data_epoch")
	val triggerCollectedDataEpoch: Long? = null,
)

/** Durable desired external action. Intent is inserted before the runtime side effect. */
@Entity(
	tableName = "lifecycle_desired_action",
	indices = [
		Index(
			value = ["logical_tracking_id", "action_revision"],
			unique = true,
			name = "idx_lifecycle_action_revision",
		),
		Index(value = ["status", "requested_at_ms"], name = "idx_lifecycle_action_pending"),
	],
)
data class LifecycleDesiredActionEntity(
	@androidx.room.PrimaryKey
	@ColumnInfo(name = "action_id") val actionId: String,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "manifest_revision") val manifestRevision: Long,
	@ColumnInfo(name = "action_revision") val actionRevision: Long,
	@ColumnInfo(name = "action_family") val actionFamily: String,
	@ColumnInfo(name = "source_kind") val sourceKind: Int?,
	@ColumnInfo(name = "desired_state") val desiredState: String,
	@ColumnInfo(name = "desired_plan_revision") val desiredPlanRevision: Long,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long,
	@ColumnInfo(name = "consent_epoch") val consentEpoch: Long?,
	@ColumnInfo(name = "start_origin") val startOrigin: String,
	@ColumnInfo(name = "boot_id") val bootId: String,
	@ColumnInfo(name = "lease_generation") val leaseGeneration: Long,
	@ColumnInfo(name = "requested_at_ms") val requestedAtMs: Long,
	@ColumnInfo(name = "requested_elapsed_realtime_nanos") val requestedElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "status") val status: String,
	@ColumnInfo(name = "attempt_count") val attemptCount: Int,
	@ColumnInfo(name = "acknowledged_at_ms") val acknowledgedAtMs: Long?,
	@ColumnInfo(name = "acknowledged_elapsed_realtime_nanos") val acknowledgedElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "failure_code") val failureCode: String?,
	@ColumnInfo(name = "retry_trigger") val retryTrigger: String?,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String?,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long?,
)

@Entity(
	tableName = "source_session_completeness",
	primaryKeys = [
		"logical_tracking_id",
		"service_run_id",
		"source_kind",
		"source_instance_id",
		"registration_generation",
	],
)
data class SourceSessionCompletenessEntity(
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "last_admission_ordinal") val lastAdmissionOrdinal: Long?,
	@ColumnInfo(name = "last_source_sequence") val lastSourceSequence: Long?,
	@ColumnInfo(name = "app_drain_complete") val appDrainComplete: Boolean,
	@ColumnInfo(name = "provider_coverage") val providerCoverage: String,
	@ColumnInfo(name = "stop_status") val stopStatus: String,
	@ColumnInfo(name = "unresolved_sequence_start") val unresolvedSequenceStart: Long?,
	@ColumnInfo(name = "unresolved_sequence_end") val unresolvedSequenceEnd: Long?,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
	init {
		require(serviceRunId.isNotBlank()) { "Completeness service run id must not be blank" }
	}
}

@Entity(tableName = "tracking_rollout_state")
data class TrackingRolloutStateEntity(
	@androidx.room.PrimaryKey val id: Int = 1,
	@ColumnInfo(name = "revision") val revision: Long,
	@ColumnInfo(name = "schema_version") val schemaVersion: Int,
	@ColumnInfo(name = "coordinator_mode") val coordinatorMode: String,
	@ColumnInfo(name = "projection_mode") val projectionMode: String,
	@ColumnInfo(name = "source_owners") val sourceOwners: String,
	@ColumnInfo(name = "semantic_settings_enabled") val semanticSettingsEnabled: Boolean,
	@ColumnInfo(name = "battery_estimate_mode") val batteryEstimateMode: String,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(tableName = "acquisition_plan_revision")
data class AcquisitionPlanRevisionEntity(
	@androidx.room.PrimaryKey
	@ColumnInfo(name = "revision") val revision: Long,
	@ColumnInfo(name = "plan_id") val planId: String,
	@ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
	@ColumnInfo(name = "status") val status: String,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long? = null,
)

@Entity(
	tableName = "source_desired_plan",
	primaryKeys = ["revision", "source_kind"],
)
data class SourceDesiredPlanEntity(
	@ColumnInfo(name = "revision") val revision: Long,
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int,
	@ColumnInfo(name = "payload") val payload: ByteArray,
	@ColumnInfo(name = "payload_checksum") val payloadChecksum: String,
)

@Entity(tableName = "source_applied_plan_state")
data class SourceAppliedPlanStateEntity(
	@androidx.room.PrimaryKey
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "desired_revision") val desiredRevision: Long,
	@ColumnInfo(name = "applied_revision") val appliedRevision: Long?,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String?,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long?,
	@ColumnInfo(name = "applied_at_elapsed_nanos") val appliedAtElapsedNanos: Long?,
	@ColumnInfo(name = "status") val status: String,
	@ColumnInfo(name = "degraded_reasons") val degradedReasons: String,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)
