package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo

/**
 * Raw, nullable projection used before immutable manifest headers become lifecycle authority.
 */
@Suppress("LongParameterList")
data class RawSessionManifestVersion(
	@ColumnInfo(name = "storage_class_signature") val storageClassSignature: String?,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
	@ColumnInfo(name = "manifest_revision") val manifestRevision: Long?,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String?,
	@ColumnInfo(name = "session_mode") val sessionMode: String?,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long?,
	@ColumnInfo(name = "acquisition_plan_revision") val acquisitionPlanRevision: Long?,
	@ColumnInfo(name = "rollout_revision") val rolloutRevision: Long?,
	@ColumnInfo(name = "start_origin") val startOrigin: String?,
	@ColumnInfo(name = "effective_boot_id") val effectiveBootId: String?,
	@ColumnInfo(name = "effective_elapsed_realtime_nanos") val effectiveElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "effective_wall_time_ms") val effectiveWallTimeMs: Long?,
	@ColumnInfo(name = "zone_id") val zoneId: String?,
	@ColumnInfo(name = "automation_epoch") val automationEpoch: Long?,
	@ColumnInfo(name = "change_reason") val changeReason: String?,
	@ColumnInfo(name = "manifest_checksum") val manifestChecksum: String?,
) {
	@Suppress("ComplexCondition", "ReturnCount")
	fun validatedOrNull(): SessionManifestVersionEntity? {
		val storageClasses = storageClassSignature?.split(STORAGE_CLASS_SEPARATOR)
			?.takeIf { it.size == STORAGE_CLASS_COUNT }
			?: return null
		if (
			!storageClasses.hasExactClass(LOGICAL_TRACKING_ID_INDEX, SQLITE_TEXT) ||
			!storageClasses.hasExactClass(MANIFEST_REVISION_INDEX, SQLITE_INTEGER) ||
			!storageClasses.hasExactClass(SERVICE_RUN_ID_INDEX, SQLITE_TEXT) ||
			!storageClasses.hasExactClass(SESSION_MODE_INDEX, SQLITE_TEXT) ||
			!storageClasses.hasExactClass(SOURCE_POLICY_REVISION_INDEX, SQLITE_INTEGER) ||
			!storageClasses.hasExactClass(ACQUISITION_PLAN_REVISION_INDEX, SQLITE_INTEGER) ||
			!storageClasses.hasExactClass(ROLLOUT_REVISION_INDEX, SQLITE_INTEGER) ||
			!storageClasses.hasExactClass(START_ORIGIN_INDEX, SQLITE_TEXT) ||
			!storageClasses.hasExactClass(EFFECTIVE_BOOT_ID_INDEX, SQLITE_TEXT) ||
			!storageClasses.hasExactClass(EFFECTIVE_ELAPSED_INDEX, SQLITE_INTEGER) ||
			!storageClasses.hasExactClass(EFFECTIVE_WALL_INDEX, SQLITE_INTEGER) ||
			!storageClasses.hasExactClass(ZONE_ID_INDEX, SQLITE_TEXT) ||
			!storageClasses.hasNullableClass(AUTOMATION_EPOCH_INDEX, automationEpoch, SQLITE_INTEGER) ||
			!storageClasses.hasExactClass(CHANGE_REASON_INDEX, SQLITE_TEXT) ||
			!storageClasses.hasExactClass(MANIFEST_CHECKSUM_INDEX, SQLITE_TEXT)
		) {
			return null
		}
		val logicalId = logicalTrackingId?.takeIf(String::isNotBlank) ?: return null
		val revision = manifestRevision?.takeIf { it > 0L } ?: return null
		val runId = serviceRunId
			?.takeIf { it.isNotBlank() && it != LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID }
			?: return null
		val mode = sessionMode?.takeIf { it in SESSION_MODES } ?: return null
		val policyRevision = sourcePolicyRevision?.takeIf { it > 0L } ?: return null
		val planRevision = acquisitionPlanRevision?.takeIf { it > 0L } ?: return null
		val rollout = rolloutRevision?.takeIf { it > 0L } ?: return null
		val origin = startOrigin?.takeIf { it in START_ORIGINS } ?: return null
		val bootId = effectiveBootId?.takeIf(String::isNotBlank) ?: return null
		val elapsed = effectiveElapsedRealtimeNanos?.takeIf { it >= 0L } ?: return null
		val wall = effectiveWallTimeMs?.takeIf { it >= 0L } ?: return null
		val zone = zoneId?.takeIf(String::isNotBlank) ?: return null
		if (automationEpoch?.let { it < 0L } == true) return null
		val reason = changeReason?.takeIf(String::isNotBlank) ?: return null
		val checksum = manifestChecksum?.takeIf(DIGEST::matches) ?: return null
		return runCatching {
			SessionManifestVersionEntity(
				logicalTrackingId = logicalId,
				manifestRevision = revision,
				serviceRunId = runId,
				sessionMode = mode,
				sourcePolicyRevision = policyRevision,
				acquisitionPlanRevision = planRevision,
				rolloutRevision = rollout,
				startOrigin = origin,
				effectiveBootId = bootId,
				effectiveElapsedRealtimeNanos = elapsed,
				effectiveWallTimeMs = wall,
				zoneId = zone,
				automationEpoch = automationEpoch,
				changeReason = reason,
				manifestChecksum = checksum,
			)
		}.getOrNull()
	}

	private companion object {
		const val STORAGE_CLASS_SEPARATOR = '|'
		const val STORAGE_CLASS_COUNT = 15
		const val LOGICAL_TRACKING_ID_INDEX = 0
		const val MANIFEST_REVISION_INDEX = 1
		const val SERVICE_RUN_ID_INDEX = 2
		const val SESSION_MODE_INDEX = 3
		const val SOURCE_POLICY_REVISION_INDEX = 4
		const val ACQUISITION_PLAN_REVISION_INDEX = 5
		const val ROLLOUT_REVISION_INDEX = 6
		const val START_ORIGIN_INDEX = 7
		const val EFFECTIVE_BOOT_ID_INDEX = 8
		const val EFFECTIVE_ELAPSED_INDEX = 9
		const val EFFECTIVE_WALL_INDEX = 10
		const val ZONE_ID_INDEX = 11
		const val AUTOMATION_EPOCH_INDEX = 12
		const val CHANGE_REASON_INDEX = 13
		const val MANIFEST_CHECKSUM_INDEX = 14
		const val SQLITE_INTEGER = "integer"
		const val SQLITE_TEXT = "text"
		val SESSION_MODES = setOf("MANUAL", "AUTOMATIC", "LEGACY_UNKNOWN")
		val START_ORIGINS = setOf(
			"MANUAL_FOREGROUND_START",
			"AUTOMATIC_BACKGROUND_START",
			"RECOVERY",
			"POLICY_RECONCILIATION",
		)
		val DIGEST = Regex("[0-9a-f]{64}")
	}
}

/** Raw lifecycle intent projection used before a start intent authenticates a retirement action. */
@Suppress("LongParameterList")
data class RawSessionLifecycleIntentVersion(
	@ColumnInfo(name = "storage_class_signature") val storageClassSignature: String?,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
	@ColumnInfo(name = "intent_revision") val intentRevision: Long?,
	@ColumnInfo(name = "manifest_revision") val manifestRevision: Long?,
	@ColumnInfo(name = "desired_state") val desiredState: String?,
	@ColumnInfo(name = "start_origin") val startOrigin: String?,
	@ColumnInfo(name = "request_boot_id") val requestBootId: String?,
	@ColumnInfo(name = "requested_elapsed_realtime_nanos") val requestedElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "requested_wall_time_ms") val requestedWallTimeMs: Long?,
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
	@ColumnInfo(name = "stop_deadline_elapsed_realtime_nanos")
	val stopDeadlineElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "intent_checksum") val intentChecksum: String?,
	@ColumnInfo(name = "trigger_collected_data_epoch") val triggerCollectedDataEpoch: Long?,
) {
	@Suppress("ComplexCondition", "LongMethod", "ReturnCount")
	fun validatedOrNull(): SessionLifecycleIntentVersionEntity? {
		val storageClasses = storageClassSignature?.split(STORAGE_CLASS_SEPARATOR)
			?.takeIf { it.size == STORAGE_CLASS_COUNT }
			?: return null
		val values = listOf(
			logicalTrackingId,
			intentRevision,
			manifestRevision,
			desiredState,
			startOrigin,
			requestBootId,
			requestedElapsedRealtimeNanos,
			requestedWallTimeMs,
			automationEpoch,
			triggerId,
			triggerKind,
			triggerBootId,
			triggerObservedElapsedRealtimeNanos,
			triggerReceivedElapsedRealtimeNanos,
			triggerExpiresElapsedRealtimeNanos,
			stopReason,
			stopDeadlineBootId,
			stopDeadlineElapsedRealtimeNanos,
			intentChecksum,
			triggerCollectedDataEpoch,
		)
		val expectedClasses = listOf(
			SQLITE_TEXT,
			SQLITE_INTEGER,
			SQLITE_INTEGER,
			SQLITE_TEXT,
			SQLITE_TEXT,
			SQLITE_TEXT,
			SQLITE_INTEGER,
			SQLITE_INTEGER,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			SQLITE_TEXT,
			null,
		)
		if (storageClasses.indices.any { index ->
				val expected = expectedClasses[index]
				if (expected == null) {
					!storageClasses.hasNullableClass(index, values[index], nullableClass(index))
				} else {
					!storageClasses.hasExactClass(index, expected)
				}
			}
		) {
			return null
		}
		val logicalId = logicalTrackingId?.takeIf(String::isNotBlank) ?: return null
		val intent = intentRevision?.takeIf { it > 0L } ?: return null
		val manifest = manifestRevision?.takeIf { it > 0L } ?: return null
		val desired = desiredState?.takeIf { it in DESIRED_STATES } ?: return null
		val origin = startOrigin?.takeIf { it in START_ORIGINS } ?: return null
		val bootId = requestBootId?.takeIf(String::isNotBlank) ?: return null
		val elapsed = requestedElapsedRealtimeNanos?.takeIf { it >= 0L } ?: return null
		val wall = requestedWallTimeMs?.takeIf { it >= 0L } ?: return null
		if (
			automationEpoch?.let { it < 0L } == true ||
			triggerCollectedDataEpoch?.let { it < 0L } == true ||
			triggerObservedElapsedRealtimeNanos?.let { it < 0L } == true ||
			triggerReceivedElapsedRealtimeNanos?.let { it < 0L } == true ||
			triggerExpiresElapsedRealtimeNanos?.let { it < 0L } == true ||
			stopDeadlineElapsedRealtimeNanos?.let { it < 0L } == true ||
			listOf(
				triggerId,
				triggerKind,
				triggerBootId,
				stopReason,
				stopDeadlineBootId,
			).any { it?.isBlank() == true } ||
			(triggerObservedElapsedRealtimeNanos == null) !=
			(triggerReceivedElapsedRealtimeNanos == null) ||
			(triggerObservedElapsedRealtimeNanos == null) !=
			(triggerExpiresElapsedRealtimeNanos == null) ||
			(stopDeadlineBootId == null) != (stopDeadlineElapsedRealtimeNanos == null)
		) {
			return null
		}
		val checksum = intentChecksum?.takeIf(DIGEST::matches) ?: return null
		return SessionLifecycleIntentVersionEntity(
			logicalTrackingId = logicalId,
			intentRevision = intent,
			manifestRevision = manifest,
			desiredState = desired,
			startOrigin = origin,
			requestBootId = bootId,
			requestedElapsedRealtimeNanos = elapsed,
			requestedWallTimeMs = wall,
			automationEpoch = automationEpoch,
			triggerId = triggerId,
			triggerKind = triggerKind,
			triggerBootId = triggerBootId,
			triggerObservedElapsedRealtimeNanos = triggerObservedElapsedRealtimeNanos,
			triggerReceivedElapsedRealtimeNanos = triggerReceivedElapsedRealtimeNanos,
			triggerExpiresElapsedRealtimeNanos = triggerExpiresElapsedRealtimeNanos,
			stopReason = stopReason,
			stopDeadlineBootId = stopDeadlineBootId,
			stopDeadlineElapsedRealtimeNanos = stopDeadlineElapsedRealtimeNanos,
			intentChecksum = checksum,
			triggerCollectedDataEpoch = triggerCollectedDataEpoch,
		)
	}

	private fun nullableClass(index: Int): String = when (index) {
		AUTOMATION_EPOCH_INDEX,
		TRIGGER_OBSERVED_INDEX,
		TRIGGER_RECEIVED_INDEX,
		TRIGGER_EXPIRES_INDEX,
		STOP_DEADLINE_ELAPSED_INDEX,
		TRIGGER_COLLECTED_DATA_EPOCH_INDEX,
		-> SQLITE_INTEGER
		else -> SQLITE_TEXT
	}

	private companion object {
		const val STORAGE_CLASS_SEPARATOR = '|'
		const val STORAGE_CLASS_COUNT = 20
		const val AUTOMATION_EPOCH_INDEX = 8
		const val TRIGGER_OBSERVED_INDEX = 12
		const val TRIGGER_RECEIVED_INDEX = 13
		const val TRIGGER_EXPIRES_INDEX = 14
		const val STOP_DEADLINE_ELAPSED_INDEX = 17
		const val TRIGGER_COLLECTED_DATA_EPOCH_INDEX = 19
		const val SQLITE_INTEGER = "integer"
		const val SQLITE_TEXT = "text"
		val DESIRED_STATES = setOf("ACTIVE", "FINALIZED")
		val START_ORIGINS = setOf(
			"MANUAL_FOREGROUND_START",
			"AUTOMATIC_BACKGROUND_START",
			"RECOVERY",
			"POLICY_RECONCILIATION",
		)
		val DIGEST = Regex("[0-9a-f]{64}")
	}
}

/** Raw source-completeness projection used before terminal settlement authentication. */
@Suppress("LongParameterList")
data class RawSourceSessionCompleteness(
	@ColumnInfo(name = "storage_class_signature") val storageClassSignature: String?,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String?,
	@ColumnInfo(name = "source_kind") val sourceKind: Long?,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String?,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long?,
	@ColumnInfo(name = "last_admission_ordinal") val lastAdmissionOrdinal: Long?,
	@ColumnInfo(name = "last_source_sequence") val lastSourceSequence: Long?,
	@ColumnInfo(name = "app_drain_complete") val appDrainComplete: Long?,
	@ColumnInfo(name = "provider_coverage") val providerCoverage: String?,
	@ColumnInfo(name = "stop_status") val stopStatus: String?,
	@ColumnInfo(name = "unresolved_sequence_start") val unresolvedSequenceStart: Long?,
	@ColumnInfo(name = "unresolved_sequence_end") val unresolvedSequenceEnd: Long?,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long?,
) {
	@Suppress("ComplexCondition", "ReturnCount")
	fun validatedOrNull(): SourceSessionCompletenessEntity? {
		val storageClasses = storageClassSignature?.split(STORAGE_CLASS_SEPARATOR)
			?.takeIf { it.size == STORAGE_CLASS_COUNT }
			?: return null
		val values = listOf(
			logicalTrackingId,
			serviceRunId,
			sourceKind,
			sourceInstanceId,
			registrationGeneration,
			lastAdmissionOrdinal,
			lastSourceSequence,
			appDrainComplete,
			providerCoverage,
			stopStatus,
			unresolvedSequenceStart,
			unresolvedSequenceEnd,
			updatedAtMs,
		)
		val nullableIntegerIndexes = setOf(
			LAST_ADMISSION_INDEX,
			LAST_SOURCE_SEQUENCE_INDEX,
			UNRESOLVED_START_INDEX,
			UNRESOLVED_END_INDEX,
		)
		if (storageClasses.indices.any { index ->
				when {
					index in nullableIntegerIndexes ->
						!storageClasses.hasNullableClass(index, values[index], SQLITE_INTEGER)
					index in REQUIRED_TEXT_INDEXES ->
						!storageClasses.hasExactClass(index, SQLITE_TEXT)
					else -> !storageClasses.hasExactClass(index, SQLITE_INTEGER)
				}
			}
		) {
			return null
		}
		val logicalId = logicalTrackingId?.takeIf(String::isNotBlank) ?: return null
		val runId = serviceRunId?.takeIf(String::isNotBlank) ?: return null
		val source = sourceKind?.takeIf { it in SOURCE_KINDS }?.toInt() ?: return null
		val instance = sourceInstanceId?.takeIf(String::isNotBlank) ?: return null
		val generation = registrationGeneration?.takeIf { it >= 0L } ?: return null
		val drainComplete = when (appDrainComplete) {
			0L -> false
			1L -> true
			else -> return null
		}
		val coverage = providerCoverage?.takeIf { it in PROVIDER_COVERAGES } ?: return null
		val status = stopStatus?.takeIf { it in STOP_STATUSES } ?: return null
		val updatedAt = updatedAtMs?.takeIf { it in 0L until Long.MAX_VALUE } ?: return null
		if (
			lastAdmissionOrdinal?.let { it <= 0L } == true ||
			lastSourceSequence?.let { it < 0L } == true ||
			(lastAdmissionOrdinal == null) != (lastSourceSequence == null) ||
			unresolvedSequenceStart?.let { it <= 0L } == true ||
			unresolvedSequenceEnd?.let { it <= 0L } == true ||
			(unresolvedSequenceStart == null) != (unresolvedSequenceEnd == null) ||
			unresolvedSequenceStart?.let { it > requireNotNull(unresolvedSequenceEnd) } == true
		) {
			return null
		}
		return runCatching {
			SourceSessionCompletenessEntity(
				logicalTrackingId = logicalId,
				serviceRunId = runId,
				sourceKind = source,
				sourceInstanceId = instance,
				registrationGeneration = generation,
				lastAdmissionOrdinal = lastAdmissionOrdinal,
				lastSourceSequence = lastSourceSequence,
				appDrainComplete = drainComplete,
				providerCoverage = coverage,
				stopStatus = status,
				unresolvedSequenceStart = unresolvedSequenceStart,
				unresolvedSequenceEnd = unresolvedSequenceEnd,
				updatedAtMs = updatedAt,
			)
		}.getOrNull()
	}

	private companion object {
		const val STORAGE_CLASS_SEPARATOR = '|'
		const val STORAGE_CLASS_COUNT = 13
		const val LAST_ADMISSION_INDEX = 5
		const val LAST_SOURCE_SEQUENCE_INDEX = 6
		const val UNRESOLVED_START_INDEX = 10
		const val UNRESOLVED_END_INDEX = 11
		const val SQLITE_INTEGER = "integer"
		const val SQLITE_TEXT = "text"
		val REQUIRED_TEXT_INDEXES = setOf(0, 1, 3, 8, 9)
		val SOURCE_KINDS = (SourceDestinationOwnerEntity.SOURCE_LOCATION.toLong()..
			SourceDestinationOwnerEntity.SOURCE_CELL.toLong()).toSet()
		val PROVIDER_COVERAGES =
			setOf("CALLBACKS_ENTERED_BEFORE_BARRIER", "PROVIDER_COMPLETENESS_UNOBSERVABLE")
		val STOP_STATUSES = setOf(
			"COMPLETE",
			"PARTIAL_UNOBSERVABLE",
			"TIMED_OUT",
			"PERMISSION_LOST",
			"PROVIDER_FAILED",
			"PROCESS_RESTARTED",
		)
	}
}

private fun List<String>.hasExactClass(index: Int, expected: String): Boolean =
	getOrNull(index) == expected

private fun List<String>.hasNullableClass(
	index: Int,
	value: Any?,
	presentClass: String,
): Boolean = getOrNull(index) == if (value == null) "null" else presentClass
