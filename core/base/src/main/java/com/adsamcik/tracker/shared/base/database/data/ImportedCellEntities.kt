package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.security.MessageDigest
import java.time.ZoneId

/** One immutable destination-local revision of an identity-free captured Cell entry. */
@Entity(
	tableName = "imported_cell_entry_revision",
	primaryKeys = ["identity", "import_revision"],
	indices = [Index(
		value = ["import_job_id", "import_entry_key"],
		unique = true,
		name = "idx_imported_cell_entry_original_receipt",
	)],
)
data class ImportedCellEntryRevisionEntity(
	@ColumnInfo(name = "identity") val identity: String,
	@ColumnInfo(name = "import_revision") val importRevision: Long,
	@ColumnInfo(name = "supersedes_import_revision") val supersedesImportRevision: Long?,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "source_format") val sourceFormat: String,
	@ColumnInfo(name = "source_schema_version") val sourceSchemaVersion: Int,
	@ColumnInfo(name = "session_mode") val sessionMode: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "subscription_grouping") val subscriptionGrouping: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "import_job_id") val importJobId: String,
	@ColumnInfo(name = "import_entry_key") val importEntryKey: String,
	@ColumnInfo(name = "import_source_name") val importSourceName: String,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
) {
	init {
		listOf(identity, contentChecksum).forEach { require(ImportedCellIdentity.isDigest(it)) }
		require(importRevision > 0L)
		require(
			(importRevision == 1L && supersedesImportRevision == null) ||
				(importRevision > 1L && supersedesImportRevision == importRevision - 1L),
		)
		require(sourceFormat == SOURCE_FORMAT && sourceSchemaVersion == SOURCE_SCHEMA_VERSION)
		require(sessionMode in SESSION_MODES)
		require(subscriptionGrouping == SUBSCRIPTION_GROUPING_UNKNOWN)
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(collectedDataEpoch >= 0L && receivedAtMs >= 0L)
		ImportedCellIdentity.requireProvenance(importJobId, importEntryKey, importSourceName)
	}

	companion object {
		const val SOURCE_FORMAT = "tracker-portable-captured-cell"
		const val SOURCE_SCHEMA_VERSION = 1
		const val SUBSCRIPTION_GROUPING_UNKNOWN = "UNKNOWN"
		private val SESSION_MODES = setOf("MANUAL", "AUTOMATIC")
	}
}

/** Immutable Cell-local receipt; alternate receipts may bind one exact retained revision. */
@Entity(
	tableName = "imported_cell_receipt",
	primaryKeys = ["import_job_id", "import_entry_key"],
	foreignKeys = [ForeignKey(
		entity = ImportedCellEntryRevisionEntity::class,
		parentColumns = ["identity", "import_revision"],
		childColumns = ["entry_identity", "entry_import_revision"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(
		value = ["entry_identity", "entry_import_revision"],
		name = "idx_imported_cell_receipt_entry",
	)],
)
data class ImportedCellReceiptEntity(
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
		ImportedCellIdentity.requireProvenance(importJobId, importEntryKey, importSourceName)
		require(receivedAtMs >= 0L)
		require(ImportedCellIdentity.isDigest(entryIdentity))
		require(entryImportRevision > 0L)
		require(ImportedCellIdentity.isDigest(entryContentChecksum))
		require(collectedDataEpoch >= 0L)
	}
}

/** Exact foreign replacement-run membership; this row is never a local service run. */
@Entity(
	tableName = "imported_cell_run",
	primaryKeys = ["entry_identity", "entry_import_revision", "identity"],
	foreignKeys = [ForeignKey(
		entity = ImportedCellEntryRevisionEntity::class,
		parentColumns = ["identity", "import_revision"],
		childColumns = ["entry_identity", "entry_import_revision"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["entry_identity", "entry_import_revision"],
			name = "idx_imported_cell_run_entry",
		),
		Index(value = ["identity"], name = "idx_imported_cell_run_identity"),
		Index(value = ["deletion_scope_digest"], name = "idx_imported_cell_run_scope"),
		Index(
			value = ["entry_identity", "entry_import_revision", "deletion_scope_digest"],
			unique = true,
			name = "idx_imported_cell_run_revision_scope",
		),
	],
)
data class ImportedCellRunEntity(
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
	@ColumnInfo(name = "retention_loss") val retentionLoss: Boolean,
	@ColumnInfo(name = "subscription_grouping") val subscriptionGrouping: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
) {
	init {
		listOf(entryIdentity, identity, deletionScopeDigest, contentChecksum).forEach {
			require(ImportedCellIdentity.isDigest(it))
		}
		require(entryImportRevision > 0L)
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(captureCoverage in CAPTURE_COVERAGES)
		require(availability in AVAILABILITIES)
		require(acquisitionCompleteness in ACQUISITION_COMPLETENESS)
		require(subscriptionGrouping == ImportedCellEntryRevisionEntity.SUBSCRIPTION_GROUPING_UNKNOWN)
		require(collectedDataEpoch >= 0L && scopeDeletionGeneration >= 0L)
		when (availability) {
			"RETAINED" -> require(captureCoverage != "NOT_CAPTURED")
			"NO_RETAINED_OBSERVATION" -> require(captureCoverage != "NOT_CAPTURED")
			"NOT_CAPTURED" -> require(captureCoverage == "NOT_CAPTURED" && !retentionLoss)
		}
	}

	private companion object {
		val CAPTURE_COVERAGES = setOf("WHOLE_RUN", "PARTIAL_RUN", "NOT_CAPTURED")
		val AVAILABILITIES = setOf("RETAINED", "NO_RETAINED_OBSERVATION", "NOT_CAPTURED")
		val ACQUISITION_COMPLETENESS = setOf("COMPLETE", "PARTIAL", "UNKNOWN")
	}
}

/** One exact identity-free Cell observation; no tower, SIM, subscription, or provider id exists. */
@Entity(
	tableName = "imported_cell_observation",
	primaryKeys = ["entry_identity", "entry_import_revision", "run_identity", "identity"],
	foreignKeys = [ForeignKey(
		entity = ImportedCellRunEntity::class,
		parentColumns = ["entry_identity", "entry_import_revision", "identity"],
		childColumns = ["entry_identity", "entry_import_revision", "run_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["entry_identity", "entry_import_revision", "run_identity"],
			name = "idx_imported_cell_observation_run",
		),
		Index(value = ["identity"], name = "idx_imported_cell_observation_identity"),
		Index(value = ["aggregate_owner_identity"], name = "idx_imported_cell_observation_owner"),
		Index(
			value = ["entry_identity", "entry_import_revision", "identity"],
			unique = true,
			name = "idx_imported_cell_observation_revision_identity",
		),
	],
)
@Suppress("LongParameterList")
data class ImportedCellObservationEntity(
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
	@ColumnInfo(name = "child_completeness") val childCompleteness: String,
	@ColumnInfo(name = "subscription_grouping") val subscriptionGrouping: String,
	@ColumnInfo(name = "submitted_child_count") val submittedChildCount: Int,
	@ColumnInfo(name = "accepted_child_count") val acceptedChildCount: Int,
	@ColumnInfo(name = "stale_child_count") val staleChildCount: Int,
	@ColumnInfo(name = "future_time_child_count") val futureTimeChildCount: Int,
	@ColumnInfo(name = "missing_time_child_count") val missingTimeChildCount: Int,
	@ColumnInfo(name = "clock_unverifiable_child_count") val clockUnverifiableChildCount: Int,
	@ColumnInfo(name = "authority_mismatch_child_count") val authorityMismatchChildCount: Int,
	@ColumnInfo(name = "unsupported_technology_child_count") val unsupportedTechnologyChildCount: Int,
	@ColumnInfo(name = "observation_count") val observationCount: Int,
	@ColumnInfo(name = "registered_observation_count") val registeredObservationCount: Int,
	@ColumnInfo(name = "gsm_count") val gsmCount: Int,
	@ColumnInfo(name = "cdma_count") val cdmaCount: Int,
	@ColumnInfo(name = "wcdma_count") val wcdmaCount: Int,
	@ColumnInfo(name = "tdscdma_count") val tdscdmaCount: Int,
	@ColumnInfo(name = "lte_count") val lteCount: Int,
	@ColumnInfo(name = "nr_count") val nrCount: Int,
	@ColumnInfo(name = "quality_unknown_count") val qualityUnknownCount: Int,
	@ColumnInfo(name = "quality_none_or_unknown_count") val qualityNoneOrUnknownCount: Int,
	@ColumnInfo(name = "quality_poor_count") val qualityPoorCount: Int,
	@ColumnInfo(name = "quality_moderate_count") val qualityModerateCount: Int,
	@ColumnInfo(name = "quality_good_count") val qualityGoodCount: Int,
	@ColumnInfo(name = "quality_great_count") val qualityGreatCount: Int,
	@ColumnInfo(name = "weak_observation_count") val weakObservationCount: Int,
	@ColumnInfo(name = "known_quality_observation_count") val knownQualityObservationCount: Int,
	@ColumnInfo(name = "all_known_quality_is_weak") val allKnownQualityIsWeak: Boolean,
	@ColumnInfo(name = "quality_flags") val qualityFlags: Long,
	@ColumnInfo(name = "quality_confidence") val qualityConfidence: Double?,
) {
	init {
		listOf(entryIdentity, runIdentity, identity, contentChecksum).forEach {
			require(ImportedCellIdentity.isDigest(it))
		}
		require(entryImportRevision > 0L && semanticRevision > 0L)
		require(supersedesSemanticRevision == semanticRevision.takeIf { it > 1L }?.minus(1L))
		require((aggregateOwnerIdentity == null) == (aggregateOwnerSemanticRevision == null))
		aggregateOwnerIdentity?.let { require(ImportedCellIdentity.isDigest(it)) }
		require(aggregateOwnerSemanticRevision?.let { it > 0L } != false)
		require(coverageStartTimeMs >= 0L && observedTimeMs >= coverageStartTimeMs)
		require(wallTimeUncertaintyMs >= 0L)
		require(latestPossibleTimeMs == Math.addExact(observedTimeMs, wallTimeUncertaintyMs))
		require(storedZoneId.isNotBlank() && storedZoneId.length <= MAX_ZONE_LENGTH)
		ZoneId.of(storedZoneId)
		require(childCompleteness == "COMPLETE" || childCompleteness == "PARTIAL")
		require(subscriptionGrouping == ImportedCellEntryRevisionEntity.SUBSCRIPTION_GROUPING_UNKNOWN)
		val counts = listOf(
			submittedChildCount, acceptedChildCount, staleChildCount, futureTimeChildCount,
			missingTimeChildCount, clockUnverifiableChildCount, authorityMismatchChildCount,
			unsupportedTechnologyChildCount, observationCount, registeredObservationCount,
			gsmCount, cdmaCount, wcdmaCount, tdscdmaCount, lteCount, nrCount,
			qualityUnknownCount, qualityNoneOrUnknownCount, qualityPoorCount,
			qualityModerateCount, qualityGoodCount, qualityGreatCount, weakObservationCount,
			knownQualityObservationCount,
		)
		require(counts.all { it >= 0 })
		require(qualityFlags >= 0L)
		require(qualityConfidence?.let { it.isFinite() && it in 0.0..1.0 } != false)
	}

	private companion object {
		const val MAX_ZONE_LENGTH = 128
	}
}

/** Payload-free proof that an imported logical Cell entry was selected and deleted. */
@Entity(tableName = "imported_cell_entry_deletion", primaryKeys = ["entry_identity"])
data class ImportedCellEntryDeletionEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "deleted_import_revision") val deletedImportRevision: Long,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(ImportedCellIdentity.isDigest(entryIdentity))
		require(collectedDataEpoch >= 0L && deletedImportRevision > 0L && deletedAtMs >= 0L)
		require(effectChecksum == checksum(
			"tracker-imported-cell-entry-deletion-v1",
			entryIdentity,
			collectedDataEpoch,
			deletedImportRevision,
			deletedAtMs,
		))
	}

	companion object {
		fun create(
			entryIdentity: String,
			collectedDataEpoch: Long,
			deletedImportRevision: Long,
			deletedAtMs: Long,
		) = ImportedCellEntryDeletionEntity(
			entryIdentity,
			collectedDataEpoch,
			deletedImportRevision,
			deletedAtMs,
			checksum(
				"tracker-imported-cell-entry-deletion-v1",
				entryIdentity,
				collectedDataEpoch,
				deletedImportRevision,
				deletedAtMs,
			),
		)
	}
}

/** Monotonic imported-run privacy generation retained after its hierarchy is removed. */
@Entity(
	tableName = "imported_cell_deletion_generation",
	primaryKeys = ["run_identity"],
	indices = [Index(
		value = ["deletion_scope_digest"],
		unique = true,
		name = "idx_imported_cell_deletion_generation_scope",
	)],
)
data class ImportedCellDeletionGenerationEntity(
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "generation") val generation: Long,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		listOf(runIdentity, entryIdentity, deletionScopeDigest).forEach {
			require(ImportedCellIdentity.isDigest(it))
		}
		require(setOf(runIdentity, entryIdentity, deletionScopeDigest).size == 3)
		require(collectedDataEpoch >= 0L && generation > 0L && deletedAtMs >= 0L)
		require(effectChecksum == checksum(
			"tracker-imported-cell-deletion-generation-v1",
			runIdentity,
			entryIdentity,
			deletionScopeDigest,
			collectedDataEpoch,
			generation,
			deletedAtMs,
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
		) = ImportedCellDeletionGenerationEntity(
			runIdentity,
			entryIdentity,
			deletionScopeDigest,
			collectedDataEpoch,
			generation,
			deletedAtMs,
			checksum(
				"tracker-imported-cell-deletion-generation-v1",
				runIdentity,
				entryIdentity,
				deletionScopeDigest,
				collectedDataEpoch,
				generation,
				deletedAtMs,
			),
		)
	}
}

internal object ImportedCellIdentity {
	private val digest = Regex("[0-9a-f]{64}")

	fun isDigest(value: String?): Boolean = value != null && digest.matches(value)

	fun requireProvenance(jobId: String, entryKey: String, sourceName: String) {
		listOf(jobId, entryKey, sourceName).forEach { value ->
			require(value.isNotBlank() && value.length <= MAX_PROVENANCE_LENGTH)
		}
	}

	private const val MAX_PROVENANCE_LENGTH = 4_096
}

private fun checksum(namespace: String, vararg values: Any): String {
	val canonical = (listOf(namespace) + values.map(Any::toString)).joinToString(separator = "") {
		"${it.length}:$it"
	}
	return MessageDigest.getInstance("SHA-256")
		.digest(canonical.toByteArray(Charsets.UTF_8))
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
