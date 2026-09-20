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
		val isPersistenceEligibleCapture =
			purpose == SourceBrokerPurpose.SESSION_CAPTURE && persistenceEligible
		when {
			isPersistenceEligibleCapture && outputDestination != null &&
				sourceKind == SourceDestinationOwnerEntity.SOURCE_ACTIVITY -> requireActivityWriter()
			isPersistenceEligibleCapture &&
				sourceKind == SourceDestinationOwnerEntity.SOURCE_ACTIVITY -> Unit
			isPersistenceEligibleCapture && outputDestination != null &&
				sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL -> requireCellWriter()
			isPersistenceEligibleCapture &&
				sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL -> Unit
			isPersistenceEligibleCapture &&
				sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS -> requireStepsWriter()
			isPersistenceEligibleCapture &&
				sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE -> requirePressureWriter()
			isPersistenceEligibleCapture &&
				sourceKind == SourceDestinationOwnerEntity.SOURCE_WIFI -> requireWifiWriter()
			else -> require(outputDestination == null && writerProjectionId == null) {
				"Only supported persistence-eligible capture sources may carry writer provenance"
			}
		}
	}

	/**
	 * Nullable pre-validation projection for immutable manifest authentication.
	 *
	 * SQLite storage classes and scalar ranges are authenticated before the validated entity is
	 * constructed, so Room never materializes malformed durable input as this validated entity.
	 */
	@Suppress("LongParameterList")
	data class RawSessionManifestSource(
		@ColumnInfo(name = "storage_class_signature") val storageClassSignature: String?,
		@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
		@ColumnInfo(name = "manifest_revision") val manifestRevision: Long?,
		@ColumnInfo(name = "source_kind") val sourceKind: Long?,
		@ColumnInfo(name = "purpose") val purpose: String?,
		@ColumnInfo(name = "consent_epoch") val consentEpoch: Long?,
		@ColumnInfo(name = "persistence_eligible") val persistenceEligible: Long?,
		@ColumnInfo(name = "qos_code") val qosCode: Long?,
		@ColumnInfo(name = "output_destination") val outputDestination: String?,
		@ColumnInfo(name = "writer_owner") val writerOwner: String?,
		@ColumnInfo(name = "writer_owner_generation") val writerOwnerGeneration: Long?,
		@ColumnInfo(name = "writer_projection_id") val writerProjectionId: String?,
		@ColumnInfo(name = "writer_projection_version") val writerProjectionVersion: Long?,
		@ColumnInfo(name = "writer_binding_generation") val writerBindingGeneration: Long?,
	) {
		@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
		fun validatedOrNull(): SessionManifestSourceEntity? {
			val storageClasses = storageClassSignature?.split(STORAGE_CLASS_SEPARATOR)
				?.takeIf { it.size == STORAGE_CLASS_COUNT }
				?: return null
			if (
				!storageClasses.hasExactClass(LOGICAL_TRACKING_ID_INDEX, SQLITE_TEXT) ||
				!storageClasses.hasExactClass(MANIFEST_REVISION_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(SOURCE_KIND_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(PURPOSE_INDEX, SQLITE_TEXT) ||
				!storageClasses.hasExactClass(CONSENT_EPOCH_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(PERSISTENCE_ELIGIBLE_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(QOS_CODE_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasNullableClass(
					OUTPUT_DESTINATION_INDEX,
					outputDestination,
					SQLITE_TEXT,
				) ||
				!storageClasses.hasNullableClass(WRITER_OWNER_INDEX, writerOwner, SQLITE_TEXT) ||
				!storageClasses.hasNullableClass(
					WRITER_OWNER_GENERATION_INDEX,
					writerOwnerGeneration,
					SQLITE_INTEGER,
				) ||
				!storageClasses.hasNullableClass(
					WRITER_PROJECTION_ID_INDEX,
					writerProjectionId,
					SQLITE_TEXT,
				) ||
				!storageClasses.hasNullableClass(
					WRITER_PROJECTION_VERSION_INDEX,
					writerProjectionVersion,
					SQLITE_INTEGER,
				) ||
				!storageClasses.hasNullableClass(
					WRITER_BINDING_GENERATION_INDEX,
					writerBindingGeneration,
					SQLITE_INTEGER,
				)
			) {
				return null
			}
			val validatedLogicalTrackingId = logicalTrackingId
				?.takeIf(String::isNotBlank)
				?: return null
			val validatedManifestRevision = manifestRevision
				?.takeIf { it > 0L }
				?: return null
			val validatedSourceKind = sourceKind
				?.takeIf { it in SOURCE_KINDS }
				?.toInt()
				?: return null
			val validatedPurpose = purpose
				?.takeIf { it in SessionManifestPurposeCode.ALL }
				?: return null
			val validatedConsentEpoch = consentEpoch
				?.takeIf { it >= 0L }
				?: return null
			val validatedPersistenceEligible = when (persistenceEligible) {
				0L -> false
				1L -> true
				else -> return null
			}
			val validatedQosCode = qosCode
				?.takeIf { it in MIN_QOS_CODE..MAX_QOS_CODE }
				?.toInt()
				?: return null
			if (
				validatedPurpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
				(!validatedPersistenceEligible || validatedQosCode == 0)
			) {
				return null
			}
			val validatedProjectionVersion = when (val version = writerProjectionVersion) {
				null -> null
				in 1L..Int.MAX_VALUE.toLong() -> version.toInt()
				else -> return null
			}
			if (
				outputDestination?.isBlank() == true ||
				writerOwner?.isBlank() == true ||
				writerOwnerGeneration?.let { it <= 0L } == true ||
				writerProjectionId?.isBlank() == true ||
				writerBindingGeneration?.let { it <= 0L } == true
			) {
				return null
			}
			val writerProvenance = listOf(outputDestination, writerOwner, writerOwnerGeneration)
			val projectionProvenance = listOf(
				writerProjectionId,
				writerProjectionVersion,
				writerBindingGeneration,
			)
			if (
				!(writerProvenance.all { it == null } || writerProvenance.all { it != null }) ||
				!(projectionProvenance.all { it == null } || projectionProvenance.all { it != null }) ||
				(writerProjectionId != null && outputDestination == null)
			) {
				return null
			}
			if (!hasValidWriterContract(
					sourceKind = validatedSourceKind,
					purpose = validatedPurpose,
					persistenceEligible = validatedPersistenceEligible,
					outputDestination = outputDestination,
					writerOwner = writerOwner,
					writerOwnerGeneration = writerOwnerGeneration,
					writerProjectionId = writerProjectionId,
					writerProjectionVersion = validatedProjectionVersion,
					writerBindingGeneration = writerBindingGeneration,
				)
			) {
				return null
			}
			return runCatching {
				SessionManifestSourceEntity(
					logicalTrackingId = validatedLogicalTrackingId,
					manifestRevision = validatedManifestRevision,
					sourceKind = validatedSourceKind,
					purpose = validatedPurpose,
					consentEpoch = validatedConsentEpoch,
					persistenceEligible = validatedPersistenceEligible,
					qosCode = validatedQosCode,
					outputDestination = outputDestination,
					writerOwner = writerOwner,
					writerOwnerGeneration = writerOwnerGeneration,
					writerProjectionId = writerProjectionId,
					writerProjectionVersion = validatedProjectionVersion,
					writerBindingGeneration = writerBindingGeneration,
				)
			}.getOrNull()
		}

		@Suppress("ComplexCondition", "LongParameterList")
		private fun hasValidWriterContract(
			sourceKind: Int,
			purpose: String,
			persistenceEligible: Boolean,
			outputDestination: String?,
			writerOwner: String?,
			writerOwnerGeneration: Long?,
			writerProjectionId: String?,
			writerProjectionVersion: Int?,
			writerBindingGeneration: Long?,
		): Boolean {
			if (purpose != SessionManifestPurposeCode.SESSION_CAPTURE || !persistenceEligible) {
				return outputDestination == null && writerProjectionId == null
			}
			return when (sourceKind) {
				SourceDestinationOwnerEntity.SOURCE_ACTIVITY -> outputDestination == null ||
					(outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY &&
						when (writerOwner) {
							SourceDestinationOwnerEntity.OWNER_LEGACY_ACTIVITY_SNAPSHOT ->
								writerOwnerGeneration ==
									SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION &&
									writerProjectionId == null
							SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS ->
								writerProjectionId ==
									SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID &&
									writerProjectionVersion ==
									SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION &&
									writerOwnerGeneration ==
									canonicalOwnerGenerationOrNull(writerBindingGeneration)
							else -> false
						})
				SourceDestinationOwnerEntity.SOURCE_CELL -> outputDestination == null ||
					(outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL &&
						writerOwner == SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS &&
						writerProjectionId == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID &&
						writerProjectionVersion ==
						SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION &&
						writerOwnerGeneration ==
						canonicalOwnerGenerationOrNull(writerBindingGeneration))
				SourceDestinationOwnerEntity.SOURCE_STEPS ->
					outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS &&
						when (writerOwner) {
							SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL ->
								writerProjectionId == null
							SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS ->
								writerProjectionId != null
							else -> false
						}
				SourceDestinationOwnerEntity.SOURCE_PRESSURE ->
					outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE &&
						when (writerOwner) {
							SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE ->
								writerOwnerGeneration ==
									SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION &&
									writerProjectionId == null
							SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS ->
								writerProjectionId ==
									SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID &&
									writerProjectionVersion ==
									SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION &&
									writerOwnerGeneration ==
									canonicalOwnerGenerationOrNull(writerBindingGeneration)
							else -> false
						}
				SourceDestinationOwnerEntity.SOURCE_WIFI -> outputDestination == null ||
					(outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI &&
						writerOwner == SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS &&
						writerProjectionId == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID &&
						writerProjectionVersion ==
						SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION &&
						writerOwnerGeneration ==
						canonicalOwnerGenerationOrNull(writerBindingGeneration))
				else -> outputDestination == null && writerProjectionId == null
			}
		}

		private fun canonicalOwnerGenerationOrNull(bindingGeneration: Long?): Long? =
			bindingGeneration?.let { generation ->
				runCatching {
					SourceWriterGenerationContract.canonicalOwnerGeneration(generation)
				}.getOrNull()
			}

		private fun List<String>.hasExactClass(index: Int, expected: String): Boolean =
			getOrNull(index) == expected

		private fun List<String>.hasNullableClass(
			index: Int,
			value: Any?,
			presentClass: String,
		): Boolean = getOrNull(index) == if (value == null) SQLITE_NULL else presentClass

		private companion object {
			const val STORAGE_CLASS_SEPARATOR = '|'
			const val STORAGE_CLASS_COUNT = 13
			const val LOGICAL_TRACKING_ID_INDEX = 0
			const val MANIFEST_REVISION_INDEX = 1
			const val SOURCE_KIND_INDEX = 2
			const val PURPOSE_INDEX = 3
			const val CONSENT_EPOCH_INDEX = 4
			const val PERSISTENCE_ELIGIBLE_INDEX = 5
			const val QOS_CODE_INDEX = 6
			const val OUTPUT_DESTINATION_INDEX = 7
			const val WRITER_OWNER_INDEX = 8
			const val WRITER_OWNER_GENERATION_INDEX = 9
			const val WRITER_PROJECTION_ID_INDEX = 10
			const val WRITER_PROJECTION_VERSION_INDEX = 11
			const val WRITER_BINDING_GENERATION_INDEX = 12
			const val MIN_QOS_CODE = 0L
			const val MAX_QOS_CODE = 3L
			const val SQLITE_INTEGER = "integer"
			const val SQLITE_TEXT = "text"
			const val SQLITE_NULL = "null"
			val SOURCE_KINDS = setOf(
				SourceDestinationOwnerEntity.SOURCE_LOCATION.toLong(),
				SourceDestinationOwnerEntity.SOURCE_ACTIVITY.toLong(),
				SourceDestinationOwnerEntity.SOURCE_STEPS.toLong(),
				SourceDestinationOwnerEntity.SOURCE_PRESSURE.toLong(),
				SourceDestinationOwnerEntity.SOURCE_WIFI.toLong(),
				SourceDestinationOwnerEntity.SOURCE_CELL.toLong(),
			)
		}
	}

	private fun requireActivityWriter() {
		require(outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY) {
			"Activity capture must target the permanent session Activity destination"
		}
		when (writerOwner) {
			SourceDestinationOwnerEntity.OWNER_LEGACY_ACTIVITY_SNAPSHOT -> {
				require(writerOwnerGeneration == SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION)
				require(writerProjectionId == null) {
					"Legacy Activity ownership must not claim candidate projection provenance"
				}
			}
			SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS -> {
				require(writerProjectionId == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID)
				require(writerProjectionVersion == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION)
				val bindingGeneration = requireNotNull(writerBindingGeneration)
				require(writerOwnerGeneration ==
					SourceWriterGenerationContract.canonicalOwnerGeneration(bindingGeneration))
			}
			else -> require(false) { "Activity capture must name a permanent destination owner" }
		}
	}

	private fun requireCellWriter() {
		require(outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL) {
			"Cell capture must target the permanent session Cell destination"
		}
		require(writerOwner == SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS)
		require(writerProjectionId == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID)
		require(writerProjectionVersion == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION)
		val bindingGeneration = requireNotNull(writerBindingGeneration)
		require(writerOwnerGeneration ==
			SourceWriterGenerationContract.canonicalOwnerGeneration(bindingGeneration))
	}

	private fun requireStepsWriter() {
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
	}

	private fun requirePressureWriter() {
		require(outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE) {
			"Pressure capture must target the permanent session Pressure destination"
		}
		when (writerOwner) {
			SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE -> {
				require(writerOwnerGeneration == SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION) {
					"Legacy Pressure ownership requires its permanent generation"
				}
				require(writerProjectionId == null) {
					"Legacy Pressure ownership must not claim candidate projection provenance"
				}
			}
			SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS -> {
				require(writerProjectionId == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID)
				require(
					writerProjectionVersion ==
						SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
				)
				val bindingGeneration = requireNotNull(writerBindingGeneration)
				require(writerOwnerGeneration ==
					SourceWriterGenerationContract.canonicalOwnerGeneration(bindingGeneration))
			}
			else -> require(false) { "Pressure capture must name a permanent destination owner" }
		}
	}

	private fun requireWifiWriter() {
		if (outputDestination == null) {
			require(writerOwner == null && writerOwnerGeneration == null)
			require(writerProjectionId == null && writerProjectionVersion == null &&
				writerBindingGeneration == null)
			return
		}
		require(outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI)
		require(writerOwner == SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS)
		require(writerProjectionId == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID)
		require(writerProjectionVersion == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION)
		val bindingGeneration = requireNotNull(writerBindingGeneration)
		require(writerOwnerGeneration ==
			SourceWriterGenerationContract.canonicalOwnerGeneration(bindingGeneration))
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
	/** Opaque engine-issued caller authority reference; never interpreted outside the engine. */
	@ColumnInfo(name = "source_caller_authority_reference")
	val sourceCallerAuthorityReference: String? = null,
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
) {
	/**
	 * Nullable pre-validation projection for retirement authority.
	 *
	 * Retirement runs after the session has entered STOPPING. Durable action corruption must
	 * therefore be rejected before provider/value-class construction rather than surfacing as an
	 * exception after the physical lifecycle boundary.
	 */
	@Suppress("LongParameterList")
	data class RawLifecycleDesiredAction(
		@ColumnInfo(name = "storage_class_signature") val storageClassSignature: String?,
		@ColumnInfo(name = "action_id") val actionId: String?,
		@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
		@ColumnInfo(name = "service_run_id") val serviceRunId: String?,
		@ColumnInfo(name = "manifest_revision") val manifestRevision: Long?,
		@ColumnInfo(name = "action_revision") val actionRevision: Long?,
		@ColumnInfo(name = "action_family") val actionFamily: String?,
		@ColumnInfo(name = "source_kind") val sourceKind: Long?,
		@ColumnInfo(name = "desired_state") val desiredState: String?,
		@ColumnInfo(name = "desired_plan_revision") val desiredPlanRevision: Long?,
		@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long?,
		@ColumnInfo(name = "consent_epoch") val consentEpoch: Long?,
		@ColumnInfo(name = "start_origin") val startOrigin: String?,
		@ColumnInfo(name = "boot_id") val bootId: String?,
		@ColumnInfo(name = "lease_generation") val leaseGeneration: Long?,
		@ColumnInfo(name = "requested_at_ms") val requestedAtMs: Long?,
		@ColumnInfo(name = "requested_elapsed_realtime_nanos")
		val requestedElapsedRealtimeNanos: Long?,
		@ColumnInfo(name = "status") val status: String?,
		@ColumnInfo(name = "attempt_count") val attemptCount: Long?,
		@ColumnInfo(name = "acknowledged_at_ms") val acknowledgedAtMs: Long?,
		@ColumnInfo(name = "acknowledged_elapsed_realtime_nanos")
		val acknowledgedElapsedRealtimeNanos: Long?,
		@ColumnInfo(name = "failure_code") val failureCode: String?,
		@ColumnInfo(name = "retry_trigger") val retryTrigger: String?,
		@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String?,
		@ColumnInfo(name = "registration_generation") val registrationGeneration: Long?,
	) {
		@Suppress("ComplexCondition", "ReturnCount")
		fun validatedOrNull(): LifecycleDesiredActionEntity? {
			val storageClasses = storageClassSignature?.split(STORAGE_CLASS_SEPARATOR)
				?.takeIf { it.size == STORAGE_CLASS_COUNT }
				?: return null
			if (
				!storageClasses.hasExactClass(ACTION_ID_INDEX, SQLITE_TEXT) ||
				!storageClasses.hasExactClass(LOGICAL_TRACKING_ID_INDEX, SQLITE_TEXT) ||
				!storageClasses.hasExactClass(SERVICE_RUN_ID_INDEX, SQLITE_TEXT) ||
				!storageClasses.hasExactClass(MANIFEST_REVISION_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(ACTION_REVISION_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(ACTION_FAMILY_INDEX, SQLITE_TEXT) ||
				!storageClasses.hasNullableClass(SOURCE_KIND_INDEX, sourceKind, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(DESIRED_STATE_INDEX, SQLITE_TEXT) ||
				!storageClasses.hasExactClass(DESIRED_PLAN_REVISION_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(SOURCE_POLICY_REVISION_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasNullableClass(CONSENT_EPOCH_INDEX, consentEpoch, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(START_ORIGIN_INDEX, SQLITE_TEXT) ||
				!storageClasses.hasExactClass(BOOT_ID_INDEX, SQLITE_TEXT) ||
				!storageClasses.hasExactClass(LEASE_GENERATION_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(REQUESTED_AT_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(REQUESTED_ELAPSED_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(STATUS_INDEX, SQLITE_TEXT) ||
				!storageClasses.hasExactClass(ATTEMPT_COUNT_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasNullableClass(
					ACKNOWLEDGED_AT_INDEX,
					acknowledgedAtMs,
					SQLITE_INTEGER,
				) ||
				!storageClasses.hasNullableClass(
					ACKNOWLEDGED_ELAPSED_INDEX,
					acknowledgedElapsedRealtimeNanos,
					SQLITE_INTEGER,
				) ||
				!storageClasses.hasNullableClass(FAILURE_CODE_INDEX, failureCode, SQLITE_TEXT) ||
				!storageClasses.hasNullableClass(RETRY_TRIGGER_INDEX, retryTrigger, SQLITE_TEXT) ||
				!storageClasses.hasNullableClass(
					SOURCE_INSTANCE_ID_INDEX,
					sourceInstanceId,
					SQLITE_TEXT,
				) ||
				!storageClasses.hasNullableClass(
					REGISTRATION_GENERATION_INDEX,
					registrationGeneration,
					SQLITE_INTEGER,
				)
			) {
				return null
			}
			val validatedActionId = actionId?.takeIf(ACTION_ID::matches) ?: return null
			val validatedLogicalTrackingId =
				logicalTrackingId?.takeIf(String::isNotBlank) ?: return null
			val validatedServiceRunId = serviceRunId?.takeIf(String::isNotBlank) ?: return null
			val validatedManifestRevision = manifestRevision?.takeIf { it > 0L } ?: return null
			val validatedActionRevision = actionRevision?.takeIf { it > 0L } ?: return null
			val validatedActionFamily = actionFamily
				?.takeIf { it in ACTION_FAMILIES }
				?: return null
			val validatedSourceKind = sourceKind
				?.takeIf { it in SOURCE_KINDS }
				?.toInt()
				?: return null
			val validatedDesiredState = desiredState
				?.takeIf { it in DESIRED_STATES }
				?: return null
			val validatedDesiredPlanRevision =
				desiredPlanRevision?.takeIf { it > 0L } ?: return null
			val validatedSourcePolicyRevision =
				sourcePolicyRevision?.takeIf { it > 0L } ?: return null
			if (consentEpoch?.let { it < 0L } == true) return null
			val validatedStartOrigin = startOrigin
				?.takeIf { it in START_ORIGINS }
				?: return null
			val validatedBootId = bootId?.takeIf(String::isNotBlank) ?: return null
			val validatedLeaseGeneration =
				leaseGeneration?.takeIf { it > 0L } ?: return null
			val validatedRequestedAt = requestedAtMs?.takeIf { it >= 0L } ?: return null
			val validatedRequestedElapsed =
				requestedElapsedRealtimeNanos?.takeIf { it >= 0L } ?: return null
			val validatedStatus = status?.takeIf { it in STATUSES } ?: return null
			val validatedAttemptCount = attemptCount
				?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }
				?.toInt()
				?: return null
			if (
				acknowledgedAtMs?.let { it < 0L } == true ||
				acknowledgedElapsedRealtimeNanos?.let { it < 0L } == true ||
				failureCode?.isBlank() == true ||
				retryTrigger?.isBlank() == true ||
				sourceInstanceId?.isBlank() == true ||
				registrationGeneration?.let { it <= 0L } == true ||
				(sourceInstanceId == null) != (registrationGeneration == null)
			) {
				return null
			}
			return LifecycleDesiredActionEntity(
				actionId = validatedActionId,
				logicalTrackingId = validatedLogicalTrackingId,
				serviceRunId = validatedServiceRunId,
				manifestRevision = validatedManifestRevision,
				actionRevision = validatedActionRevision,
				actionFamily = validatedActionFamily,
				sourceKind = validatedSourceKind,
				desiredState = validatedDesiredState,
				desiredPlanRevision = validatedDesiredPlanRevision,
				sourcePolicyRevision = validatedSourcePolicyRevision,
				consentEpoch = consentEpoch,
				startOrigin = validatedStartOrigin,
				bootId = validatedBootId,
				leaseGeneration = validatedLeaseGeneration,
				requestedAtMs = validatedRequestedAt,
				requestedElapsedRealtimeNanos = validatedRequestedElapsed,
				status = validatedStatus,
				attemptCount = validatedAttemptCount,
				acknowledgedAtMs = acknowledgedAtMs,
				acknowledgedElapsedRealtimeNanos = acknowledgedElapsedRealtimeNanos,
				failureCode = failureCode,
				retryTrigger = retryTrigger,
				sourceInstanceId = sourceInstanceId,
				registrationGeneration = registrationGeneration,
			)
		}

		private fun List<String>.hasExactClass(index: Int, expected: String): Boolean =
			getOrNull(index) == expected

		private fun List<String>.hasNullableClass(
			index: Int,
			value: Any?,
			presentClass: String,
		): Boolean = getOrNull(index) == if (value == null) SQLITE_NULL else presentClass

		private companion object {
			const val STORAGE_CLASS_SEPARATOR = '|'
			const val STORAGE_CLASS_COUNT = 24
			const val ACTION_ID_INDEX = 0
			const val LOGICAL_TRACKING_ID_INDEX = 1
			const val SERVICE_RUN_ID_INDEX = 2
			const val MANIFEST_REVISION_INDEX = 3
			const val ACTION_REVISION_INDEX = 4
			const val ACTION_FAMILY_INDEX = 5
			const val SOURCE_KIND_INDEX = 6
			const val DESIRED_STATE_INDEX = 7
			const val DESIRED_PLAN_REVISION_INDEX = 8
			const val SOURCE_POLICY_REVISION_INDEX = 9
			const val CONSENT_EPOCH_INDEX = 10
			const val START_ORIGIN_INDEX = 11
			const val BOOT_ID_INDEX = 12
			const val LEASE_GENERATION_INDEX = 13
			const val REQUESTED_AT_INDEX = 14
			const val REQUESTED_ELAPSED_INDEX = 15
			const val STATUS_INDEX = 16
			const val ATTEMPT_COUNT_INDEX = 17
			const val ACKNOWLEDGED_AT_INDEX = 18
			const val ACKNOWLEDGED_ELAPSED_INDEX = 19
			const val FAILURE_CODE_INDEX = 20
			const val RETRY_TRIGGER_INDEX = 21
			const val SOURCE_INSTANCE_ID_INDEX = 22
			const val REGISTRATION_GENERATION_INDEX = 23
			const val SQLITE_INTEGER = "integer"
			const val SQLITE_TEXT = "text"
			const val SQLITE_NULL = "null"
			val ACTION_FAMILIES = setOf("SOURCE_RUNTIME")
			val ACTION_ID = Regex("[0-9a-f]{64}")
			val DESIRED_STATES = setOf("STARTED", "STOPPED")
			val START_ORIGINS = setOf(
				"MANUAL_FOREGROUND_START",
				"AUTOMATIC_BACKGROUND_START",
				"RECOVERY",
				"POLICY_RECONCILIATION",
			)
			val STATUSES = setOf(
				"AWAITING_FOREGROUND",
				"PENDING",
				"APPLYING",
				"CLEANUP_REQUIRED",
				"START_ACCEPTED",
				"TEMPORARILY_ILLEGAL",
				"TERMINAL_FAILURE",
				"STOP_ACCEPTED",
				"SUPERSEDED",
			)
			val SOURCE_KINDS = setOf(
				SourceDestinationOwnerEntity.SOURCE_LOCATION.toLong(),
				SourceDestinationOwnerEntity.SOURCE_ACTIVITY.toLong(),
				SourceDestinationOwnerEntity.SOURCE_STEPS.toLong(),
				SourceDestinationOwnerEntity.SOURCE_PRESSURE.toLong(),
				SourceDestinationOwnerEntity.SOURCE_WIFI.toLong(),
				SourceDestinationOwnerEntity.SOURCE_CELL.toLong(),
			)
		}
	}
}

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
	@ColumnInfo(name = "applied_payload_version") val appliedPayloadVersion: Int? = null,
	@ColumnInfo(name = "applied_payload") val appliedPayload: ByteArray? = null,
	@ColumnInfo(name = "applied_payload_checksum") val appliedPayloadChecksum: String? = null,
)
