package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.adsamcik.tracker.shared.base.database.PortableActivityEntryV1
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Base64

/** Exact retained wall/zone interval used only for structural-day membership. */
data class ImportedActivityRetainedZoneRange(
	val startTimeMs: Long,
	val endInclusiveMs: Long,
	val storedZoneId: String,
) {
	init {
		require(startTimeMs >= 0L && endInclusiveMs >= startTimeMs)
		require(storedZoneId.isNotBlank() && storedZoneId.length <= MAX_TEXT_VALUE_LENGTH)
		ZoneId.of(storedZoneId)
	}
}

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

/**
 * Payload-free proof that one complete imported Activity correction lineage was removed by the
 * already-durable global retention floor. Selected entry deletion does not reuse this receipt.
 * Source-wide Activity erasure may retain a redacted zero-time receipt alongside an entry deletion
 * marker so typed child/scope collision authority survives without keeping a visible timing shell.
 */
@Entity(tableName = "imported_activity_retention_receipt", primaryKeys = ["entry_identity"])
@Suppress("LongParameterList")
data class ImportedActivityRetentionReceiptEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "source_evidence_revision") val sourceEvidenceRevision: Long,
	@ColumnInfo(name = "retained_from_ms") val retainedFromMs: Long,
	@ColumnInfo(name = "retained_at_ms") val retainedAtMs: Long,
	@ColumnInfo(name = "latest_import_revision") val latestImportRevision: Long,
	@ColumnInfo(name = "latest_content_checksum") val latestContentChecksum: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
	@ColumnInfo(name = "temporal_authority_state", defaultValue = "'UNAVAILABLE'")
	val temporalAuthorityState: String,
	@ColumnInfo(name = "latest_member_start_time_ms") val latestMemberStartTimeMs: Long?,
	@ColumnInfo(name = "latest_member_identity") val latestMemberIdentity: String?,
	@ColumnInfo(name = "structural_zone_range_count", defaultValue = "0")
	val structuralZoneRangeCount: Int,
	@ColumnInfo(name = "structural_zone_ranges_payload", defaultValue = "''")
	val structuralZoneRangesPayload: String,
	@ColumnInfo(name = "structural_zone_coverage_complete", defaultValue = "0")
	val structuralZoneCoverageComplete: Boolean,
	@ColumnInfo(name = "revision_count") val revisionCount: Int,
	@ColumnInfo(name = "import_receipt_count") val importReceiptCount: Int,
	@ColumnInfo(name = "run_row_count") val runRowCount: Int,
	@ColumnInfo(name = "zone_epoch_row_count") val zoneEpochRowCount: Int,
	@ColumnInfo(name = "window_row_count") val windowRowCount: Int,
	@ColumnInfo(name = "fragment_row_count") val fragmentRowCount: Int,
	@ColumnInfo(name = "run_deletion_count") val runDeletionCount: Int,
	@ColumnInfo(name = "run_deletion_set_checksum") val runDeletionSetChecksum: String,
	@ColumnInfo(name = "source_fence_count") val sourceFenceCount: Int,
	@ColumnInfo(name = "source_fence_set_checksum") val sourceFenceSetChecksum: String,
	@ColumnInfo(name = "protected_identity_count") val protectedIdentityCount: Int,
	@ColumnInfo(name = "protected_identity_set_checksum") val protectedIdentitySetChecksum: String,
	@ColumnInfo(name = "lineage_authority_checksum") val lineageAuthorityChecksum: String,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		listOf(
			entryIdentity,
			latestContentChecksum,
			runDeletionSetChecksum,
			sourceFenceSetChecksum,
			protectedIdentitySetChecksum,
			lineageAuthorityChecksum,
			effectChecksum,
		).forEach { require(ImportedActivityIdentity.isDigest(it)) }
		require(collectedDataEpoch >= 0L && sourceEvidenceRevision > 0L)
		require(retainedFromMs >= 0L && retainedAtMs >= 0L)
		require(latestImportRevision > 0L)
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(receivedAtMs in 0L..retainedAtMs)
		when (temporalAuthorityState) {
			TEMPORAL_AUTHORITY_AVAILABLE -> {
				require(latestMemberStartTimeMs != null &&
					latestMemberStartTimeMs in startTimeMs..endTimeMs)
				require(ImportedActivityIdentity.isDigest(latestMemberIdentity))
				require(structuralZoneRangeCount in 1..MAX_ZONE_ROWS)
				require(structuralZoneRangesPayload.length <= MAX_TEMPORAL_PAYLOAD_LENGTH)
				require(structuralZoneRanges().size == structuralZoneRangeCount)
			}
			TEMPORAL_AUTHORITY_UNAVAILABLE -> {
				require(latestMemberStartTimeMs == null && latestMemberIdentity == null)
				require(structuralZoneRangeCount == 0 && structuralZoneRangesPayload.isEmpty())
				require(!structuralZoneCoverageComplete)
			}
			TEMPORAL_AUTHORITY_REDACTED -> {
				require(startTimeMs == 0L && endTimeMs == 0L && receivedAtMs == 0L)
				require(latestMemberStartTimeMs == null && latestMemberIdentity == null)
				require(structuralZoneRangeCount == 0 && structuralZoneRangesPayload.isEmpty())
				require(!structuralZoneCoverageComplete)
			}
			else -> error("Unknown imported Activity retained temporal authority")
		}
		require(revisionCount in 1..MAX_REVISIONS)
		require(latestImportRevision == revisionCount.toLong())
		require(importReceiptCount in revisionCount..MAX_RECEIPTS)
		require(runRowCount in revisionCount..MAX_RUN_ROWS)
		require(zoneEpochRowCount in runRowCount..MAX_ZONE_ROWS)
		require(windowRowCount in revisionCount..MAX_WINDOW_ROWS)
		require(fragmentRowCount in windowRowCount..MAX_FRAGMENT_ROWS)
		require(runRowCount % revisionCount == 0)
		require(zoneEpochRowCount % revisionCount == 0)
		require(windowRowCount % revisionCount == 0)
		val stableRunCount = runRowCount / revisionCount
		val stableWindowCount = windowRowCount / revisionCount
		require(stableRunCount in 1..MAX_RUNS_PER_ENTRY)
		require(runDeletionCount in 0..stableRunCount)
		require(sourceFenceCount in 0..stableRunCount)
		require(protectedIdentityCount == 1 + stableRunCount * 2 + stableWindowCount)
		require(protectedIdentityCount <= MAX_PROTECTED_IDENTITIES)
		require(effectChecksum == when (temporalAuthorityState) {
			TEMPORAL_AUTHORITY_UNAVAILABLE -> legacyChecksum(this)
			else -> checksum(this)
		})
	}

	@Suppress("ComplexCondition")
	fun authenticates(markers: List<ImportedActivityRetainedIdentityEntity>): Boolean {
		val stableRunCount = runRowCount / revisionCount
		val stableWindowCount = windowRowCount / revisionCount
		return markers.size == protectedIdentityCount &&
			markers.all { it.entryIdentity == entryIdentity } &&
			markers.map { it.protectedIdentity }.distinct().size == markers.size &&
			markers.count { it.identityKind == ImportedActivityRetainedIdentityEntity.ENTRY } == 1 &&
			markers.count { it.identityKind == ImportedActivityRetainedIdentityEntity.RUN } ==
			stableRunCount &&
			markers.count { it.identityKind == ImportedActivityRetainedIdentityEntity.WINDOW } ==
			stableWindowCount &&
			markers.count {
				it.identityKind == ImportedActivityRetainedIdentityEntity.DELETION_SCOPE
			} == stableRunCount &&
			protectedIdentitySetChecksum == checksumProtectedIdentities(markers)
	}

	fun structuralZoneRanges(): List<ImportedActivityRetainedZoneRange> =
		decodeStructuralZoneRanges(structuralZoneRangesPayload, structuralZoneRangeCount)

	companion object {
		const val TEMPORAL_AUTHORITY_AVAILABLE = "AVAILABLE"
		const val TEMPORAL_AUTHORITY_UNAVAILABLE = "UNAVAILABLE"
		const val TEMPORAL_AUTHORITY_REDACTED = "REDACTED"

		@Suppress("LongParameterList")
		fun create(
			entryIdentity: String,
			collectedDataEpoch: Long,
			sourceEvidenceRevision: Long,
			retainedFromMs: Long,
			retainedAtMs: Long,
			latestImportRevision: Long,
			latestContentChecksum: String,
			startTimeMs: Long,
			endTimeMs: Long,
			receivedAtMs: Long,
			revisionCount: Int,
			importReceiptCount: Int,
			runRowCount: Int,
			zoneEpochRowCount: Int,
			windowRowCount: Int,
			fragmentRowCount: Int,
			runDeletions: List<ImportedActivityDeletionGenerationEntity>,
			sourceFences: List<SourceDeletionFenceEntity>,
			markers: List<ImportedActivityRetainedIdentityEntity>,
			lineageAuthorityChecksum: String,
			latestMemberStartTimeMs: Long,
			latestMemberIdentity: String,
			structuralZoneRanges: List<ImportedActivityRetainedZoneRange>,
			structuralZoneCoverageComplete: Boolean,
		): ImportedActivityRetentionReceiptEntity {
			require(latestMemberStartTimeMs in startTimeMs..endTimeMs)
			require(ImportedActivityIdentity.isDigest(latestMemberIdentity))
			require(structuralZoneRanges.isNotEmpty())
			val protectedChecksum = checksumProtectedIdentities(markers)
			val runDeletionChecksum =
				ImportedActivityEntryDeletionReceiptEntity.checksumRunDeletions(runDeletions)
			val sourceFenceChecksum =
				ImportedActivityEntryDeletionReceiptEntity.checksumSourceFences(sourceFences)
			val structuralPayload = encodeStructuralZoneRanges(structuralZoneRanges)
			return ImportedActivityRetentionReceiptEntity(
				entryIdentity = entryIdentity,
				collectedDataEpoch = collectedDataEpoch,
				sourceEvidenceRevision = sourceEvidenceRevision,
				retainedFromMs = retainedFromMs,
				retainedAtMs = retainedAtMs,
				latestImportRevision = latestImportRevision,
				latestContentChecksum = latestContentChecksum,
				startTimeMs = startTimeMs,
				endTimeMs = endTimeMs,
				receivedAtMs = receivedAtMs,
				temporalAuthorityState = TEMPORAL_AUTHORITY_AVAILABLE,
				latestMemberStartTimeMs = latestMemberStartTimeMs,
				latestMemberIdentity = latestMemberIdentity,
				structuralZoneRangeCount = structuralZoneRanges.size,
				structuralZoneRangesPayload = structuralPayload,
				structuralZoneCoverageComplete = structuralZoneCoverageComplete,
				revisionCount = revisionCount,
				importReceiptCount = importReceiptCount,
				runRowCount = runRowCount,
				zoneEpochRowCount = zoneEpochRowCount,
				windowRowCount = windowRowCount,
				fragmentRowCount = fragmentRowCount,
				runDeletionCount = runDeletions.size,
				runDeletionSetChecksum = runDeletionChecksum,
				sourceFenceCount = sourceFences.size,
				sourceFenceSetChecksum = sourceFenceChecksum,
				protectedIdentityCount = markers.size,
				protectedIdentitySetChecksum = protectedChecksum,
				lineageAuthorityChecksum = lineageAuthorityChecksum,
				effectChecksum = checksum(
					entryIdentity,
					collectedDataEpoch,
					sourceEvidenceRevision,
					retainedFromMs,
					retainedAtMs,
					latestImportRevision,
					latestContentChecksum,
					startTimeMs,
					endTimeMs,
					receivedAtMs,
					TEMPORAL_AUTHORITY_AVAILABLE,
					latestMemberStartTimeMs,
					latestMemberIdentity,
					structuralZoneRanges.size,
					structuralPayload,
					structuralZoneCoverageComplete,
					revisionCount,
					importReceiptCount,
					runRowCount,
					zoneEpochRowCount,
					windowRowCount,
					fragmentRowCount,
					runDeletions.size,
					runDeletionChecksum,
					sourceFences.size,
					sourceFenceChecksum,
					markers.size,
					protectedChecksum,
					lineageAuthorityChecksum,
				),
			)
		}

		/**
		 * Exact in-memory representation of a retained receipt created before temporal authority
		 * columns existed. Its original effect checksum remains authoritative.
		 */
		fun createTemporalAuthorityUnavailableFromLegacy(
			original: ImportedActivityRetentionReceiptEntity,
		): ImportedActivityRetentionReceiptEntity {
			require(original.temporalAuthorityState == TEMPORAL_AUTHORITY_AVAILABLE)
			return ImportedActivityRetentionReceiptEntity(
				entryIdentity = original.entryIdentity,
				collectedDataEpoch = original.collectedDataEpoch,
				sourceEvidenceRevision = original.sourceEvidenceRevision,
				retainedFromMs = original.retainedFromMs,
				retainedAtMs = original.retainedAtMs,
				latestImportRevision = original.latestImportRevision,
				latestContentChecksum = original.latestContentChecksum,
				startTimeMs = original.startTimeMs,
				endTimeMs = original.endTimeMs,
				receivedAtMs = original.receivedAtMs,
				temporalAuthorityState = TEMPORAL_AUTHORITY_UNAVAILABLE,
				latestMemberStartTimeMs = null,
				latestMemberIdentity = null,
				structuralZoneRangeCount = 0,
				structuralZoneRangesPayload = "",
				structuralZoneCoverageComplete = false,
				revisionCount = original.revisionCount,
				importReceiptCount = original.importReceiptCount,
				runRowCount = original.runRowCount,
				zoneEpochRowCount = original.zoneEpochRowCount,
				windowRowCount = original.windowRowCount,
				fragmentRowCount = original.fragmentRowCount,
				runDeletionCount = original.runDeletionCount,
				runDeletionSetChecksum = original.runDeletionSetChecksum,
				sourceFenceCount = original.sourceFenceCount,
				sourceFenceSetChecksum = original.sourceFenceSetChecksum,
				protectedIdentityCount = original.protectedIdentityCount,
				protectedIdentitySetChecksum = original.protectedIdentitySetChecksum,
				lineageAuthorityChecksum = original.lineageAuthorityChecksum,
				effectChecksum = legacyChecksum(original),
			)
		}

		@Suppress("LongParameterList")
		fun createRedacted(
			original: ImportedActivityRetentionReceiptEntity,
			sourceEvidenceRevision: Long,
			retainedAtMs: Long,
			runDeletions: List<ImportedActivityDeletionGenerationEntity>,
			sourceFences: List<SourceDeletionFenceEntity>,
			markers: List<ImportedActivityRetainedIdentityEntity>,
		): ImportedActivityRetentionReceiptEntity {
			val runDeletionChecksum =
				ImportedActivityEntryDeletionReceiptEntity.checksumRunDeletions(runDeletions)
			val sourceFenceChecksum =
				ImportedActivityEntryDeletionReceiptEntity.checksumSourceFences(sourceFences)
			val protectedChecksum = checksumProtectedIdentities(markers)
			return ImportedActivityRetentionReceiptEntity(
				entryIdentity = original.entryIdentity,
				collectedDataEpoch = original.collectedDataEpoch,
				sourceEvidenceRevision = sourceEvidenceRevision,
				retainedFromMs = original.retainedFromMs,
				retainedAtMs = retainedAtMs,
				latestImportRevision = original.latestImportRevision,
				latestContentChecksum = original.latestContentChecksum,
				startTimeMs = 0L,
				endTimeMs = 0L,
				receivedAtMs = 0L,
				temporalAuthorityState = TEMPORAL_AUTHORITY_REDACTED,
				latestMemberStartTimeMs = null,
				latestMemberIdentity = null,
				structuralZoneRangeCount = 0,
				structuralZoneRangesPayload = "",
				structuralZoneCoverageComplete = false,
				revisionCount = original.revisionCount,
				importReceiptCount = original.importReceiptCount,
				runRowCount = original.runRowCount,
				zoneEpochRowCount = original.zoneEpochRowCount,
				windowRowCount = original.windowRowCount,
				fragmentRowCount = original.fragmentRowCount,
				runDeletionCount = runDeletions.size,
				runDeletionSetChecksum = runDeletionChecksum,
				sourceFenceCount = sourceFences.size,
				sourceFenceSetChecksum = sourceFenceChecksum,
				protectedIdentityCount = markers.size,
				protectedIdentitySetChecksum = protectedChecksum,
				lineageAuthorityChecksum = original.lineageAuthorityChecksum,
				effectChecksum = checksum(
					original.entryIdentity,
					original.collectedDataEpoch,
					sourceEvidenceRevision,
					original.retainedFromMs,
					retainedAtMs,
					original.latestImportRevision,
					original.latestContentChecksum,
					0L,
					0L,
					0L,
					TEMPORAL_AUTHORITY_REDACTED,
					null,
					null,
					0,
					"",
					false,
					original.revisionCount,
					original.importReceiptCount,
					original.runRowCount,
					original.zoneEpochRowCount,
					original.windowRowCount,
					original.fragmentRowCount,
					runDeletions.size,
					runDeletionChecksum,
					sourceFences.size,
					sourceFenceChecksum,
					markers.size,
					protectedChecksum,
					original.lineageAuthorityChecksum,
				),
			)
		}

		fun checksumProtectedIdentities(values: List<ImportedActivityRetainedIdentityEntity>): String {
			require(values.isNotEmpty() && values.size <= MAX_PROTECTED_IDENTITIES)
			require(values.map { it.protectedIdentity }.distinct().size == values.size)
			return ImportedActivityIdentity.digest(
				"tracker-imported-activity-retained-identity-set-v1",
				listOf(values.size.toString()) + values.sortedWith(
					compareBy(ImportedActivityRetainedIdentityEntity::identityKind)
						.thenBy(ImportedActivityRetainedIdentityEntity::protectedIdentity),
				).flatMap { listOf(it.identityKind, it.protectedIdentity) },
			)
		}

		fun lineageAuthorityChecksum(
			headers: List<ImportedActivityEntryRevisionEntity>,
			receipts: List<ImportedActivityReceiptEntity>,
		): String {
			require(headers.isNotEmpty() && headers.size <= MAX_REVISIONS)
			require(receipts.size in headers.size..MAX_RECEIPTS)
			return ImportedActivityIdentity.digest(
				"tracker-imported-activity-retained-lineage-authority-v1",
				listOf(headers.size.toString()) + headers.sortedBy { it.importRevision }.flatMap { value ->
					listOf(
						value.identity,
						value.importRevision.toString(),
						value.supersedesImportRevision?.toString() ?: "NONE",
						value.contentChecksum,
						value.sourceFormat,
						value.sourceSchemaVersion.toString(),
						value.sessionMode,
						value.startTimeMs.toString(),
						value.endTimeMs.toString(),
						value.collectedDataEpoch.toString(),
						value.importJobId,
						value.importEntryKey,
						value.importSourceName,
						value.receivedAtMs.toString(),
					)
				} + listOf(receipts.size.toString()) + receipts.sortedWith(
					compareBy(ImportedActivityReceiptEntity::entryImportRevision)
						.thenBy(ImportedActivityReceiptEntity::importJobId)
						.thenBy(ImportedActivityReceiptEntity::importEntryKey),
				).flatMap { value ->
					listOf(
						value.importJobId,
						value.importEntryKey,
						value.importSourceName,
						value.receivedAtMs.toString(),
						value.entryImportRevision.toString(),
						value.entryContentChecksum,
						value.collectedDataEpoch.toString(),
					)
				},
			)
		}

		private fun checksum(value: ImportedActivityRetentionReceiptEntity) = checksum(
			value.entryIdentity,
			value.collectedDataEpoch,
			value.sourceEvidenceRevision,
			value.retainedFromMs,
			value.retainedAtMs,
			value.latestImportRevision,
			value.latestContentChecksum,
			value.startTimeMs,
			value.endTimeMs,
			value.receivedAtMs,
			value.temporalAuthorityState,
			value.latestMemberStartTimeMs,
			value.latestMemberIdentity,
			value.structuralZoneRangeCount,
			value.structuralZoneRangesPayload,
			value.structuralZoneCoverageComplete,
			value.revisionCount,
			value.importReceiptCount,
			value.runRowCount,
			value.zoneEpochRowCount,
			value.windowRowCount,
			value.fragmentRowCount,
			value.runDeletionCount,
			value.runDeletionSetChecksum,
			value.sourceFenceCount,
			value.sourceFenceSetChecksum,
			value.protectedIdentityCount,
			value.protectedIdentitySetChecksum,
			value.lineageAuthorityChecksum,
		)

		private fun legacyChecksum(value: ImportedActivityRetentionReceiptEntity) =
			ImportedActivityIdentity.digest(
				"tracker-imported-activity-retention-receipt-v1",
				listOf(
					value.entryIdentity,
					value.collectedDataEpoch.toString(),
					value.sourceEvidenceRevision.toString(),
					value.retainedFromMs.toString(),
					value.retainedAtMs.toString(),
					value.latestImportRevision.toString(),
					value.latestContentChecksum,
					value.startTimeMs.toString(),
					value.endTimeMs.toString(),
					value.receivedAtMs.toString(),
					value.revisionCount.toString(),
					value.importReceiptCount.toString(),
					value.runRowCount.toString(),
					value.zoneEpochRowCount.toString(),
					value.windowRowCount.toString(),
					value.fragmentRowCount.toString(),
					value.runDeletionCount.toString(),
					value.runDeletionSetChecksum,
					value.sourceFenceCount.toString(),
					value.sourceFenceSetChecksum,
					value.protectedIdentityCount.toString(),
					value.protectedIdentitySetChecksum,
					value.lineageAuthorityChecksum,
				),
			)

		@Suppress("LongParameterList")
		private fun checksum(
			entryIdentity: String,
			collectedDataEpoch: Long,
			sourceEvidenceRevision: Long,
			retainedFromMs: Long,
			retainedAtMs: Long,
			latestImportRevision: Long,
			latestContentChecksum: String,
			startTimeMs: Long,
			endTimeMs: Long,
			receivedAtMs: Long,
			temporalAuthorityState: String,
			latestMemberStartTimeMs: Long?,
			latestMemberIdentity: String?,
			structuralZoneRangeCount: Int,
			structuralZoneRangesPayload: String,
			structuralZoneCoverageComplete: Boolean,
			revisionCount: Int,
			importReceiptCount: Int,
			runRowCount: Int,
			zoneEpochRowCount: Int,
			windowRowCount: Int,
			fragmentRowCount: Int,
			runDeletionCount: Int,
			runDeletionSetChecksum: String,
			sourceFenceCount: Int,
			sourceFenceSetChecksum: String,
			protectedIdentityCount: Int,
			protectedIdentitySetChecksum: String,
			lineageAuthorityChecksum: String,
		) = ImportedActivityIdentity.digest(
			"tracker-imported-activity-retention-receipt-v1",
			listOf(
				entryIdentity,
				collectedDataEpoch.toString(),
				sourceEvidenceRevision.toString(),
				retainedFromMs.toString(),
				retainedAtMs.toString(),
				latestImportRevision.toString(),
				latestContentChecksum,
				startTimeMs.toString(),
				endTimeMs.toString(),
				receivedAtMs.toString(),
				temporalAuthorityState,
				latestMemberStartTimeMs?.toString() ?: "NONE",
				latestMemberIdentity ?: "NONE",
				structuralZoneRangeCount.toString(),
				structuralZoneRangesPayload,
				structuralZoneCoverageComplete.toString(),
				revisionCount.toString(),
				importReceiptCount.toString(),
				runRowCount.toString(),
				zoneEpochRowCount.toString(),
				windowRowCount.toString(),
				fragmentRowCount.toString(),
				runDeletionCount.toString(),
				runDeletionSetChecksum,
				sourceFenceCount.toString(),
				sourceFenceSetChecksum,
				protectedIdentityCount.toString(),
				protectedIdentitySetChecksum,
				lineageAuthorityChecksum,
			),
		)

		private const val MAX_REVISIONS = 16
		private const val MAX_RECEIPTS = 256
		private const val MAX_RUN_ROWS = MAX_REVISIONS * 64
		private const val MAX_RUNS_PER_ENTRY = 64
		private const val MAX_ZONE_ROWS = 16_384
		private const val MAX_WINDOW_ROWS = 65_536
		private const val MAX_FRAGMENT_ROWS = 524_288
		private const val MAX_PROTECTED_IDENTITIES = 1 + 64 + 16_384 + 64
		private const val MAX_TEMPORAL_PAYLOAD_LENGTH = 4 * 1_024 * 1_024
	}
}

/** One globally exclusive semantic identity retained without any Activity payload. */
@Entity(
	tableName = "imported_activity_retained_identity",
	primaryKeys = ["protected_identity"],
	foreignKeys = [ForeignKey(
		entity = ImportedActivityRetentionReceiptEntity::class,
		parentColumns = ["entry_identity"],
		childColumns = ["entry_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(value = ["entry_identity"], name = "idx_imported_activity_retained_identity_entry")],
)
data class ImportedActivityRetainedIdentityEntity(
	@ColumnInfo(name = "protected_identity") val protectedIdentity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "identity_kind") val identityKind: String,
) {
	init {
		require(ImportedActivityIdentity.isDigest(protectedIdentity))
		require(ImportedActivityIdentity.isDigest(entryIdentity))
		require(identityKind in ALL_KINDS)
		require((identityKind == ENTRY) == (protectedIdentity == entryIdentity))
	}

	companion object {
		const val ENTRY = "ENTRY"
		const val RUN = "RUN"
		const val WINDOW = "WINDOW"
		const val DELETION_SCOPE = "DELETION_SCOPE"
		private val ALL_KINDS = setOf(ENTRY, RUN, WINDOW, DELETION_SCOPE)
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

/** Activity-only exact-replay receipt retained with the entry no-resurrection marker. */
@Entity(
	tableName = "imported_activity_entry_deletion_receipt",
	primaryKeys = ["entry_identity"],
	foreignKeys = [ForeignKey(
		entity = ImportedActivityEntryDeletionEntity::class,
		parentColumns = ["entry_identity"],
		childColumns = ["entry_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
)
@Suppress("LongParameterList")
data class ImportedActivityEntryDeletionReceiptEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "deleted_import_revision") val deletedImportRevision: Long,
	@ColumnInfo(name = "deleted_content_checksum") val deletedContentChecksum: String,
	@ColumnInfo(name = "expected_run_count") val expectedRunCount: Int,
	@ColumnInfo(name = "run_scope_set_checksum") val runScopeSetChecksum: String,
	@ColumnInfo(name = "expected_window_count") val expectedWindowCount: Int,
	@ColumnInfo(name = "window_identity_set_checksum") val windowIdentitySetChecksum: String,
	@ColumnInfo(name = "run_deletion_set_checksum") val runDeletionSetChecksum: String,
	@ColumnInfo(name = "source_fence_count") val sourceFenceCount: Int,
	@ColumnInfo(name = "source_fence_set_checksum") val sourceFenceSetChecksum: String,
	@ColumnInfo(name = "retained_from_ms") val retainedFromMs: Long?,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		listOf(
			entryIdentity,
			deletedContentChecksum,
			runScopeSetChecksum,
			windowIdentitySetChecksum,
			runDeletionSetChecksum,
			sourceFenceSetChecksum,
			effectChecksum,
		).forEach { require(ImportedActivityIdentity.isDigest(it)) }
		require(collectedDataEpoch >= 0L && deletedImportRevision > 0L && deletedAtMs >= 0L)
		require(expectedRunCount in 1..MAX_EXPECTED_RUNS)
		require(expectedWindowCount in 1..MAX_EXPECTED_WINDOWS)
		require(sourceFenceCount in 0..expectedRunCount)
		require(retainedFromMs == null || retainedFromMs >= 0L)
		require(effectChecksum == checksum(this))
	}

	companion object {
		@Suppress("LongParameterList")
		fun create(
			entryDeletion: ImportedActivityEntryDeletionEntity,
			deletedContentChecksum: String,
			runScopes: List<Pair<String, String>>,
			windowIdentities: List<String>,
			runDeletions: List<ImportedActivityDeletionGenerationEntity>,
			sourceFences: List<SourceDeletionFenceEntity>,
			retainedFromMs: Long?,
		): ImportedActivityEntryDeletionReceiptEntity {
			require(ImportedActivityIdentity.isDigest(deletedContentChecksum))
			require(retainedFromMs == null || retainedFromMs >= 0L)
			requireValidRunScopes(runScopes)
			requireValidWindowIdentities(windowIdentities)
			val runIdentities = runScopes.mapTo(linkedSetOf()) { it.first }
			require(runDeletions.size == runScopes.size)
			require(runDeletions.mapTo(linkedSetOf()) { it.runIdentity } == runIdentities)
			require(runDeletions.all {
				it.collectedDataEpoch == entryDeletion.collectedDataEpoch && it.generation == 1L
			})
			val scopeDigests = runScopes.mapTo(linkedSetOf()) { it.second }
			require(entryDeletion.entryIdentity !in runIdentities)
			require(entryDeletion.entryIdentity !in scopeDigests)
			require(entryDeletion.entryIdentity !in windowIdentities)
			require(windowIdentities.none { it in runIdentities || it in scopeDigests })
			require(sourceFences.distinctBy { it.scopeIdentityDigest }.size == sourceFences.size)
			require(sourceFences.all {
				it.sourceKind == SourceDestinationOwnerEntity.SOURCE_ACTIVITY &&
					it.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
					it.scopeKind == SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN &&
					it.scopeIdentityDigest in scopeDigests &&
					it.collectedDataEpoch == entryDeletion.collectedDataEpoch &&
					it.fenceGeneration == 1L
			})
			val runScopeSetChecksum = checksumRunScopes(runScopes)
			val windowIdentitySetChecksum = checksumWindowIdentities(windowIdentities)
			val runDeletionSetChecksum = checksumRunDeletions(runDeletions)
			val sourceFenceSetChecksum = checksumSourceFences(sourceFences)
			val effectChecksum = checksum(
				entryIdentity = entryDeletion.entryIdentity,
				collectedDataEpoch = entryDeletion.collectedDataEpoch,
				deletedImportRevision = entryDeletion.deletedImportRevision,
				deletedContentChecksum = deletedContentChecksum,
				expectedRunCount = runScopes.size,
				runScopeSetChecksum = runScopeSetChecksum,
				expectedWindowCount = windowIdentities.size,
				windowIdentitySetChecksum = windowIdentitySetChecksum,
				runDeletionSetChecksum = runDeletionSetChecksum,
				sourceFenceCount = sourceFences.size,
				sourceFenceSetChecksum = sourceFenceSetChecksum,
				retainedFromMs = retainedFromMs,
				deletedAtMs = entryDeletion.deletedAtMs,
			)
			return ImportedActivityEntryDeletionReceiptEntity(
				entryIdentity = entryDeletion.entryIdentity,
				collectedDataEpoch = entryDeletion.collectedDataEpoch,
				deletedImportRevision = entryDeletion.deletedImportRevision,
				deletedContentChecksum = deletedContentChecksum,
				expectedRunCount = runScopes.size,
				runScopeSetChecksum = runScopeSetChecksum,
				expectedWindowCount = windowIdentities.size,
				windowIdentitySetChecksum = windowIdentitySetChecksum,
				runDeletionSetChecksum = runDeletionSetChecksum,
				sourceFenceCount = sourceFences.size,
				sourceFenceSetChecksum = sourceFenceSetChecksum,
				retainedFromMs = retainedFromMs,
				deletedAtMs = entryDeletion.deletedAtMs,
				effectChecksum = effectChecksum,
			)
		}

		fun checksumRunScopes(values: List<Pair<String, String>>): String {
			requireValidRunScopes(values)
			return ImportedActivityIdentity.digest(
				"tracker-imported-activity-deletion-run-scope-set-v1",
				listOf(values.size.toString()) + values.sortedWith(
					compareBy<Pair<String, String>>({ it.first }, { it.second }),
				).flatMap { (runIdentity, scopeDigest) -> listOf(runIdentity, scopeDigest) },
			)
		}

		fun checksumWindowIdentities(values: List<String>): String {
			requireValidWindowIdentities(values)
			return ImportedActivityIdentity.digest(
				"tracker-imported-activity-deletion-window-identity-set-v1",
				listOf(values.size.toString()) + values.sorted(),
			)
		}

		fun checksumRunDeletions(values: List<ImportedActivityDeletionGenerationEntity>): String =
			ImportedActivityIdentity.digest(
				"tracker-imported-activity-deletion-run-marker-set-v1",
				listOf(values.size.toString()) + values.sortedBy { it.runIdentity }.flatMap { value ->
					listOf(
						value.runIdentity,
						value.collectedDataEpoch.toString(),
						value.generation.toString(),
						value.deletedAtMs.toString(),
						value.effectChecksum,
					)
				},
			)

		fun checksumSourceFences(values: List<SourceDeletionFenceEntity>): String =
			ImportedActivityIdentity.digest(
				"tracker-imported-activity-deletion-source-fence-set-v1",
				listOf(values.size.toString()) + values.sortedBy { it.scopeIdentityDigest }.flatMap { value ->
					listOf(
						value.sourceKind.toString(),
						value.purpose,
						value.scopeKind,
						value.scopeIdentityDigest,
						value.fenceGeneration.toString(),
						value.collectedDataEpoch.toString(),
						value.deletedAtMs.toString(),
						value.effectChecksum,
					)
				},
			)

		private fun requireValidRunScopes(values: List<Pair<String, String>>) {
			require(values.size in 1..MAX_EXPECTED_RUNS)
			values.flatMap { listOf(it.first, it.second) }.forEach {
				require(ImportedActivityIdentity.isDigest(it))
			}
			require(values.distinctBy { it.first }.size == values.size)
			require(values.distinctBy { it.second }.size == values.size)
			val identities = values.mapTo(linkedSetOf()) { it.first }
			require(values.none { it.second in identities })
		}

		private fun requireValidWindowIdentities(values: List<String>) {
			require(values.size in 1..MAX_EXPECTED_WINDOWS)
			require(values.distinct().size == values.size)
			values.forEach { require(ImportedActivityIdentity.isDigest(it)) }
		}

		private fun checksum(value: ImportedActivityEntryDeletionReceiptEntity) = checksum(
			entryIdentity = value.entryIdentity,
			collectedDataEpoch = value.collectedDataEpoch,
			deletedImportRevision = value.deletedImportRevision,
			deletedContentChecksum = value.deletedContentChecksum,
			expectedRunCount = value.expectedRunCount,
			runScopeSetChecksum = value.runScopeSetChecksum,
			expectedWindowCount = value.expectedWindowCount,
			windowIdentitySetChecksum = value.windowIdentitySetChecksum,
			runDeletionSetChecksum = value.runDeletionSetChecksum,
			sourceFenceCount = value.sourceFenceCount,
			sourceFenceSetChecksum = value.sourceFenceSetChecksum,
			retainedFromMs = value.retainedFromMs,
			deletedAtMs = value.deletedAtMs,
		)

		@Suppress("LongParameterList")
		private fun checksum(
			entryIdentity: String,
			collectedDataEpoch: Long,
			deletedImportRevision: Long,
			deletedContentChecksum: String,
			expectedRunCount: Int,
			runScopeSetChecksum: String,
			expectedWindowCount: Int,
			windowIdentitySetChecksum: String,
			runDeletionSetChecksum: String,
			sourceFenceCount: Int,
			sourceFenceSetChecksum: String,
			retainedFromMs: Long?,
			deletedAtMs: Long,
		) =
			ImportedActivityIdentity.digest(
				"tracker-imported-activity-entry-deletion-receipt-v1",
				listOf(
					entryIdentity,
					collectedDataEpoch.toString(),
					deletedImportRevision.toString(),
					deletedContentChecksum,
					expectedRunCount.toString(),
					runScopeSetChecksum,
					expectedWindowCount.toString(),
					windowIdentitySetChecksum,
					runDeletionSetChecksum,
					sourceFenceCount.toString(),
					sourceFenceSetChecksum,
					retainedFromMs?.toString() ?: "NONE",
					deletedAtMs.toString(),
				),
			)

		private const val MAX_EXPECTED_RUNS = 64
		private const val MAX_EXPECTED_WINDOWS = 16_384
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

internal data class ImportedActivityRetainedTemporalAuthority(
	val latestMemberStartTimeMs: Long,
	val latestMemberIdentity: String,
	val ranges: List<ImportedActivityRetainedZoneRange>,
	val complete: Boolean,
)

internal fun PortableActivityEntryV1.toImportedActivityRetainedTemporalAuthority():
	ImportedActivityRetainedTemporalAuthority {
	var complete = true
	val ranges = runs.flatMap { run ->
		require(run.zoneEpochs.map { it.effectiveWallTimeMs }.distinct().size == run.zoneEpochs.size)
		val runEndInclusive = if (run.endTimeMs > run.startTimeMs) {
			run.endTimeMs - 1L
		} else {
			run.endTimeMs
		}
		val effectiveAtStart = run.zoneEpochs.lastOrNull {
			it.effectiveWallTimeMs <= run.startTimeMs
		}
		if (effectiveAtStart == null) complete = false
		run.zoneEpochs.mapIndexedNotNull { index, epoch ->
			val start = maxOf(run.startTimeMs, epoch.effectiveWallTimeMs)
			val nextStart = run.zoneEpochs.getOrNull(index + 1)?.effectiveWallTimeMs
			val end = minOf(
				runEndInclusive,
				nextStart?.let { if (it == 0L) -1L else it - 1L } ?: runEndInclusive,
			)
			if (end < start) {
				null
			} else {
				ImportedActivityRetainedZoneRange(start, end, epoch.zoneId)
			}
		}
	}.sortedWith(
		compareBy(ImportedActivityRetainedZoneRange::startTimeMs)
			.thenBy(ImportedActivityRetainedZoneRange::endInclusiveMs)
			.thenBy(ImportedActivityRetainedZoneRange::storedZoneId),
	)
	require(ranges.isNotEmpty() && ranges.size <= 16_384)
	val latestMember = runs.maxWith(
		compareBy({ it.startTimeMs }, { it.identity.value }),
	)
	return ImportedActivityRetainedTemporalAuthority(
		latestMemberStartTimeMs = latestMember.startTimeMs,
		latestMemberIdentity = latestMember.identity.value,
		ranges = ranges,
		complete = complete,
	)
}

private fun encodeStructuralZoneRanges(
	values: List<ImportedActivityRetainedZoneRange>,
): String = values.joinToString(separator = ";") { value ->
	val zone = Base64.getUrlEncoder().withoutPadding()
		.encodeToString(value.storedZoneId.toByteArray(Charsets.UTF_8))
	"${value.startTimeMs},${value.endInclusiveMs},$zone"
}

private fun decodeStructuralZoneRanges(
	payload: String,
	expectedCount: Int,
): List<ImportedActivityRetainedZoneRange> {
	if (expectedCount == 0) {
		require(payload.isEmpty())
		return emptyList()
	}
	require(payload.isNotEmpty())
	val ranges = payload.split(';').map { encoded ->
		val parts = encoded.split(',', limit = 3)
		require(parts.size == 3)
		ImportedActivityRetainedZoneRange(
			startTimeMs = parts[0].toLong(),
			endInclusiveMs = parts[1].toLong(),
			storedZoneId = Base64.getUrlDecoder().decode(parts[2]).toString(Charsets.UTF_8),
		)
	}
	require(ranges.size == expectedCount)
	require(ranges == ranges.sortedWith(
		compareBy(ImportedActivityRetainedZoneRange::startTimeMs)
			.thenBy(ImportedActivityRetainedZoneRange::endInclusiveMs)
			.thenBy(ImportedActivityRetainedZoneRange::storedZoneId),
	))
	return ranges
}

private fun requireBoundedText(value: String) {
	require(value.isNotBlank() && value.length <= MAX_TEXT_VALUE_LENGTH)
}

private const val MAX_TEXT_VALUE_LENGTH = 128
