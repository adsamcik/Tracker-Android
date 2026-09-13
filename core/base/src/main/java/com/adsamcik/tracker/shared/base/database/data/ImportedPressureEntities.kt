package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.security.MessageDigest
import java.time.ZoneId

/**
 * One immutable observation of a portable Pressure entry.
 *
 * [importRevision] is destination-local receipt order, not a claim about the exporting database's
 * semantic revision. A later authenticated import may append a successor without replacing the
 * evidence that was previously received. Receipt identifiers are copied as provenance because the
 * generic receipt rows are mutable workflow state and therefore cannot safely own imported facts.
 */
@Entity(
	tableName = "imported_pressure_entry_revision",
	primaryKeys = ["identity", "import_revision"],
	indices = [
		Index(
			value = ["import_job_id", "import_entry_key"],
			unique = true,
			name = "idx_imported_pressure_entry_receipt",
		),
	],
)
data class ImportedPressureEntryRevisionEntity(
	@ColumnInfo(name = "identity") val identity: String,
	@ColumnInfo(name = "import_revision") val importRevision: Long,
	@ColumnInfo(name = "supersedes_import_revision") val supersedesImportRevision: Long?,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "source_format") val sourceFormat: String,
	@ColumnInfo(name = "source_schema_version") val sourceSchemaVersion: Int,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "import_job_id") val importJobId: String,
	@ColumnInfo(name = "import_entry_key") val importEntryKey: String,
	@ColumnInfo(name = "import_source_name") val importSourceName: String,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
) {
	init {
		require(ImportedPressureIdentity.isOpaque(identity))
		require(ImportedPressureIdentity.isOpaque(contentChecksum))
		require(importRevision > 0L)
		require(
			(importRevision == 1L && supersedesImportRevision == null) ||
				(supersedesImportRevision != null && supersedesImportRevision in 1L until importRevision),
		)
		require(sourceFormat == SOURCE_FORMAT && sourceSchemaVersion == SOURCE_SCHEMA_VERSION)
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(collectedDataEpoch >= 0L && receivedAtMs >= 0L)
		ImportedPressureIdentity.requireProvenance(importJobId, importEntryKey, importSourceName)
	}

	companion object {
		const val SOURCE_FORMAT = "tracker-portable-pressure"
		const val SOURCE_SCHEMA_VERSION = 1
	}
}

/** Exact foreign physical membership, without any fabricated local run/provider authority. */
@Entity(
	tableName = "imported_pressure_run",
	primaryKeys = ["entry_identity", "entry_import_revision", "identity"],
	foreignKeys = [ForeignKey(
		entity = ImportedPressureEntryRevisionEntity::class,
		parentColumns = ["identity", "import_revision"],
		childColumns = ["entry_identity", "entry_import_revision"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["entry_identity", "entry_import_revision"],
			name = "idx_imported_pressure_run_entry",
		),
		Index(value = ["identity"], name = "idx_imported_pressure_run_identity"),
	],
)
data class ImportedPressureRunEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "identity") val identity: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "captured_for_whole_run") val capturedForWholeRun: Boolean,
	@ColumnInfo(name = "availability") val availability: String,
	@ColumnInfo(name = "coverage") val coverage: String,
	@ColumnInfo(name = "retention_loss") val retentionLoss: Boolean,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
) {
	init {
		require(ImportedPressureIdentity.isOpaque(entryIdentity))
		require(ImportedPressureIdentity.isOpaque(identity))
		require(entryImportRevision > 0L)
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(availability in AVAILABILITIES && coverage in COVERAGES)
		require(collectedDataEpoch >= 0L && scopeDeletionGeneration >= 0L)
		if (availability == AVAILABILITY_NO_RETAINED_OBSERVATION) {
			require(retentionLoss && coverage == COVERAGE_PARTIAL)
		}
		if (availability in NON_NUMERIC_FINAL_AVAILABILITIES) {
			require(coverage == COVERAGE_NONE && !retentionLoss)
		}
		if (retentionLoss) {
			require(coverage == COVERAGE_PARTIAL)
			require(availability in RETENTION_LOSS_AVAILABILITIES)
		}
		if (coverage == COVERAGE_COMPLETE) require(capturedForWholeRun && !retentionLoss)
	}

	companion object {
		const val AVAILABILITY_RETAINED = "RETAINED"
		const val AVAILABILITY_NO_RETAINED_OBSERVATION = "NO_RETAINED_OBSERVATION"
		const val AVAILABILITY_DISABLED = "DISABLED"
		const val AVAILABILITY_DELETED = "DELETED"
		const val AVAILABILITY_UNAVAILABLE = "UNAVAILABLE"
		const val COVERAGE_NONE = "NONE"
		const val COVERAGE_COMPLETE = "COMPLETE"
		const val COVERAGE_PARTIAL = "PARTIAL"
		const val COVERAGE_UNKNOWN = "UNKNOWN"

		private val AVAILABILITIES = setOf(
			AVAILABILITY_RETAINED,
			AVAILABILITY_NO_RETAINED_OBSERVATION,
			AVAILABILITY_DISABLED,
			AVAILABILITY_DELETED,
			AVAILABILITY_UNAVAILABLE,
		)
		private val COVERAGES = setOf(
			COVERAGE_NONE,
			COVERAGE_COMPLETE,
			COVERAGE_PARTIAL,
			COVERAGE_UNKNOWN,
		)
		private val NON_NUMERIC_FINAL_AVAILABILITIES = setOf(
			AVAILABILITY_DISABLED,
			AVAILABILITY_DELETED,
			AVAILABILITY_UNAVAILABLE,
		)
		private val RETENTION_LOSS_AVAILABILITIES = setOf(
			AVAILABILITY_RETAINED,
			AVAILABILITY_NO_RETAINED_OBSERVATION,
		)
	}
}

/** One exact portable Pressure window; it is never rebound to a live WAL, provider, or service run. */
@Entity(
	tableName = "imported_pressure_window",
	primaryKeys = ["entry_identity", "entry_import_revision", "run_identity", "identity"],
	foreignKeys = [ForeignKey(
		entity = ImportedPressureRunEntity::class,
		parentColumns = ["entry_identity", "entry_import_revision", "identity"],
		childColumns = ["entry_identity", "entry_import_revision", "run_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["entry_identity", "entry_import_revision", "run_identity"],
			name = "idx_imported_pressure_window_run",
		),
		Index(value = ["identity"], name = "idx_imported_pressure_window_identity"),
		Index(
			value = ["interval_start_time_ms", "identity"],
			name = "idx_imported_pressure_window_time",
		),
	],
)
@Suppress("LongParameterList")
data class ImportedPressureWindowEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "identity") val identity: String,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "interval_start_time_ms") val intervalStartTimeMs: Long,
	@ColumnInfo(name = "interval_end_time_ms") val intervalEndTimeMs: Long,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long,
	@ColumnInfo(name = "observed_duration_nanos") val observedDurationNanos: Long,
	@ColumnInfo(name = "sample_count") val sampleCount: Int,
	@ColumnInfo(name = "expected_sample_count") val expectedSampleCount: Int,
	@ColumnInfo(name = "mean_hectopascals") val meanHectopascals: Double,
	@ColumnInfo(name = "sum_squared_deviations") val sumSquaredDeviations: Double,
	@ColumnInfo(name = "minimum_hectopascals") val minimumHectopascals: Float,
	@ColumnInfo(name = "maximum_hectopascals") val maximumHectopascals: Float,
	@ColumnInfo(name = "first_hectopascals") val firstHectopascals: Float,
	@ColumnInfo(name = "latest_hectopascals") val latestHectopascals: Float,
	@ColumnInfo(name = "slope_hectopascals_per_second") val slopeHectopascalsPerSecond: Double?,
	@ColumnInfo(name = "r_squared") val rSquared: Double?,
	@ColumnInfo(name = "sensor_accuracy") val sensorAccuracy: String,
	@ColumnInfo(name = "effective_sample_period_micros") val effectiveSamplePeriodMicros: Int,
	@ColumnInfo(name = "effective_maximum_report_latency_micros")
	val effectiveMaximumReportLatencyMicros: Int,
	@ColumnInfo(name = "target_window_duration_nanos") val targetWindowDurationNanos: Long,
	@ColumnInfo(name = "maximum_inter_sample_gap_nanos") val maximumInterSampleGapNanos: Long,
	@ColumnInfo(name = "closure_kind") val closureKind: String,
	@ColumnInfo(name = "qualification") val qualification: String,
	@ColumnInfo(name = "source_quality_flags") val sourceQualityFlags: Long,
	@ColumnInfo(name = "source_quality_confidence") val sourceQualityConfidence: Float?,
	@ColumnInfo(name = "stored_zone_id") val storedZoneId: String,
) {
	init {
		require(ImportedPressureIdentity.isOpaque(entryIdentity))
		require(ImportedPressureIdentity.isOpaque(runIdentity))
		require(ImportedPressureIdentity.isOpaque(identity))
		require(ImportedPressureIdentity.isOpaque(contentChecksum))
		require(entryImportRevision > 0L)
		require(intervalStartTimeMs >= 0L && intervalEndTimeMs >= intervalStartTimeMs)
		require(wallTimeUncertaintyMs >= 0L && observedDurationNanos >= 0L)
		require(intervalEndTimeMs - intervalStartTimeMs == observedDurationNanos / NANOS_PER_MILLISECOND)
		require(sampleCount > 0 && expectedSampleCount > 0)
		require(meanHectopascals.isFinite() && meanHectopascals > 0.0)
		require(sumSquaredDeviations.isFinite() && sumSquaredDeviations >= 0.0)
		require(minimumHectopascals.isFinite() && minimumHectopascals > 0f)
		require(maximumHectopascals.isFinite() && maximumHectopascals >= minimumHectopascals)
		require(meanHectopascals in minimumHectopascals.toDouble()..maximumHectopascals.toDouble())
		require(firstHectopascals in minimumHectopascals..maximumHectopascals)
		require(latestHectopascals in minimumHectopascals..maximumHectopascals)
		require(slopeHectopascalsPerSecond == null || slopeHectopascalsPerSecond.isFinite())
		require(rSquared == null || rSquared.isFinite() && rSquared in 0.0..1.0)
		require(sensorAccuracy in SENSOR_ACCURACIES)
		require(effectiveSamplePeriodMicros > 0 && effectiveMaximumReportLatencyMicros >= 0)
		require(targetWindowDurationNanos > 0L && maximumInterSampleGapNanos >= 0L)
		require(closureKind in CLOSURES && qualification in QUALIFICATIONS)
		require(sourceQualityFlags >= 0L)
		require(sourceQualityConfidence == null || sourceQualityConfidence in 0f..1f)
		require(storedZoneId.isNotBlank() && storedZoneId.length <= MAX_ZONE_ID_LENGTH)
		ZoneId.of(storedZoneId)
		val samplePeriodNanos = effectiveSamplePeriodMicros.toLong() * NANOS_PER_MICROSECOND
		val expectedFromPlan = targetWindowDurationNanos / samplePeriodNanos +
			if (targetWindowDurationNanos % samplePeriodNanos == 0L) 0L else 1L
		require(expectedFromPlan.coerceAtLeast(1L) == expectedSampleCount.toLong())
		if (sampleCount == 1) {
			require(firstHectopascals == latestHectopascals)
			require(firstHectopascals == minimumHectopascals)
			require(latestHectopascals == maximumHectopascals)
			require(meanHectopascals == firstHectopascals.toDouble())
			require(sumSquaredDeviations == 0.0)
			require(observedDurationNanos == 0L && maximumInterSampleGapNanos == 0L)
			require(slopeHectopascalsPerSecond == null && rSquared == null)
		} else {
			require(observedDurationNanos > 0L)
			require(maximumInterSampleGapNanos in 1L..observedDurationNanos)
			requireNotNull(slopeHectopascalsPerSecond)
			if (sumSquaredDeviations > 0.0) requireNotNull(rSquared) else require(rSquared == null)
		}
		val requiredObservedDuration = saturatedMultiply(
			expectedSampleCount.toLong() - 1L,
			samplePeriodNanos,
		)
		val noMissingCadence = samplePeriodNanos > Long.MAX_VALUE / 2L ||
			maximumInterSampleGapNanos < samplePeriodNanos * 2L
		val complete = closureKind == "TARGET_ELAPSED" && sampleCount >= expectedSampleCount &&
			observedDurationNanos >= requiredObservedDuration && noMissingCadence
		require((qualification == "COMPLETE") == complete)
	}

	companion object {
		const val MAX_ZONE_ID_LENGTH = 128
		private const val NANOS_PER_MICROSECOND = 1_000L
		private const val NANOS_PER_MILLISECOND = 1_000_000L
		private val SENSOR_ACCURACIES = setOf("UNKNOWN", "UNRELIABLE", "LOW", "MEDIUM", "HIGH")
		private val CLOSURES = setOf("TARGET_ELAPSED", "SOURCE_BOUNDARY")
		private val QUALIFICATIONS = setOf("COMPLETE", "PARTIAL")

		private fun saturatedMultiply(first: Long, second: Long): Long =
			if (first == 0L || second <= Long.MAX_VALUE / first) first * second else Long.MAX_VALUE
	}
}

/**
 * Source-local privacy authority retained after a selected imported run is removed.
 *
 * Portable v1 has no successor generation token, so any retained generation makes old v1 replay
 * unverifiable and the later writer must reject it. Full collected-data clear removes this row.
 */
@Entity(tableName = "imported_pressure_deletion_generation", primaryKeys = ["run_identity"])
data class ImportedPressureDeletionGenerationEntity(
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "generation") val generation: Long,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(ImportedPressureIdentity.isOpaque(runIdentity))
		require(collectedDataEpoch >= 0L && generation > 0L && deletedAtMs >= 0L)
		require(effectChecksum == checksum(runIdentity, collectedDataEpoch, generation, deletedAtMs))
	}

	companion object {
		fun create(
			runIdentity: String,
			collectedDataEpoch: Long,
			generation: Long,
			deletedAtMs: Long,
		): ImportedPressureDeletionGenerationEntity = ImportedPressureDeletionGenerationEntity(
			runIdentity,
			collectedDataEpoch,
			generation,
			deletedAtMs,
			checksum(runIdentity, collectedDataEpoch, generation, deletedAtMs),
		)

		private fun checksum(
			runIdentity: String,
			collectedDataEpoch: Long,
			generation: Long,
			deletedAtMs: Long,
		): String {
			val values = listOf(
				"tracker-imported-pressure-deletion-generation-v1",
				runIdentity,
				collectedDataEpoch.toString(),
				generation.toString(),
				deletedAtMs.toString(),
			)
			val canonical = values.joinToString(separator = "") { "${it.length}:$it" }
			return "sha256:" + MessageDigest.getInstance("SHA-256")
				.digest(canonical.toByteArray(Charsets.UTF_8))
				.joinToString(separator = "") { byte -> "%02x".format(byte) }
		}
	}
}

internal object ImportedPressureIdentity {
	private val opaque = Regex("sha256:[0-9a-f]{64}")
	fun isOpaque(value: String?): Boolean = value != null && opaque.matches(value)

	fun requireProvenance(jobId: String, entryKey: String, sourceName: String) {
		require(jobId.isNotBlank() && jobId.length <= MAX_PROVENANCE_LENGTH)
		require(entryKey.isNotBlank() && entryKey.length <= MAX_PROVENANCE_LENGTH)
		require(sourceName.isNotBlank() && sourceName.length <= MAX_PROVENANCE_LENGTH)
	}

	private const val MAX_PROVENANCE_LENGTH = 4_096
}
