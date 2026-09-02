package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Append-only, self-contained semantic history for one source-qualified Pressure window.
 *
 * Version 1 deliberately accepts only the frozen source-payload-v4 evidence shape. It preserves
 * the source statistics, realized coverage, provider request, immutable session authority, and
 * replay identities without deriving altitude or depending on the legacy [PressureSample] row.
 */
@Entity(
	tableName = "pressure_fact_revision",
	primaryKeys = [
		"writer_projection_id",
		"writer_projection_version",
		"logical_fact_id",
		"semantic_revision",
	],
	indices = [
		Index(
			value = ["writer_projection_id", "writer_projection_version", "mutation_id"],
			unique = true,
			name = "idx_pressure_fact_revision_mutation",
		),
		Index(
			value = [
				"writer_projection_id",
				"writer_projection_version",
				"source_admission_ordinal",
			],
			unique = true,
			name = "idx_pressure_fact_revision_writer_admission",
		),
		Index(
			value = [
				"writer_projection_id",
				"writer_projection_version",
				"logical_fact_id",
				"semantic_revision",
			],
			name = "idx_pressure_fact_revision_latest",
		),
	],
)
@Suppress("LongParameterList")
data class PressureFactRevisionEntity(
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "mutation_id") val mutationId: String,
	@ColumnInfo(name = "source_event_id") val sourceEventId: String,
	@ColumnInfo(name = "source_admission_ordinal") val sourceAdmissionOrdinal: Long,
	@ColumnInfo(name = "writer_projection_id") val writerProjectionId: String,
	@ColumnInfo(name = "writer_projection_version") val writerProjectionVersion: Int,
	@ColumnInfo(name = "writer_binding_generation") val writerBindingGeneration: Long,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int,
	@ColumnInfo(name = "interval_start_time_ms") val intervalStartTimeMs: Long,
	@ColumnInfo(name = "interval_end_time_ms") val intervalEndTimeMs: Long,
	@ColumnInfo(name = "window_start_elapsed_realtime_nanos")
	val windowStartElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "window_end_elapsed_realtime_nanos")
	val windowEndElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "clock_domain_id") val clockDomainId: String,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long,
	@ColumnInfo(name = "sample_count") val sampleCount: Int,
	@ColumnInfo(name = "mean_hectopascals") val meanHectopascals: Double,
	@ColumnInfo(name = "sum_squared_deviations") val sumSquaredDeviations: Double,
	@ColumnInfo(name = "minimum_hectopascals") val minimumHectopascals: Float,
	@ColumnInfo(name = "maximum_hectopascals") val maximumHectopascals: Float,
	@ColumnInfo(name = "first_provider_sequence") val firstProviderSequence: Long,
	@ColumnInfo(name = "last_provider_sequence") val lastProviderSequence: Long,
	@ColumnInfo(name = "first_hectopascals") val firstHectopascals: Float,
	@ColumnInfo(name = "last_hectopascals") val lastHectopascals: Float,
	@ColumnInfo(name = "slope_hectopascals_per_second") val slopeHectopascalsPerSecond: Double?,
	@ColumnInfo(name = "r_squared") val rSquared: Double?,
	@ColumnInfo(name = "sensor_accuracy") val sensorAccuracy: String,
	@ColumnInfo(name = "effective_sample_period_micros") val effectiveSamplePeriodMicros: Int,
	@ColumnInfo(name = "effective_maximum_report_latency_micros")
	val effectiveMaximumReportLatencyMicros: Int,
	@ColumnInfo(name = "target_window_duration_nanos") val targetWindowDurationNanos: Long,
	@ColumnInfo(name = "expected_sample_count") val expectedSampleCount: Int,
	@ColumnInfo(name = "maximum_inter_sample_gap_nanos") val maximumInterSampleGapNanos: Long,
	@ColumnInfo(name = "closure_kind") val closureKind: String,
	@ColumnInfo(name = "qualification") val qualification: String,
	@ColumnInfo(name = "source_quality_flags") val sourceQualityFlags: Long,
	@ColumnInfo(name = "source_quality_confidence") val sourceQualityConfidence: Float?,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "purpose") val purpose: String,
	@ColumnInfo(name = "manifest_revision") val manifestRevision: Long,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long,
	@ColumnInfo(name = "capture_consent_epoch") val captureConsentEpoch: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
	@ColumnInfo(name = "applied_at_ms") val appliedAtMs: Long,
) {
	init {
		requireIdentityAndAuthority()
		requireTimelineAndConfiguration()
		requireStatistics()
		requireQualification()
	}

	private fun requireIdentityAndAuthority() {
		require(logicalFactId.isNotBlank())
		require(semanticRevision > 0L)
		require(mutationId.isNotBlank())
		require(sourceEventId.isNotBlank())
		require(sourceAdmissionOrdinal > 0L)
		require(writerProjectionId == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID)
		require(writerProjectionVersion == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION)
		require(writerBindingGeneration == SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION)
		require(payloadVersion == QUALIFIED_PRESSURE_PAYLOAD_VERSION)
		require(clockDomainId.isNotBlank())
		require(wallTimeUncertaintyMs >= 0L)
		require(sourceQualityFlags >= 0L)
		require(sourceQualityConfidence == null || sourceQualityConfidence in 0f..1f)
		require(logicalTrackingId.isNotBlank())
		require(serviceRunId.isNotBlank())
		require(purpose == PURPOSE_SESSION_CAPTURE)
		require(manifestRevision > 0L)
		require(sourcePolicyRevision > 0L)
		require(captureConsentEpoch >= 0L)
		require(collectedDataEpoch >= 0L)
		require(effectChecksum.isNotBlank())
		require(appliedAtMs == intervalEndTimeMs)
	}

	private fun requireTimelineAndConfiguration() {
		require(intervalStartTimeMs >= 0L && intervalEndTimeMs >= intervalStartTimeMs)
		require(windowStartElapsedRealtimeNanos >= 0L)
		require(windowEndElapsedRealtimeNanos >= windowStartElapsedRealtimeNanos)
		val elapsedDurationMs = (windowEndElapsedRealtimeNanos - windowStartElapsedRealtimeNanos) /
			NANOS_PER_MILLISECOND
		require(intervalEndTimeMs - intervalStartTimeMs == elapsedDurationMs)
		require(firstProviderSequence > 0L)
		require(lastProviderSequence >= firstProviderSequence)
		require(lastProviderSequence - firstProviderSequence == sampleCount.toLong() - 1L)
		require(effectiveSamplePeriodMicros > 0)
		require(effectiveMaximumReportLatencyMicros >= 0)
		require(targetWindowDurationNanos > 0L)
		require(expectedSampleCount == expectedSampleCount(
			targetWindowDurationNanos,
			effectiveSamplePeriodMicros,
		))
		require(maximumInterSampleGapNanos >= 0L)
		require(closureKind in CLOSURE_KINDS)
		require(sensorAccuracy in SENSOR_ACCURACIES)
	}

	@Suppress("CyclomaticComplexMethod") // The constructor keeps all numeric invariants atomic.
	private fun requireStatistics() {
		require(sampleCount > 0)
		require(meanHectopascals.isFinite() && meanHectopascals > 0.0)
		require(sumSquaredDeviations.isFinite() && sumSquaredDeviations >= 0.0)
		require(minimumHectopascals.isFinite() && minimumHectopascals > 0f)
		require(maximumHectopascals.isFinite() && maximumHectopascals >= minimumHectopascals)
		require(meanHectopascals in minimumHectopascals.toDouble()..maximumHectopascals.toDouble())
		require(firstHectopascals.isFinite() && firstHectopascals in minimumHectopascals..maximumHectopascals)
		require(lastHectopascals.isFinite() && lastHectopascals in minimumHectopascals..maximumHectopascals)

		val observedSpan = windowEndElapsedRealtimeNanos - windowStartElapsedRealtimeNanos
		if (sampleCount == 1) {
			require(firstHectopascals == lastHectopascals)
			require(firstHectopascals == minimumHectopascals)
			require(lastHectopascals == maximumHectopascals)
			require(meanHectopascals == firstHectopascals.toDouble())
			require(sumSquaredDeviations == 0.0)
			require(observedSpan == 0L && maximumInterSampleGapNanos == 0L)
			require(slopeHectopascalsPerSecond == null && rSquared == null)
		} else {
			require(observedSpan > 0L && maximumInterSampleGapNanos in 1L..observedSpan)
			requireNotNull(slopeHectopascalsPerSecond).also { require(it.isFinite()) }
			if (sumSquaredDeviations > 0.0) {
				requireNotNull(rSquared).also { require(it.isFinite() && it in 0.0..1.0) }
			} else {
				require(rSquared == null)
			}
		}
	}

	private fun requireQualification() {
		require(qualification in QUALIFICATIONS)
		val samplePeriodNanos = effectiveSamplePeriodMicros.toLong() * NANOS_PER_MICROSECOND
		val requiredObservedSpan = saturatedMultiply(expectedSampleCount.toLong() - 1L, samplePeriodNanos)
		val observedSpan = windowEndElapsedRealtimeNanos - windowStartElapsedRealtimeNanos
		val noMissingCadence = samplePeriodNanos > Long.MAX_VALUE / 2L ||
			maximumInterSampleGapNanos < samplePeriodNanos * 2L
		val isComplete = closureKind == CLOSURE_TARGET_ELAPSED &&
			sampleCount >= expectedSampleCount && observedSpan >= requiredObservedSpan && noMissingCadence
		require((qualification == QUALIFICATION_COMPLETE) == isComplete)
	}

	/** Frozen wire and qualification vocabulary for the first Pressure fact revision. */
	companion object {
		const val QUALIFIED_PRESSURE_PAYLOAD_VERSION = 4
		const val PURPOSE_SESSION_CAPTURE = "SESSION_CAPTURE"
		const val CLOSURE_TARGET_ELAPSED = "TARGET_ELAPSED"
		const val CLOSURE_SOURCE_BOUNDARY = "SOURCE_BOUNDARY"
		const val QUALIFICATION_COMPLETE = "COMPLETE"
		const val QUALIFICATION_PARTIAL = "PARTIAL"
		const val SENSOR_ACCURACY_UNKNOWN = "UNKNOWN"
		const val SENSOR_ACCURACY_UNRELIABLE = "UNRELIABLE"
		const val SENSOR_ACCURACY_LOW = "LOW"
		const val SENSOR_ACCURACY_MEDIUM = "MEDIUM"
		const val SENSOR_ACCURACY_HIGH = "HIGH"

		private const val NANOS_PER_MICROSECOND = 1_000L
		private const val NANOS_PER_MILLISECOND = 1_000_000L
		private val CLOSURE_KINDS = setOf(CLOSURE_TARGET_ELAPSED, CLOSURE_SOURCE_BOUNDARY)
		private val QUALIFICATIONS = setOf(QUALIFICATION_COMPLETE, QUALIFICATION_PARTIAL)
		private val SENSOR_ACCURACIES = setOf(
			SENSOR_ACCURACY_UNKNOWN,
			SENSOR_ACCURACY_UNRELIABLE,
			SENSOR_ACCURACY_LOW,
			SENSOR_ACCURACY_MEDIUM,
			SENSOR_ACCURACY_HIGH,
		)

		private fun expectedSampleCount(targetNanos: Long, samplePeriodMicros: Int): Int {
			val samplePeriodNanos = samplePeriodMicros.toLong() * NANOS_PER_MICROSECOND
			val wholePeriods = targetNanos / samplePeriodNanos
			val roundedUp = wholePeriods + if (targetNanos % samplePeriodNanos == 0L) {
				0L
			} else {
				1L
			}
			return roundedUp.coerceAtLeast(1L).also { require(it <= Int.MAX_VALUE) }.toInt()
		}

		private fun saturatedMultiply(first: Long, second: Long): Long =
			if (first == 0L || second <= Long.MAX_VALUE / first) {
				first * second
			} else {
				Long.MAX_VALUE
			}
	}
}
