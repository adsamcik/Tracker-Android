package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.security.MessageDigest
import java.time.ZoneId

/** One immutable destination-local revision of a captured Activity portable entry. */
@Entity(
	tableName = "imported_activity_entry_revision",
	primaryKeys = ["identity", "import_revision"],
	indices = [Index(
		value = ["import_job_id", "import_entry_key"],
		unique = true,
		name = "idx_imported_activity_entry_original_receipt",
	)],
)
data class ImportedActivityEntryRevisionEntity(
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
		require(ImportedActivityIdentity.isDigest(identity))
		require(ImportedActivityIdentity.isDigest(contentChecksum))
		require(importRevision > 0L)
		require(
			(importRevision == 1L && supersedesImportRevision == null) ||
				(importRevision > 1L && supersedesImportRevision == importRevision - 1L),
		)
		require(sourceFormat == SOURCE_FORMAT && sourceSchemaVersion == SOURCE_SCHEMA_VERSION)
		require(sessionMode == "MANUAL" || sessionMode == "AUTOMATIC")
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(collectedDataEpoch >= 0L && receivedAtMs >= 0L)
		ImportedActivityIdentity.requireProvenance(importJobId, importEntryKey, importSourceName)
	}

	companion object {
		const val SOURCE_FORMAT = "tracker-portable-captured-activity"
		const val SOURCE_SCHEMA_VERSION = 1
	}
}

/** Immutable Activity-local receipt; alternate receipts may bind one exact retained revision. */
@Entity(
	tableName = "imported_activity_receipt",
	primaryKeys = ["import_job_id", "import_entry_key"],
	foreignKeys = [ForeignKey(
		entity = ImportedActivityEntryRevisionEntity::class,
		parentColumns = ["identity", "import_revision"],
		childColumns = ["entry_identity", "entry_import_revision"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(
		value = ["entry_identity", "entry_import_revision"],
		name = "idx_imported_activity_receipt_entry",
	)],
)
data class ImportedActivityReceiptEntity(
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
		ImportedActivityIdentity.requireProvenance(importJobId, importEntryKey, importSourceName)
		require(receivedAtMs >= 0L)
		require(ImportedActivityIdentity.isDigest(entryIdentity))
		require(entryImportRevision > 0L)
		require(ImportedActivityIdentity.isDigest(entryContentChecksum))
		require(collectedDataEpoch >= 0L)
	}
}

/** Exact imported replacement-run membership, never a local SourceServiceRun or provider grant. */
@Entity(
	tableName = "imported_activity_run",
	primaryKeys = ["entry_identity", "entry_import_revision", "identity"],
	foreignKeys = [ForeignKey(
		entity = ImportedActivityEntryRevisionEntity::class,
		parentColumns = ["identity", "import_revision"],
		childColumns = ["entry_identity", "entry_import_revision"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["entry_identity", "entry_import_revision"],
			name = "idx_imported_activity_run_entry",
		),
		Index(value = ["identity"], name = "idx_imported_activity_run_identity"),
		Index(value = ["deletion_scope_digest"], name = "idx_imported_activity_run_scope"),
	],
)
data class ImportedActivityRunEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "identity") val identity: String,
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "capture_coverage") val captureCoverage: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
) {
	init {
		listOf(entryIdentity, identity, deletionScopeDigest, contentChecksum).forEach { value ->
			require(ImportedActivityIdentity.isDigest(value))
		}
		require(entryImportRevision > 0L)
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(captureCoverage in setOf("WHOLE_RUN", "PARTIAL_RUN", "NOT_CAPTURED"))
		require(collectedDataEpoch >= 0L && scopeDeletionGeneration >= 0L)
	}
}

/** Ordered civil-time authority copied from portable v1, not a local manifest. */
@Entity(
	tableName = "imported_activity_zone_epoch",
	primaryKeys = ["entry_identity", "entry_import_revision", "run_identity", "ordinal"],
	foreignKeys = [ForeignKey(
		entity = ImportedActivityRunEntity::class,
		parentColumns = ["entry_identity", "entry_import_revision", "identity"],
		childColumns = ["entry_identity", "entry_import_revision", "run_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(
		value = ["entry_identity", "entry_import_revision", "run_identity"],
		name = "idx_imported_activity_zone_run",
	)],
)
data class ImportedActivityZoneEpochEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "ordinal") val ordinal: Int,
	@ColumnInfo(name = "effective_wall_time_ms") val effectiveWallTimeMs: Long,
	@ColumnInfo(name = "zone_id") val zoneId: String,
) {
	init {
		listOf(entryIdentity, runIdentity).forEach { require(ImportedActivityIdentity.isDigest(it)) }
		require(entryImportRevision > 0L && ordinal >= 0 && effectiveWallTimeMs >= 0L)
		require(zoneId.isNotBlank() && zoneId.length <= MAX_TEXT_VALUE_LENGTH)
		ZoneId.of(zoneId)
	}
}

/** One exact imported Activity window. Offsets remain relative to its foreign physical run. */
@Entity(
	tableName = "imported_activity_window",
	primaryKeys = ["entry_identity", "entry_import_revision", "run_identity", "identity"],
	foreignKeys = [ForeignKey(
		entity = ImportedActivityRunEntity::class,
		parentColumns = ["entry_identity", "entry_import_revision", "identity"],
		childColumns = ["entry_identity", "entry_import_revision", "run_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["entry_identity", "entry_import_revision", "run_identity"],
			name = "idx_imported_activity_window_run",
		),
		Index(value = ["identity"], name = "idx_imported_activity_window_identity"),
	],
)
@Suppress("LongParameterList")
data class ImportedActivityWindowEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "identity") val identity: String,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "start_offset_nanos") val startOffsetNanos: Long,
	@ColumnInfo(name = "end_offset_nanos") val endOffsetNanos: Long,
	@ColumnInfo(name = "stored_zone_id") val storedZoneId: String,
	@ColumnInfo(name = "coverage") val coverage: String,
	@ColumnInfo(name = "known_active_duration_nanos") val knownActiveDurationNanos: Long,
	@ColumnInfo(name = "known_inactive_duration_nanos") val knownInactiveDurationNanos: Long,
	@ColumnInfo(name = "unknown_activity_duration_nanos") val unknownActivityDurationNanos: Long,
	@ColumnInfo(name = "unobserved_duration_nanos") val unobservedDurationNanos: Long,
) {
	init {
		listOf(entryIdentity, runIdentity, identity, contentChecksum).forEach {
			require(ImportedActivityIdentity.isDigest(it))
		}
		require(entryImportRevision > 0L)
		require(startOffsetNanos >= 0L && endOffsetNanos > startOffsetNanos)
		require(storedZoneId.isNotBlank() && storedZoneId.length <= MAX_TEXT_VALUE_LENGTH)
		ZoneId.of(storedZoneId)
		require(coverage in setOf("NONE", "PARTIAL", "COMPLETE"))
		listOf(
			knownActiveDurationNanos,
			knownInactiveDurationNanos,
			unknownActivityDurationNanos,
			unobservedDurationNanos,
		).forEach { require(it >= 0L) }
	}
}

/** Ordered complete coverage of one imported window, including explicit unobserved gaps. */
@Entity(
	tableName = "imported_activity_fragment",
	primaryKeys = [
		"entry_identity", "entry_import_revision", "run_identity", "window_identity", "ordinal",
	],
	foreignKeys = [ForeignKey(
		entity = ImportedActivityWindowEntity::class,
		parentColumns = ["entry_identity", "entry_import_revision", "run_identity", "identity"],
		childColumns = ["entry_identity", "entry_import_revision", "run_identity", "window_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(
		value = ["entry_identity", "entry_import_revision", "run_identity", "window_identity"],
		name = "idx_imported_activity_fragment_window",
	)],
)
@Suppress("LongParameterList")
data class ImportedActivityFragmentEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "window_identity") val windowIdentity: String,
	@ColumnInfo(name = "ordinal") val ordinal: Int,
	@ColumnInfo(name = "fragment_kind") val fragmentKind: String,
	@ColumnInfo(name = "start_offset_nanos") val startOffsetNanos: Long,
	@ColumnInfo(name = "end_offset_nanos") val endOffsetNanos: Long,
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
	@ColumnInfo(name = "end_wall_time_ms") val endWallTimeMs: Long?,
	@ColumnInfo(name = "end_wall_time_uncertainty_ms") val endWallTimeUncertaintyMs: Long?,
	@ColumnInfo(name = "end_boundary_kind") val endBoundaryKind: String?,
	@ColumnInfo(name = "wall_time_continuity") val wallTimeContinuity: String?,
) {
	init {
		listOf(entryIdentity, runIdentity, windowIdentity).forEach {
			require(ImportedActivityIdentity.isDigest(it))
		}
		require(entryImportRevision > 0L && ordinal >= 0)
		require(startOffsetNanos >= 0L && endOffsetNanos > startOffsetNanos)
		require(fragmentKind == KIND_GAP || fragmentKind == KIND_BAND)
		if (fragmentKind == KIND_GAP) {
			requireBoundedText(requireNotNull(gapReason))
			require(
				listOf(
					activity, mechanism, refinedTransitionActivity, confidenceKind,
					startBoundaryKind, endBoundaryKind, wallTimeContinuity,
				).all { it == null },
			)
			require(
				listOf(
					confidenceMinimumPercent, confidenceMaximumPercent, confidenceObservationCount,
					startWallTimeMs, startWallTimeUncertaintyMs, endWallTimeMs,
					endWallTimeUncertaintyMs,
				).all { it == null },
			)
		} else {
			require(gapReason == null)
			listOf(
				activity, mechanism, confidenceKind, startBoundaryKind, endBoundaryKind,
				wallTimeContinuity,
			).forEach { requireBoundedText(requireNotNull(it)) }
			refinedTransitionActivity?.let(::requireBoundedText)
			require(
				listOf(
					startWallTimeMs, startWallTimeUncertaintyMs, endWallTimeMs,
					endWallTimeUncertaintyMs,
				).all { it != null },
			)
		}
	}

	companion object {
		const val KIND_GAP = "GAP"
		const val KIND_BAND = "BAND"
	}
}

/** Entry-level no-resurrection authority retained after imported logical deletion. */
@Entity(tableName = "imported_activity_entry_deletion", primaryKeys = ["entry_identity"])
data class ImportedActivityEntryDeletionEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "deleted_import_revision") val deletedImportRevision: Long,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(ImportedActivityIdentity.isDigest(entryIdentity))
		require(collectedDataEpoch >= 0L && deletedImportRevision > 0L && deletedAtMs >= 0L)
		require(effectChecksum == checksum(entryIdentity, collectedDataEpoch, deletedImportRevision, deletedAtMs))
	}

	companion object {
		fun create(
			entryIdentity: String,
			collectedDataEpoch: Long,
			deletedImportRevision: Long,
			deletedAtMs: Long,
		) = ImportedActivityEntryDeletionEntity(
			entryIdentity,
			collectedDataEpoch,
			deletedImportRevision,
			deletedAtMs,
			checksum(entryIdentity, collectedDataEpoch, deletedImportRevision, deletedAtMs),
		)

		private fun checksum(identity: String, epoch: Long, revision: Long, deletedAtMs: Long) =
			ImportedActivityIdentity.digest(
				"tracker-imported-activity-entry-deletion-v1",
				listOf(identity, epoch.toString(), revision.toString(), deletedAtMs.toString()),
			)
	}
}

/** Run-level no-resurrection authority; portable v1 has no successor deletion generation token. */
@Entity(tableName = "imported_activity_deletion_generation", primaryKeys = ["run_identity"])
data class ImportedActivityDeletionGenerationEntity(
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "generation") val generation: Long,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(ImportedActivityIdentity.isDigest(runIdentity))
		require(collectedDataEpoch >= 0L && generation > 0L && deletedAtMs >= 0L)
		require(effectChecksum == checksum(runIdentity, collectedDataEpoch, generation, deletedAtMs))
	}

	companion object {
		fun create(
			runIdentity: String,
			collectedDataEpoch: Long,
			generation: Long,
			deletedAtMs: Long,
		) = ImportedActivityDeletionGenerationEntity(
			runIdentity,
			collectedDataEpoch,
			generation,
			deletedAtMs,
			checksum(runIdentity, collectedDataEpoch, generation, deletedAtMs),
		)

		private fun checksum(identity: String, epoch: Long, generation: Long, deletedAtMs: Long) =
			ImportedActivityIdentity.digest(
				"tracker-imported-activity-run-deletion-v1",
				listOf(identity, epoch.toString(), generation.toString(), deletedAtMs.toString()),
			)
	}
}

internal object ImportedActivityIdentity {
	private val digest = Regex("[0-9a-f]{64}")

	fun isDigest(value: String?): Boolean = value != null && digest.matches(value)

	fun requireProvenance(jobId: String, entryKey: String, sourceName: String) {
		listOf(jobId, entryKey, sourceName).forEach { value ->
			require(value.isNotBlank() && value.length <= MAX_PROVENANCE_LENGTH)
		}
	}

	fun digest(namespace: String, values: List<String>): String {
		val canonical = (listOf(namespace) + values).joinToString(separator = "") { "${it.length}:$it" }
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	private const val MAX_PROVENANCE_LENGTH = 4_096
}

private fun requireBoundedText(value: String) {
	require(value.isNotBlank() && value.length <= MAX_TEXT_VALUE_LENGTH)
}

private const val MAX_TEXT_VALUE_LENGTH = 128
