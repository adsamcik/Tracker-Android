package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.security.MessageDigest
import java.time.ZoneId

/** Immutable destination-local revision of one captured Wi-Fi portable entry. */
@Entity(
	tableName = "imported_wifi_entry_revision",
	primaryKeys = ["identity", "import_revision"],
	indices = [Index(
		value = ["import_job_id", "import_entry_key"],
		unique = true,
		name = "idx_imported_wifi_entry_receipt",
	)],
)
@Suppress("LongParameterList")
data class ImportedWifiEntryRevisionEntity(
	@ColumnInfo(name = "identity") val identity: String,
	@ColumnInfo(name = "import_revision") val importRevision: Long,
	@ColumnInfo(name = "supersedes_import_revision") val supersedesImportRevision: Long?,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "source_format") val sourceFormat: String,
	@ColumnInfo(name = "source_schema_version") val sourceSchemaVersion: Int,
	@ColumnInfo(name = "session_mode") val sessionMode: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "import_job_id") val importJobId: String,
	@ColumnInfo(name = "import_entry_key") val importEntryKey: String,
	@ColumnInfo(name = "import_source_name") val importSourceName: String,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
) {
	init {
		requireWifiOpaque(identity)
		requireWifiOpaque(contentChecksum)
		require(importRevision > 0L)
		require(supersedesImportRevision == importRevision.takeIf { it > 1L }?.minus(1L))
		require(sourceFormat == SOURCE_FORMAT && sourceSchemaVersion == SOURCE_SCHEMA_VERSION)
		require(sessionMode in SESSION_MODES)
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(collectedDataEpoch >= 0L && receivedAtMs >= 0L)
		requireWifiImportProvenance(importJobId, importEntryKey, importSourceName)
	}

	companion object {
		const val SOURCE_FORMAT = "tracker-portable-captured-wifi"
		const val SOURCE_SCHEMA_VERSION = 1
		private val SESSION_MODES = setOf("MANUAL", "AUTOMATIC")
	}
}

/** Immutable source-specific receipt authority for an imported Wi-Fi revision. */
@Entity(
	tableName = "imported_wifi_receipt",
	primaryKeys = ["import_job_id", "import_entry_key"],
	foreignKeys = [ForeignKey(
		entity = ImportedWifiEntryRevisionEntity::class,
		parentColumns = ["identity", "import_revision"],
		childColumns = ["entry_identity", "entry_import_revision"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(
		value = ["entry_identity", "entry_import_revision"],
		name = "idx_imported_wifi_receipt_entry",
	)],
)
data class ImportedWifiReceiptEntity(
	@ColumnInfo(name = "import_job_id") val importJobId: String,
	@ColumnInfo(name = "import_entry_key") val importEntryKey: String,
	@ColumnInfo(name = "import_source_name") val importSourceName: String,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "entry_content_checksum") val entryContentChecksum: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
) {
	init {
		requireWifiImportProvenance(importJobId, importEntryKey, importSourceName)
		require(receivedAtMs >= 0L)
		requireWifiOpaque(entryIdentity)
		require(entryImportRevision > 0L)
		requireWifiOpaque(entryContentChecksum)
		require(collectedDataEpoch >= 0L)
	}
}

/** Exact foreign physical membership; it grants no local service-run or provider authority. */
@Entity(
	tableName = "imported_wifi_run",
	primaryKeys = ["entry_identity", "entry_import_revision", "identity"],
	foreignKeys = [ForeignKey(
		entity = ImportedWifiEntryRevisionEntity::class,
		parentColumns = ["identity", "import_revision"],
		childColumns = ["entry_identity", "entry_import_revision"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["entry_identity", "entry_import_revision"],
			name = "idx_imported_wifi_run_entry",
		),
		Index(value = ["identity"], name = "idx_imported_wifi_run_identity"),
		Index(value = ["deletion_scope_digest"], name = "idx_imported_wifi_run_scope"),
	],
)
@Suppress("LongParameterList")
data class ImportedWifiRunEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "identity") val identity: String,
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "capture_coverage") val captureCoverage: String,
	@ColumnInfo(name = "availability") val availability: String,
	@ColumnInfo(name = "acquisition_completeness") val acquisitionCompleteness: String,
	@ColumnInfo(name = "has_unresolved_provider_range") val hasUnresolvedProviderRange: Boolean,
	@ColumnInfo(name = "retention_loss") val retentionLoss: Boolean,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
) {
	init {
		requireWifiOpaque(entryIdentity)
		requireWifiOpaque(identity)
		requireWifiOpaque(deletionScopeDigest)
		requireWifiOpaque(contentChecksum)
		require(entryImportRevision > 0L)
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(captureCoverage in CAPTURE_COVERAGES)
		require(availability in AVAILABILITIES)
		require(acquisitionCompleteness in ACQUISITION_COMPLETENESS)
		require(collectedDataEpoch >= 0L && scopeDeletionGeneration >= 0L)
		if (availability == "NOT_CAPTURED") {
			require(captureCoverage == "NOT_CAPTURED")
			require(acquisitionCompleteness == "UNKNOWN")
			require(!hasUnresolvedProviderRange && !retentionLoss)
		} else {
			require(captureCoverage != "NOT_CAPTURED")
		}
		require(!hasUnresolvedProviderRange || acquisitionCompleteness != "COMPLETE")
	}

	private companion object {
		val CAPTURE_COVERAGES = setOf("WHOLE_RUN", "PARTIAL_RUN", "NOT_CAPTURED")
		val AVAILABILITIES = setOf("RETAINED", "NO_RETAINED_OBSERVATION", "NOT_CAPTURED")
		val ACQUISITION_COMPLETENESS = setOf("COMPLETE", "PARTIAL", "UNKNOWN")
	}
}

/** Ordered civil-time evidence attached to one exact imported run. */
@Entity(
	tableName = "imported_wifi_run_zone",
	primaryKeys = ["entry_identity", "entry_import_revision", "run_identity", "ordinal"],
	foreignKeys = [ForeignKey(
		entity = ImportedWifiRunEntity::class,
		parentColumns = ["entry_identity", "entry_import_revision", "identity"],
		childColumns = ["entry_identity", "entry_import_revision", "run_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(
		value = ["entry_identity", "entry_import_revision", "run_identity"],
		name = "idx_imported_wifi_run_zone_run",
	)],
)
data class ImportedWifiRunZoneEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "ordinal") val ordinal: Int,
	@ColumnInfo(name = "zone_id") val zoneId: String,
) {
	init {
		requireWifiOpaque(entryIdentity)
		requireWifiOpaque(runIdentity)
		require(entryImportRevision > 0L && ordinal >= 0)
		require(zoneId.isNotBlank() && zoneId.length <= MAX_ZONE_LENGTH)
		ZoneId.of(zoneId)
	}

	private companion object { const val MAX_ZONE_LENGTH = 128 }
}

/** One identity-free Wi-Fi aggregate/coverage observation from the portable hierarchy. */
@Entity(
	tableName = "imported_wifi_observation",
	primaryKeys = ["entry_identity", "entry_import_revision", "run_identity", "identity"],
	foreignKeys = [
		ForeignKey(
			entity = ImportedWifiRunEntity::class,
			parentColumns = ["entry_identity", "entry_import_revision", "identity"],
			childColumns = ["entry_identity", "entry_import_revision", "run_identity"],
			onDelete = ForeignKey.CASCADE,
		),
		ForeignKey(
			entity = ImportedWifiObservationEntity::class,
			parentColumns = [
				"entry_identity", "entry_import_revision", "run_identity", "identity", "semantic_revision",
			],
			childColumns = [
				"entry_identity", "entry_import_revision", "run_identity",
				"aggregate_owner_identity", "aggregate_owner_semantic_revision",
			],
			onDelete = ForeignKey.NO_ACTION,
			deferred = true,
		),
	],
	indices = [
		Index(
			value = ["entry_identity", "entry_import_revision", "run_identity"],
			name = "idx_imported_wifi_observation_run",
		),
		Index(value = ["identity"], name = "idx_imported_wifi_observation_identity"),
		Index(
			value = [
				"entry_identity", "entry_import_revision", "run_identity", "identity", "semantic_revision",
			],
			unique = true,
			name = "idx_imported_wifi_observation_semantic_owner",
		),
		Index(
			value = [
				"entry_identity", "entry_import_revision", "run_identity",
				"aggregate_owner_identity", "aggregate_owner_semantic_revision",
			],
			name = "idx_imported_wifi_observation_aggregate_owner",
		),
	],
)
@Suppress("LongParameterList")
data class ImportedWifiObservationEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "identity") val identity: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "supersedes_semantic_revision") val supersedesSemanticRevision: Long?,
	@ColumnInfo(name = "aggregate_owner_identity") val aggregateOwnerIdentity: String?,
	@ColumnInfo(name = "aggregate_owner_semantic_revision") val aggregateOwnerSemanticRevision: Long?,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "coverage_start_time_ms") val coverageStartTimeMs: Long,
	@ColumnInfo(name = "observed_time_ms") val observedTimeMs: Long,
	@ColumnInfo(name = "latest_possible_time_ms") val latestPossibleTimeMs: Long,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long,
	@ColumnInfo(name = "stored_zone_id") val storedZoneId: String,
	@ColumnInfo(name = "availability") val availability: String,
	@ColumnInfo(name = "result_completeness") val resultCompleteness: String,
	@ColumnInfo(name = "submitted_result_count") val submittedResultCount: Int,
	@ColumnInfo(name = "accepted_result_count") val acceptedResultCount: Int,
	@ColumnInfo(name = "stale_result_count") val staleResultCount: Int,
	@ColumnInfo(name = "clock_unverifiable_result_count") val clockUnverifiableResultCount: Int,
	@ColumnInfo(name = "malformed_result_count") val malformedResultCount: Int,
	@ColumnInfo(name = "observation_count") val observationCount: Int,
	@ColumnInfo(name = "two_point_four_ghz_count") val twoPointFourGhzCount: Int,
	@ColumnInfo(name = "five_ghz_count") val fiveGhzCount: Int,
	@ColumnInfo(name = "six_ghz_count") val sixGhzCount: Int,
	@ColumnInfo(name = "other_band_count") val otherBandCount: Int,
	@ColumnInfo(name = "strongest_signal_dbm") val strongestSignalDbm: Int,
	@ColumnInfo(name = "weakest_signal_dbm") val weakestSignalDbm: Int,
	@ColumnInfo(name = "mean_signal_dbm") val meanSignalDbm: Double,
	@ColumnInfo(name = "source_quality_flags") val sourceQualityFlags: Long,
	@ColumnInfo(name = "source_quality_confidence") val sourceQualityConfidence: Float?,
) {
	init {
		requireWifiOpaque(entryIdentity)
		requireWifiOpaque(runIdentity)
		requireWifiOpaque(identity)
		requireWifiOpaque(contentChecksum)
		require(entryImportRevision > 0L && semanticRevision > 0L)
		require(supersedesSemanticRevision == semanticRevision.takeIf { it > 1L }?.minus(1L))
		require((aggregateOwnerIdentity == null) == (aggregateOwnerSemanticRevision == null))
		aggregateOwnerIdentity?.let {
			requireWifiOpaque(it)
			require(it != identity && requireNotNull(aggregateOwnerSemanticRevision) > 0L)
		}
		require(wallTimeUncertaintyMs in 0L..observedTimeMs)
		require(coverageStartTimeMs in 0L..(observedTimeMs - wallTimeUncertaintyMs))
		require(latestPossibleTimeMs == Math.addExact(observedTimeMs, wallTimeUncertaintyMs))
		require(storedZoneId.isNotBlank() && storedZoneId.length <= MAX_ZONE_LENGTH)
		ZoneId.of(storedZoneId)
		require(availability == "AVAILABLE")
		require(resultCompleteness in RESULT_COMPLETENESS)
		require(listOf(staleResultCount, clockUnverifiableResultCount, malformedResultCount).all { it >= 0 })
		val rejected = Math.addExact(
			Math.addExact(staleResultCount, clockUnverifiableResultCount),
			malformedResultCount,
		)
		require(submittedResultCount > 0 && acceptedResultCount > 0)
		require(Math.addExact(acceptedResultCount, rejected) == submittedResultCount)
		require((resultCompleteness == "COMPLETE") == (rejected == 0))
		require(observationCount == acceptedResultCount)
		val bandTotal = Math.addExact(
			Math.addExact(twoPointFourGhzCount, fiveGhzCount),
			Math.addExact(sixGhzCount, otherBandCount),
		)
		require(listOf(twoPointFourGhzCount, fiveGhzCount, sixGhzCount, otherBandCount).all { it >= 0 })
		require(bandTotal == observationCount)
		require(strongestSignalDbm >= weakestSignalDbm)
		require(meanSignalDbm.isFinite() &&
			meanSignalDbm in weakestSignalDbm.toDouble()..strongestSignalDbm.toDouble())
		require(sourceQualityFlags >= 0L)
		require(sourceQualityConfidence?.let { it in 0f..1f } != false)
	}

	private companion object {
		const val MAX_ZONE_LENGTH = 128
		val RESULT_COMPLETENESS = setOf("COMPLETE", "PARTIAL")
	}
}

/** Logical-entry privacy authority retained after later selected imported deletion. */
@Entity(tableName = "imported_wifi_entry_deletion", primaryKeys = ["entry_identity"])
data class ImportedWifiEntryDeletionEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "deleted_import_revision") val deletedImportRevision: Long,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		requireWifiOpaque(entryIdentity)
		require(collectedDataEpoch >= 0L && deletedImportRevision > 0L && deletedAtMs >= 0L)
		require(effectChecksum == checksum(
			entryIdentity, collectedDataEpoch, deletedImportRevision, deletedAtMs,
		))
	}

	companion object {
		fun create(
			entryIdentity: String,
			collectedDataEpoch: Long,
			deletedImportRevision: Long,
			deletedAtMs: Long,
		): ImportedWifiEntryDeletionEntity = ImportedWifiEntryDeletionEntity(
			entryIdentity,
			collectedDataEpoch,
			deletedImportRevision,
			deletedAtMs,
			checksum(entryIdentity, collectedDataEpoch, deletedImportRevision, deletedAtMs),
		)

		private fun checksum(
			entryIdentity: String,
			collectedDataEpoch: Long,
			deletedImportRevision: Long,
			deletedAtMs: Long,
		): String = wifiAuthorityDigest(
			"tracker-imported-wifi-entry-deletion-v1",
			entryIdentity,
			collectedDataEpoch,
			deletedImportRevision,
			deletedAtMs,
		)
	}
}

/** Payload-free run/scope generation; full collected-data clear is its only erase path. */
@Entity(
	tableName = "imported_wifi_deletion_generation",
	primaryKeys = ["run_identity"],
	indices = [
		Index(value = ["entry_identity"], name = "idx_imported_wifi_deletion_entry"),
		Index(value = ["deletion_scope_digest"], unique = true, name = "idx_imported_wifi_deletion_scope"),
	],
)
data class ImportedWifiDeletionGenerationEntity(
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "generation") val generation: Long,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		requireWifiOpaque(runIdentity)
		requireWifiOpaque(entryIdentity)
		requireWifiOpaque(deletionScopeDigest)
		require(collectedDataEpoch >= 0L && generation > 0L && deletedAtMs >= 0L)
		require(effectChecksum == checksum(
			runIdentity, entryIdentity, deletionScopeDigest, collectedDataEpoch, generation, deletedAtMs,
		))
	}

	companion object {
		fun create(
			runIdentity: String,
			entryIdentity: String,
			deletionScopeDigest: String,
			collectedDataEpoch: Long,
			generation: Long,
			deletedAtMs: Long,
		): ImportedWifiDeletionGenerationEntity = ImportedWifiDeletionGenerationEntity(
			runIdentity,
			entryIdentity,
			deletionScopeDigest,
			collectedDataEpoch,
			generation,
			deletedAtMs,
			checksum(
				runIdentity,
				entryIdentity,
				deletionScopeDigest,
				collectedDataEpoch,
				generation,
				deletedAtMs,
			),
		)

		private fun checksum(
			runIdentity: String,
			entryIdentity: String,
			deletionScopeDigest: String,
			collectedDataEpoch: Long,
			generation: Long,
			deletedAtMs: Long,
		): String = wifiAuthorityDigest(
			"tracker-imported-wifi-deletion-generation-v1",
			runIdentity,
			entryIdentity,
			deletionScopeDigest,
			collectedDataEpoch,
			generation,
			deletedAtMs,
		)
	}
}

private val WIFI_OPAQUE = Regex("[0-9a-f]{64}")
private const val MAX_IMPORT_PROVENANCE_LENGTH = 4_096

private fun requireWifiOpaque(value: String) = require(WIFI_OPAQUE.matches(value))

private fun requireWifiImportProvenance(jobId: String, entryKey: String, sourceName: String) {
	listOf(jobId, entryKey, sourceName).forEach { value ->
		require(value.isNotBlank() && value.length <= MAX_IMPORT_PROVENANCE_LENGTH)
	}
}

private fun wifiAuthorityDigest(namespace: String, vararg values: Any): String {
	val parts = listOf(namespace) + values.map(Any::toString)
	val canonical = parts.joinToString(separator = "") { value -> "${value.length}:$value" }
	return MessageDigest.getInstance("SHA-256")
		.digest(canonical.toByteArray(Charsets.UTF_8))
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
