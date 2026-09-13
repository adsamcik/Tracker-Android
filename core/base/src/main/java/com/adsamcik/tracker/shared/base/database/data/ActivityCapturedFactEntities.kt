package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Append-only authority and summary for one semantic revision of a captured Activity window.
 *
 * The stable window id excludes derived fragments. Late observations append a new semantic
 * revision and atomically replace the cursor; they never rewrite prior evidence.
 */
@Entity(
	tableName = "activity_captured_window_revision",
	primaryKeys = [
		"writer_projection_id",
		"writer_projection_version",
		"logical_window_id",
		"semantic_revision",
	],
	indices = [
		Index(
			value = ["writer_projection_id", "writer_projection_version", "mutation_id"],
			unique = true,
			name = "idx_activity_captured_window_mutation",
		),
		Index(
			value = ["service_run_id", "logical_tracking_id", "session_segment_id"],
			name = "idx_activity_captured_window_run_scope",
		),
	],
)
@Suppress("LongParameterList")
data class ActivityCapturedWindowRevisionEntity(
	@ColumnInfo(name = "writer_projection_id") val writerProjectionId: String,
	@ColumnInfo(name = "writer_projection_version") val writerProjectionVersion: Int,
	@ColumnInfo(name = "writer_binding_generation") val writerBindingGeneration: Long,
	@ColumnInfo(name = "writer_owner_generation") val writerOwnerGeneration: Long,
	@ColumnInfo(name = "logical_window_id") val logicalWindowId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "supersedes_semantic_revision") val supersedesSemanticRevision: Long?,
	@ColumnInfo(name = "mutation_id") val mutationId: String,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "session_segment_id") val sessionSegmentId: Long,
	@ColumnInfo(name = "purpose") val purpose: String,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "configuration_revision") val configurationRevision: Long,
	@ColumnInfo(name = "physical_configuration_fingerprint")
	val physicalConfigurationFingerprint: String,
	@ColumnInfo(name = "authorization_revision") val authorizationRevision: Long,
	@ColumnInfo(name = "authorization_fingerprint") val authorizationFingerprint: String,
	@ColumnInfo(name = "purpose_eligibility_mask") val purposeEligibilityMask: Long,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long,
	@ColumnInfo(name = "capture_consent_epoch") val captureConsentEpoch: Long,
	@ColumnInfo(name = "manifest_revision") val manifestRevision: Long,
	@ColumnInfo(name = "lifecycle_lease_generation") val lifecycleLeaseGeneration: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "clock_domain_id") val clockDomainId: String,
	@ColumnInfo(name = "stored_zone_id") val storedZoneId: String,
	@ColumnInfo(name = "provider_acceptance_start_nanos") val providerAcceptanceStartNanos: Long,
	@ColumnInfo(name = "provider_acceptance_end_nanos") val providerAcceptanceEndNanos: Long,
	@ColumnInfo(name = "authorization_effect_start_nanos") val authorizationEffectStartNanos: Long,
	@ColumnInfo(name = "authorization_effect_end_nanos") val authorizationEffectEndNanos: Long,
	@ColumnInfo(name = "session_run_effect_start_nanos") val sessionRunEffectStartNanos: Long,
	@ColumnInfo(name = "session_run_effect_end_nanos") val sessionRunEffectEndNanos: Long,
	@ColumnInfo(name = "window_start_elapsed_realtime_nanos") val windowStartElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "window_end_elapsed_realtime_nanos") val windowEndElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "coverage") val coverage: String,
	@ColumnInfo(name = "known_active_duration_nanos") val knownActiveDurationNanos: Long,
	@ColumnInfo(name = "known_inactive_duration_nanos") val knownInactiveDurationNanos: Long,
	@ColumnInfo(name = "unknown_activity_duration_nanos") val unknownActivityDurationNanos: Long,
	@ColumnInfo(name = "unobserved_duration_nanos") val unobservedDurationNanos: Long,
	@ColumnInfo(name = "exact_duplicate_count") val exactDuplicateCount: Int,
	@ColumnInfo(name = "semantic_duplicate_count") val semanticDuplicateCount: Int,
	@ColumnInfo(name = "unchanged_evidence_count") val unchangedEvidenceCount: Int,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
	@ColumnInfo(name = "applied_at_ms") val appliedAtMs: Long,
) {
	init {
		require(writerProjectionId == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID)
		require(writerProjectionVersion == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION)
		require(writerBindingGeneration == SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION)
		require(writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION)
		require(logicalWindowId.isNotBlank() && mutationId.isNotBlank())
		require(semanticRevision > 0L)
		require(
			if (semanticRevision == 1L) supersedesSemanticRevision == null
			else supersedesSemanticRevision == semanticRevision - 1L,
		)
		require(logicalTrackingId.isNotBlank() && serviceRunId.isNotBlank())
		require(sessionSegmentId > 0L)
		require(purpose == SourceBrokerPurpose.SESSION_CAPTURE)
		require(sourceInstanceId.isNotBlank())
		require(registrationGeneration > 0L && configurationRevision > 0L)
		require(physicalConfigurationFingerprint.isNotBlank())
		require(authorizationRevision > 0L && authorizationFingerprint.isNotBlank())
		require(purposeEligibilityMask and SourceBrokerPurpose.ALL_MASK == purposeEligibilityMask)
		require(purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L)
		require(sourcePolicyRevision > 0L && captureConsentEpoch >= 0L)
		require(manifestRevision > 0L && lifecycleLeaseGeneration > 0L)
		require(collectedDataEpoch >= 0L)
		require(clockDomainId.isNotBlank() && storedZoneId.isNotBlank())
		require(providerAcceptanceStartNanos in 0 until providerAcceptanceEndNanos)
		require(authorizationEffectStartNanos in 0 until authorizationEffectEndNanos)
		require(sessionRunEffectStartNanos in 0 until sessionRunEffectEndNanos)
		require(windowStartElapsedRealtimeNanos in 0 until windowEndElapsedRealtimeNanos)
		require(windowStartElapsedRealtimeNanos >= providerAcceptanceStartNanos)
		require(windowEndElapsedRealtimeNanos <= providerAcceptanceEndNanos)
		require(windowStartElapsedRealtimeNanos >= authorizationEffectStartNanos)
		require(windowEndElapsedRealtimeNanos <= authorizationEffectEndNanos)
		require(windowStartElapsedRealtimeNanos >= sessionRunEffectStartNanos)
		require(windowEndElapsedRealtimeNanos <= sessionRunEffectEndNanos)
		require(coverage in COVERAGES)
		require(knownActiveDurationNanos >= 0L && knownInactiveDurationNanos >= 0L)
		require(unknownActivityDurationNanos >= 0L && unobservedDurationNanos >= 0L)
		val duration = windowEndElapsedRealtimeNanos - windowStartElapsedRealtimeNanos
		require(
			knownActiveDurationNanos + knownInactiveDurationNanos +
				unknownActivityDurationNanos + unobservedDurationNanos == duration,
		)
		require(exactDuplicateCount >= 0 && semanticDuplicateCount >= 0 && unchangedEvidenceCount >= 0)
		require(scopeDeletionGeneration == 0L)
		require(effectChecksum.isNotBlank() && appliedAtMs >= 0L)
	}

	companion object {
		val COVERAGES = setOf("NONE", "PARTIAL", "COMPLETE")
	}
}

/** One band or explicit gap in the canonical tiling of a captured window revision. */
@Entity(
	tableName = "activity_captured_fragment",
	primaryKeys = [
		"writer_projection_id", "writer_projection_version", "logical_window_id",
		"semantic_revision", "fragment_ordinal",
	],
	foreignKeys = [
		ForeignKey(
			entity = ActivityCapturedWindowRevisionEntity::class,
			parentColumns = [
				"writer_projection_id", "writer_projection_version", "logical_window_id",
				"semantic_revision",
			],
			childColumns = [
				"writer_projection_id", "writer_projection_version", "logical_window_id",
				"semantic_revision",
			],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(
			value = [
				"writer_projection_id", "writer_projection_version", "logical_window_id",
				"semantic_revision",
			],
			name = "idx_activity_captured_fragment_parent",
		),
	],
)
@Suppress("LongParameterList")
data class ActivityCapturedFragmentEntity(
	@ColumnInfo(name = "writer_projection_id") val writerProjectionId: String,
	@ColumnInfo(name = "writer_projection_version") val writerProjectionVersion: Int,
	@ColumnInfo(name = "logical_window_id") val logicalWindowId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "fragment_ordinal") val fragmentOrdinal: Int,
	@ColumnInfo(name = "fragment_kind") val fragmentKind: String,
	@ColumnInfo(name = "band_ordinal") val bandOrdinal: Int?,
	@ColumnInfo(name = "interval_start_elapsed_realtime_nanos") val intervalStartElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "interval_end_elapsed_realtime_nanos") val intervalEndElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "gap_reason") val gapReason: String?,
	@ColumnInfo(name = "activity") val activity: String?,
	@ColumnInfo(name = "mechanism") val mechanism: String?,
	@ColumnInfo(name = "refined_transition_activity") val refinedTransitionActivity: String?,
	@ColumnInfo(name = "confidence_kind") val confidenceKind: String?,
	@ColumnInfo(name = "confidence_minimum_percent") val confidenceMinimumPercent: Int?,
	@ColumnInfo(name = "confidence_maximum_percent") val confidenceMaximumPercent: Int?,
	@ColumnInfo(name = "confidence_observation_count") val confidenceObservationCount: Int?,
	@ColumnInfo(name = "start_wall_time_ms") val startWallTimeMs: Long?,
	@ColumnInfo(name = "start_wall_time_uncertainty_ms") val startWallTimeUncertaintyMs: Long?,
	@ColumnInfo(name = "start_boundary_kind") val startBoundaryKind: String?,
	@ColumnInfo(name = "start_anchor_source_event_id") val startAnchorSourceEventId: String?,
	@ColumnInfo(name = "start_anchor_provider_elapsed_nanos") val startAnchorProviderElapsedNanos: Long?,
	@ColumnInfo(name = "end_wall_time_ms") val endWallTimeMs: Long?,
	@ColumnInfo(name = "end_wall_time_uncertainty_ms") val endWallTimeUncertaintyMs: Long?,
	@ColumnInfo(name = "end_boundary_kind") val endBoundaryKind: String?,
	@ColumnInfo(name = "end_anchor_source_event_id") val endAnchorSourceEventId: String?,
	@ColumnInfo(name = "end_anchor_provider_elapsed_nanos") val endAnchorProviderElapsedNanos: Long?,
	@ColumnInfo(name = "wall_time_continuity") val wallTimeContinuity: String?,
) {
	init {
		require(writerProjectionId == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID)
		require(writerProjectionVersion == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION)
		require(logicalWindowId.isNotBlank() && semanticRevision > 0L && fragmentOrdinal >= 0)
		require(intervalStartElapsedRealtimeNanos in 0 until intervalEndElapsedRealtimeNanos)
		when (fragmentKind) {
			KIND_GAP -> {
				require(gapReason?.isNotBlank() == true)
				require(bandOrdinal == null)
				require(BAND_ONLY_FIELDS.all { it(this) == null })
			}
			KIND_BAND -> {
				require(gapReason == null && bandOrdinal != null && bandOrdinal >= 0)
				require(activity?.isNotBlank() == true && mechanism?.isNotBlank() == true)
				require(confidenceKind?.isNotBlank() == true)
				require(startWallTimeMs != null && startWallTimeMs >= 0L)
				require(startWallTimeUncertaintyMs != null && startWallTimeUncertaintyMs >= 0L)
				require(startBoundaryKind?.isNotBlank() == true)
				require(startAnchorSourceEventId?.isNotBlank() == true)
				require(startAnchorProviderElapsedNanos != null && startAnchorProviderElapsedNanos >= 0L)
				require(endWallTimeMs != null && endWallTimeMs >= 0L)
				require(endWallTimeUncertaintyMs != null && endWallTimeUncertaintyMs >= 0L)
				require(endBoundaryKind?.isNotBlank() == true)
				require(endAnchorSourceEventId?.isNotBlank() == true)
				require(endAnchorProviderElapsedNanos != null && endAnchorProviderElapsedNanos >= 0L)
				require(wallTimeContinuity?.isNotBlank() == true)
				if (confidenceKind == CONFIDENCE_SAMPLED) {
					val minimum = requireNotNull(confidenceMinimumPercent)
					val maximum = requireNotNull(confidenceMaximumPercent)
					require(minimum in 0..100)
					require(maximum in minimum..100)
					require(confidenceObservationCount != null && confidenceObservationCount > 0)
				} else {
					require(confidenceKind == CONFIDENCE_TRANSITION)
					require(confidenceMinimumPercent == null && confidenceMaximumPercent == null)
					require(confidenceObservationCount == null)
				}
			}
			else -> require(false) { "Unknown captured Activity fragment kind $fragmentKind" }
		}
	}

	companion object {
		const val KIND_BAND = "BAND"
		const val KIND_GAP = "GAP"
		const val CONFIDENCE_TRANSITION = "TRANSITION_SIGNAL"
		const val CONFIDENCE_SAMPLED = "SAMPLED"
		private val BAND_ONLY_FIELDS: List<(ActivityCapturedFragmentEntity) -> Any?> = listOf(
			{ it.activity }, { it.mechanism }, { it.refinedTransitionActivity }, { it.confidenceKind },
			{ it.confidenceMinimumPercent }, { it.confidenceMaximumPercent },
			{ it.confidenceObservationCount }, { it.startWallTimeMs },
			{ it.startWallTimeUncertaintyMs }, { it.startBoundaryKind },
			{ it.startAnchorSourceEventId }, { it.startAnchorProviderElapsedNanos },
			{ it.endWallTimeMs }, { it.endWallTimeUncertaintyMs }, { it.endBoundaryKind },
			{ it.endAnchorSourceEventId }, { it.endAnchorProviderElapsedNanos },
			{ it.wallTimeContinuity },
		)
	}
}

/** Immutable provider evidence linked to one captured Activity band. */
@Entity(
	tableName = "activity_captured_evidence",
	primaryKeys = [
		"writer_projection_id", "writer_projection_version", "logical_window_id",
		"semantic_revision", "fragment_ordinal", "evidence_ordinal",
	],
	foreignKeys = [
		ForeignKey(
			entity = ActivityCapturedFragmentEntity::class,
			parentColumns = [
				"writer_projection_id", "writer_projection_version", "logical_window_id",
				"semantic_revision", "fragment_ordinal",
			],
			childColumns = [
				"writer_projection_id", "writer_projection_version", "logical_window_id",
				"semantic_revision", "fragment_ordinal",
			],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(
			value = [
				"writer_projection_id", "writer_projection_version", "logical_window_id",
				"semantic_revision", "fragment_ordinal",
			],
			name = "idx_activity_captured_evidence_fragment",
		),
	],
)
data class ActivityCapturedEvidenceEntity(
	@ColumnInfo(name = "writer_projection_id") val writerProjectionId: String,
	@ColumnInfo(name = "writer_projection_version") val writerProjectionVersion: Int,
	@ColumnInfo(name = "logical_window_id") val logicalWindowId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "fragment_ordinal") val fragmentOrdinal: Int,
	@ColumnInfo(name = "evidence_ordinal") val evidenceOrdinal: Int,
	@ColumnInfo(name = "source_event_id") val sourceEventId: String,
	@ColumnInfo(name = "source_admission_ordinal") val sourceAdmissionOrdinal: Long,
	@ColumnInfo(name = "source_sequence") val sourceSequence: Long,
	@ColumnInfo(name = "provider_elapsed_realtime_nanos") val providerElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "received_elapsed_realtime_nanos") val receivedElapsedRealtimeNanos: Long,
) {
	init {
		require(writerProjectionId == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID)
		require(writerProjectionVersion == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION)
		require(logicalWindowId.isNotBlank() && semanticRevision > 0L)
		require(fragmentOrdinal >= 0 && evidenceOrdinal >= 0)
		require(sourceEventId.isNotBlank() && sourceAdmissionOrdinal > 0L && sourceSequence > 0L)
		require(providerElapsedRealtimeNanos >= 0L)
		require(receivedElapsedRealtimeNanos >= providerElapsedRealtimeNanos)
	}
}

/** Monotonic pointer to the latest fully committed revision of one stable Activity window. */
@Entity(
	tableName = "activity_captured_window_cursor",
	primaryKeys = ["writer_projection_id", "writer_projection_version", "logical_window_id"],
	indices = [
		Index(
			value = ["service_run_id", "logical_tracking_id", "session_segment_id"],
			name = "idx_activity_captured_cursor_run_scope",
		),
	],
)
data class ActivityCapturedWindowCursorEntity(
	@ColumnInfo(name = "writer_projection_id") val writerProjectionId: String,
	@ColumnInfo(name = "writer_projection_version") val writerProjectionVersion: Int,
	@ColumnInfo(name = "logical_window_id") val logicalWindowId: String,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "session_segment_id") val sessionSegmentId: Long,
	@ColumnInfo(name = "writer_owner_generation") val writerOwnerGeneration: Long,
	@ColumnInfo(name = "latest_semantic_revision") val latestSemanticRevision: Long,
	@ColumnInfo(name = "latest_mutation_id") val latestMutationId: String,
	@ColumnInfo(name = "latest_effect_checksum") val latestEffectChecksum: String,
	@ColumnInfo(name = "cursor_revision") val cursorRevision: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
	init {
		require(writerProjectionId == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID)
		require(writerProjectionVersion == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION)
		require(logicalWindowId.isNotBlank() && logicalTrackingId.isNotBlank() && serviceRunId.isNotBlank())
		require(sessionSegmentId > 0L)
		require(writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION)
		require(latestSemanticRevision > 0L)
		require(latestMutationId.isNotBlank() && latestEffectChecksum.isNotBlank())
		require(cursorRevision > 0L && collectedDataEpoch >= 0L && updatedAtMs >= 0L)
	}
}
