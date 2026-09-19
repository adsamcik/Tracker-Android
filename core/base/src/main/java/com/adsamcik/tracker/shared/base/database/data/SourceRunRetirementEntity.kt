package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Durable lifecycle-side receipt for one exact provider generation owned by a service run.
 *
 * The provider operation may complete before the coordinator coroutine resumes. Persisting the
 * requested identity first and the full acknowledgement immediately after the physical boundary
 * lets a replacement process distinguish an acknowledged retirement from an interrupted partial.
 * A cleanup-only completion is terminal for the exact provisional provider generation but carries
 * no product-completeness acknowledgement.
 */
@Entity(
	tableName = "source_run_retirement",
	primaryKeys = [
		"logical_tracking_id",
		"service_run_id",
		"source_kind",
		"source_instance_id",
		"registration_generation",
	],
)
data class SourceRunRetirementEntity(
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "action_id") val actionId: String,
	@ColumnInfo(name = "attempt_count") val attemptCount: Int,
	@ColumnInfo(name = "lease_generation") val leaseGeneration: Long,
	@ColumnInfo(name = "cutoff_elapsed_realtime_nanos") val cutoffElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "cutoff_wall_time_ms") val cutoffWallTimeMs: Long,
	@ColumnInfo(name = "state") val state: String,
	@ColumnInfo(name = "applied_revision") val appliedRevision: Long?,
	@ColumnInfo(name = "callback_entry_barrier_sequence") val callbackEntryBarrierSequence: Long?,
	@ColumnInfo(name = "last_source_sequence") val lastSourceSequence: Long?,
	@ColumnInfo(name = "last_admission_ordinal") val lastAdmissionOrdinal: Long?,
	@ColumnInfo(name = "failed_admission_count") val failedAdmissionCount: Long?,
	@ColumnInfo(name = "unresolved_sequence_start") val unresolvedSequenceStart: Long?,
	@ColumnInfo(name = "unresolved_sequence_end") val unresolvedSequenceEnd: Long?,
	@ColumnInfo(name = "registration_removal_outcome") val registrationRemovalOutcome: String?,
	@ColumnInfo(name = "provider_flush_outcome") val providerFlushOutcome: String?,
	@ColumnInfo(name = "provider_coverage") val providerCoverage: String?,
	@ColumnInfo(name = "app_drain_complete") val appDrainComplete: Boolean?,
	@ColumnInfo(name = "stop_status") val stopStatus: String?,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
	init {
		require(logicalTrackingId.isNotBlank())
		require(serviceRunId.isNotBlank())
		require(sourceKind > 0)
		require(sourceInstanceId.isNotBlank())
		require(registrationGeneration > 0L)
		require(actionId.isNotBlank())
		require(attemptCount > 0)
		require(leaseGeneration > 0L)
		require(cutoffElapsedRealtimeNanos >= 0L)
		require(cutoffWallTimeMs >= 0L)
		require(state in STATES)
		require(updatedAtMs >= 0L)
		val requiredAcknowledgement = listOf(
			callbackEntryBarrierSequence,
			failedAdmissionCount,
			registrationRemovalOutcome,
			providerFlushOutcome,
			providerCoverage,
			appDrainComplete,
			stopStatus,
		)
		if (state in PAYLOAD_FREE_STATES) {
			require(
				(listOf(
					appliedRevision,
					lastSourceSequence,
					lastAdmissionOrdinal,
					unresolvedSequenceStart,
					unresolvedSequenceEnd,
				) + requiredAcknowledgement).all { it == null },
			)
			require(
				state != STATE_CLEANUP_ONLY_COMPLETED ||
					sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS,
			) {
				"Cleanup-only retirement receipts are reserved for Steps provisional cleanup"
			}
		} else {
			require(requiredAcknowledgement.all { it != null })
			require(
				(state == STATE_INTERRUPTED) ==
					(stopStatus == STOP_STATUS_PROCESS_RESTARTED),
			) {
				"Interrupted retirement state must correspond exactly to a process-restarted stop"
			}
		}
	}

	/**
	 * Nullable pre-validation projection for lifecycle recovery.
	 *
	 * Retirement rows are durable recovery input and may have been left malformed by an interrupted
	 * older writer or direct database corruption. Room must not construct the validated entity until
	 * the complete row shape has been checked.
	 */
	@Suppress("LongParameterList")
	data class RawSourceRunRetirement(
		@ColumnInfo(name = "storage_class_signature") val storageClassSignature: String?,
		@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
		@ColumnInfo(name = "service_run_id") val serviceRunId: String?,
		@ColumnInfo(name = "source_kind") val sourceKind: Long?,
		@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String?,
		@ColumnInfo(name = "registration_generation") val registrationGeneration: Long?,
		@ColumnInfo(name = "action_id") val actionId: String?,
		@ColumnInfo(name = "attempt_count") val attemptCount: Long?,
		@ColumnInfo(name = "lease_generation") val leaseGeneration: Long?,
		@ColumnInfo(name = "cutoff_elapsed_realtime_nanos") val cutoffElapsedRealtimeNanos: Long?,
		@ColumnInfo(name = "cutoff_wall_time_ms") val cutoffWallTimeMs: Long?,
		@ColumnInfo(name = "state") val state: String?,
		@ColumnInfo(name = "applied_revision") val appliedRevision: Long?,
		@ColumnInfo(name = "callback_entry_barrier_sequence")
		val callbackEntryBarrierSequence: Long?,
		@ColumnInfo(name = "last_source_sequence") val lastSourceSequence: Long?,
		@ColumnInfo(name = "last_admission_ordinal") val lastAdmissionOrdinal: Long?,
		@ColumnInfo(name = "failed_admission_count") val failedAdmissionCount: Long?,
		@ColumnInfo(name = "unresolved_sequence_start") val unresolvedSequenceStart: Long?,
		@ColumnInfo(name = "unresolved_sequence_end") val unresolvedSequenceEnd: Long?,
		@ColumnInfo(name = "registration_removal_outcome")
		val registrationRemovalOutcome: String?,
		@ColumnInfo(name = "provider_flush_outcome") val providerFlushOutcome: String?,
		@ColumnInfo(name = "provider_coverage") val providerCoverage: String?,
		@ColumnInfo(name = "app_drain_complete") val appDrainComplete: Long?,
		@ColumnInfo(name = "stop_status") val stopStatus: String?,
		@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long?,
	) {
		@Suppress("ReturnCount")
		fun validatedOrNull(): SourceRunRetirementEntity? {
			val storageClasses = storageClassSignature?.split(STORAGE_CLASS_SEPARATOR)
				?.takeIf { it.size == STORAGE_CLASS_COUNT }
				?: return null
			if (
				!storageClasses.hasExactClass(LOGICAL_TRACKING_ID_INDEX, SQLITE_TEXT) ||
				!storageClasses.hasExactClass(SERVICE_RUN_ID_INDEX, SQLITE_TEXT) ||
				!storageClasses.hasExactClass(SOURCE_KIND_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(SOURCE_INSTANCE_ID_INDEX, SQLITE_TEXT) ||
				!storageClasses.hasExactClass(REGISTRATION_GENERATION_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(ACTION_ID_INDEX, SQLITE_TEXT) ||
				!storageClasses.hasExactClass(ATTEMPT_COUNT_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(LEASE_GENERATION_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(CUTOFF_ELAPSED_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(CUTOFF_WALL_INDEX, SQLITE_INTEGER) ||
				!storageClasses.hasExactClass(STATE_INDEX, SQLITE_TEXT) ||
				!storageClasses.hasNullableClass(APPLIED_REVISION_INDEX, appliedRevision, SQLITE_INTEGER) ||
				!storageClasses.hasNullableClass(
					CALLBACK_BARRIER_INDEX,
					callbackEntryBarrierSequence,
					SQLITE_INTEGER,
				) ||
				!storageClasses.hasNullableClass(LAST_SOURCE_SEQUENCE_INDEX, lastSourceSequence, SQLITE_INTEGER) ||
				!storageClasses.hasNullableClass(
					LAST_ADMISSION_ORDINAL_INDEX,
					lastAdmissionOrdinal,
					SQLITE_INTEGER,
				) ||
				!storageClasses.hasNullableClass(
					FAILED_ADMISSION_COUNT_INDEX,
					failedAdmissionCount,
					SQLITE_INTEGER,
				) ||
				!storageClasses.hasNullableClass(
					UNRESOLVED_SEQUENCE_START_INDEX,
					unresolvedSequenceStart,
					SQLITE_INTEGER,
				) ||
				!storageClasses.hasNullableClass(
					UNRESOLVED_SEQUENCE_END_INDEX,
					unresolvedSequenceEnd,
					SQLITE_INTEGER,
				) ||
				!storageClasses.hasNullableClass(
					REGISTRATION_REMOVAL_INDEX,
					registrationRemovalOutcome,
					SQLITE_TEXT,
				) ||
				!storageClasses.hasNullableClass(
					PROVIDER_FLUSH_INDEX,
					providerFlushOutcome,
					SQLITE_TEXT,
				) ||
				!storageClasses.hasNullableClass(
					PROVIDER_COVERAGE_INDEX,
					providerCoverage,
					SQLITE_TEXT,
				) ||
				!storageClasses.hasNullableClass(APP_DRAIN_INDEX, appDrainComplete, SQLITE_INTEGER) ||
				!storageClasses.hasNullableClass(STOP_STATUS_INDEX, stopStatus, SQLITE_TEXT) ||
				!storageClasses.hasExactClass(UPDATED_AT_INDEX, SQLITE_INTEGER)
			) {
				return null
			}
			val validatedSourceKind = sourceKind
				?.takeIf { it in SOURCE_KINDS }
				?.toInt()
				?: return null
			val validatedLogicalTrackingId = logicalTrackingId
				?.takeIf(String::isNotBlank)
				?: return null
			val validatedServiceRunId = serviceRunId
				?.takeIf(String::isNotBlank)
				?: return null
			val validatedSourceInstanceId = sourceInstanceId
				?.takeIf(String::isNotBlank)
				?: return null
			val validatedRegistrationGeneration = registrationGeneration
				?.takeIf { it > 0L }
				?: return null
			val validatedActionId = actionId
				?.takeIf(String::isNotBlank)
				?: return null
			val validatedAttemptCount = attemptCount
				?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
				?.toInt()
				?: return null
			val validatedLeaseGeneration = leaseGeneration
				?.takeIf { it > 0L }
				?: return null
			val validatedCutoffElapsed = cutoffElapsedRealtimeNanos
				?.takeIf { it >= 0L }
				?: return null
			val validatedCutoffWall = cutoffWallTimeMs
				?.takeIf { it >= 0L }
				?: return null
			val validatedState = state
				?.takeIf { it in STATES }
				?: return null
			val validatedUpdatedAt = updatedAtMs
				?.takeIf { it >= 0L }
				?: return null
			val validatedDrain = when (appDrainComplete) {
				null -> null
				0L -> false
				1L -> true
				else -> return null
			}
			if (appliedRevision?.let { it < 0L } == true ||
				callbackEntryBarrierSequence?.let { it < 0L } == true ||
				lastSourceSequence?.let { it < 0L } == true ||
				lastAdmissionOrdinal?.let { it <= 0L } == true ||
				failedAdmissionCount?.let { it < 0L } == true ||
				unresolvedSequenceStart?.let { it <= 0L } == true ||
				unresolvedSequenceEnd?.let { it <= 0L } == true ||
				(unresolvedSequenceStart == null) != (unresolvedSequenceEnd == null) ||
				unresolvedSequenceStart?.let { start ->
					start > requireNotNull(unresolvedSequenceEnd)
				} == true ||
				registrationRemovalOutcome?.let { it !in REGISTRATION_REMOVAL_OUTCOMES } == true ||
				providerFlushOutcome?.let { it !in PROVIDER_FLUSH_OUTCOMES } == true ||
				providerCoverage?.let { it !in PROVIDER_COVERAGES } == true ||
				stopStatus?.let { it !in STOP_STATUSES } == true
			) {
				return null
			}
			val requiredAcknowledgement = listOf(
				callbackEntryBarrierSequence,
				failedAdmissionCount,
				registrationRemovalOutcome,
				providerFlushOutcome,
				providerCoverage,
				appDrainComplete,
				stopStatus,
			)
			if (validatedState in PAYLOAD_FREE_STATES) {
				val payload = listOf(
					appliedRevision,
					lastSourceSequence,
					lastAdmissionOrdinal,
					unresolvedSequenceStart,
					unresolvedSequenceEnd,
				) + requiredAcknowledgement
				if (payload.any { it != null }) return null
				if (
					validatedState == STATE_CLEANUP_ONLY_COMPLETED &&
					validatedSourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS
				) {
					return null
				}
			} else {
				if (requiredAcknowledgement.any { it == null }) return null
				if (
					(validatedState == STATE_INTERRUPTED) !=
					(stopStatus == STOP_STATUS_PROCESS_RESTARTED)
				) {
					return null
				}
			}
			return try {
				SourceRunRetirementEntity(
					logicalTrackingId = validatedLogicalTrackingId,
					serviceRunId = validatedServiceRunId,
					sourceKind = validatedSourceKind,
					sourceInstanceId = validatedSourceInstanceId,
					registrationGeneration = validatedRegistrationGeneration,
					actionId = validatedActionId,
					attemptCount = validatedAttemptCount,
					leaseGeneration = validatedLeaseGeneration,
					cutoffElapsedRealtimeNanos = validatedCutoffElapsed,
					cutoffWallTimeMs = validatedCutoffWall,
					state = validatedState,
					appliedRevision = appliedRevision,
					callbackEntryBarrierSequence = callbackEntryBarrierSequence,
					lastSourceSequence = lastSourceSequence,
					lastAdmissionOrdinal = lastAdmissionOrdinal,
					failedAdmissionCount = failedAdmissionCount,
					unresolvedSequenceStart = unresolvedSequenceStart,
					unresolvedSequenceEnd = unresolvedSequenceEnd,
					registrationRemovalOutcome = registrationRemovalOutcome,
					providerFlushOutcome = providerFlushOutcome,
					providerCoverage = providerCoverage,
					appDrainComplete = validatedDrain,
					stopStatus = stopStatus,
					updatedAtMs = validatedUpdatedAt,
				)
			} catch (_: IllegalArgumentException) {
				null
			}
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
			const val LOGICAL_TRACKING_ID_INDEX = 0
			const val SERVICE_RUN_ID_INDEX = 1
			const val SOURCE_KIND_INDEX = 2
			const val SOURCE_INSTANCE_ID_INDEX = 3
			const val REGISTRATION_GENERATION_INDEX = 4
			const val ACTION_ID_INDEX = 5
			const val ATTEMPT_COUNT_INDEX = 6
			const val LEASE_GENERATION_INDEX = 7
			const val CUTOFF_ELAPSED_INDEX = 8
			const val CUTOFF_WALL_INDEX = 9
			const val STATE_INDEX = 10
			const val APPLIED_REVISION_INDEX = 11
			const val CALLBACK_BARRIER_INDEX = 12
			const val LAST_SOURCE_SEQUENCE_INDEX = 13
			const val LAST_ADMISSION_ORDINAL_INDEX = 14
			const val FAILED_ADMISSION_COUNT_INDEX = 15
			const val UNRESOLVED_SEQUENCE_START_INDEX = 16
			const val UNRESOLVED_SEQUENCE_END_INDEX = 17
			const val REGISTRATION_REMOVAL_INDEX = 18
			const val PROVIDER_FLUSH_INDEX = 19
			const val PROVIDER_COVERAGE_INDEX = 20
			const val APP_DRAIN_INDEX = 21
			const val STOP_STATUS_INDEX = 22
			const val UPDATED_AT_INDEX = 23
			const val SQLITE_INTEGER = "integer"
			const val SQLITE_TEXT = "text"
			const val SQLITE_NULL = "null"
			val REGISTRATION_REMOVAL_OUTCOMES =
				setOf("REMOVED", "NOT_REGISTERED", "FAILED", "UNOBSERVABLE")
			val PROVIDER_FLUSH_OUTCOMES =
				setOf("COMPLETE", "NOT_SUPPORTED", "FAILED", "TIMED_OUT", "NOT_REQUESTED")
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

	companion object {
		const val STATE_REQUESTED = "REQUESTED"
		const val STATE_CLEANUP_ONLY_COMPLETED = "CLEANUP_ONLY_COMPLETED"
		const val STATE_ACKNOWLEDGED = "ACKNOWLEDGED"
		const val STATE_INTERRUPTED = "INTERRUPTED"
		private const val STOP_STATUS_PROCESS_RESTARTED = "PROCESS_RESTARTED"
		private val PAYLOAD_FREE_STATES = setOf(
			STATE_REQUESTED,
			STATE_CLEANUP_ONLY_COMPLETED,
		)
		val STATES = PAYLOAD_FREE_STATES + setOf(STATE_ACKNOWLEDGED, STATE_INTERRUPTED)
	}
}
