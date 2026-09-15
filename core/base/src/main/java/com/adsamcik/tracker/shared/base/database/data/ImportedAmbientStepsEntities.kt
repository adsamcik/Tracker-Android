package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapReason
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsPartialCause
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/** Immutable archive metadata. It contains no local provider, session, consent, or writer identity. */
@Entity(
	tableName = "imported_ambient_steps_archive",
	primaryKeys = ["archive_identity"],
	indices = [Index(
		value = ["content_checksum"],
		unique = true,
		name = "idx_imported_ambient_steps_archive_checksum",
	)],
)
data class ImportedAmbientStepsArchiveEntity(
	@ColumnInfo(name = "archive_identity") val archiveIdentity: String,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "source_format") val sourceFormat: String,
	@ColumnInfo(name = "source_schema_version") val sourceSchemaVersion: Int,
	@ColumnInfo(name = "encoded_byte_count") val encodedByteCount: Long,
	@ColumnInfo(name = "day_count") val dayCount: Int,
	@ColumnInfo(name = "fact_count") val factCount: Int,
	@ColumnInfo(name = "gap_count") val gapCount: Int,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "first_received_at_ms") val firstReceivedAtMs: Long,
) {
	init {
		require(ImportedAmbientStepsIdentity.isDigest(archiveIdentity))
		require(ImportedAmbientStepsIdentity.isDigest(contentChecksum))
		require(
			AmbientStepsPortableOpaqueIdentity(archiveIdentity) ==
				AmbientStepsPortableOpaqueIdentity.derive(
					AmbientStepsPortableIdentityKind.ARCHIVE,
					contentChecksum,
				),
		)
		require(sourceFormat == AmbientStepsPortableFormatV1.FORMAT)
		require(sourceSchemaVersion == AmbientStepsPortableFormatV1.SCHEMA_VERSION)
		require(encodedByteCount in 1L..AmbientStepsPortableFormatV1.MAX_FILE_BYTES)
		require(dayCount in 1..AmbientStepsPortableFormatV1.MAX_DAYS)
		require(factCount in 1..AmbientStepsPortableFormatV1.MAX_FACTS)
		require(gapCount in 0..AmbientStepsPortableFormatV1.MAX_GAPS)
		require(collectedDataEpoch >= 0L && firstReceivedAtMs >= 0L)
	}
}

/** Exact receipt claim. Alternate receipts may bind one already-authenticated archive. */
@Entity(
	tableName = "imported_ambient_steps_receipt",
	primaryKeys = ["import_job_id", "archive_key"],
	foreignKeys = [ForeignKey(
		entity = ImportedAmbientStepsArchiveEntity::class,
		parentColumns = ["archive_identity"],
		childColumns = ["archive_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["archive_identity"],
			name = "idx_imported_ambient_steps_receipt_archive",
		),
		Index(
			value = ["receipt_identity"],
			unique = true,
			name = "idx_imported_ambient_steps_receipt_identity",
		),
	],
)
data class ImportedAmbientStepsReceiptEntity(
	@ColumnInfo(name = "import_job_id") val importJobId: String,
	@ColumnInfo(name = "archive_key") val archiveKey: String,
	@ColumnInfo(name = "receipt_identity") val receiptIdentity: String,
	@ColumnInfo(name = "source_name") val sourceName: String,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
	@ColumnInfo(name = "archive_identity") val archiveIdentity: String,
	@ColumnInfo(name = "archive_content_checksum") val archiveContentChecksum: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
) {
	init {
		ImportedAmbientStepsIdentity.requireReceipt(importJobId, archiveKey, sourceName)
		require(receiptIdentity == ImportedAmbientStepsIdentity.receipt(importJobId, archiveKey))
		require(receivedAtMs >= 0L)
		require(ImportedAmbientStepsIdentity.isDigest(archiveIdentity))
		require(ImportedAmbientStepsIdentity.isDigest(archiveContentChecksum))
		require(collectedDataEpoch >= 0L)
	}
}

/** Immutable archive-to-entry membership, including archives that only repeat retained revisions. */
@Entity(
	tableName = "imported_ambient_steps_archive_day",
	primaryKeys = ["archive_identity", "ordinal"],
	foreignKeys = [ForeignKey(
		entity = ImportedAmbientStepsArchiveEntity::class,
		parentColumns = ["archive_identity"],
		childColumns = ["archive_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["archive_identity", "day_identity"],
			unique = true,
			name = "idx_imported_ambient_steps_archive_day_owner",
		),
		Index(
			value = ["day_identity"],
			name = "idx_imported_ambient_steps_archive_day_entry",
		),
	],
)
data class ImportedAmbientStepsArchiveDayEntity(
	@ColumnInfo(name = "archive_identity") val archiveIdentity: String,
	@ColumnInfo(name = "ordinal") val ordinal: Int,
	@ColumnInfo(name = "day_identity") val dayIdentity: String,
	@ColumnInfo(name = "day_content_checksum") val dayContentChecksum: String,
	@ColumnInfo(name = "bound_day_import_revision") val boundDayImportRevision: Long,
	@ColumnInfo(name = "fact_count") val factCount: Int,
	@ColumnInfo(name = "gap_count") val gapCount: Int,
) {
	init {
		listOf(archiveIdentity, dayIdentity, dayContentChecksum).forEach {
			require(ImportedAmbientStepsIdentity.isDigest(it))
		}
		require(ordinal >= 0)
		require(boundDayImportRevision > 0L)
		require(factCount in 1..AmbientStepsPortableFormatV1.MAX_FACTS_PER_DAY)
		require(gapCount in 0..AmbientStepsPortableFormatV1.MAX_GAPS_PER_DAY)
	}
}

/** Destination-local correction revision for one immutable portable structural-day identity. */
@Entity(
	tableName = "imported_ambient_steps_day_revision",
	primaryKeys = ["day_identity", "import_revision"],
	foreignKeys = [ForeignKey(
		entity = ImportedAmbientStepsArchiveEntity::class,
		parentColumns = ["archive_identity"],
		childColumns = ["archive_identity"],
		onDelete = ForeignKey.RESTRICT,
	)],
	indices = [
		Index(
			value = ["archive_identity", "day_identity"],
			unique = true,
			name = "idx_imported_ambient_steps_day_archive_owner",
		),
		Index(
			value = [
				"structural_epoch_day",
				"stored_zone_id",
				"structural_day_start_time_ms",
			],
			name = "idx_imported_ambient_steps_day_structural",
		),
		Index(
			value = ["deletion_scope_identity"],
			name = "idx_imported_ambient_steps_day_scope",
		),
	],
)
data class ImportedAmbientStepsDayRevisionEntity(
	@ColumnInfo(name = "day_identity") val dayIdentity: String,
	@ColumnInfo(name = "import_revision") val importRevision: Long,
	@ColumnInfo(name = "supersedes_import_revision") val supersedesImportRevision: Long?,
	@ColumnInfo(name = "archive_identity") val archiveIdentity: String,
	@ColumnInfo(name = "day_content_checksum") val dayContentChecksum: String,
	@ColumnInfo(name = "deletion_scope_identity") val deletionScopeIdentity: String,
	@ColumnInfo(name = "structural_epoch_day") val structuralEpochDay: Long,
	@ColumnInfo(name = "stored_zone_id") val storedZoneId: String,
	@ColumnInfo(name = "structural_day_start_time_ms") val structuralDayStartTimeMs: Long,
	@ColumnInfo(name = "structural_day_end_time_ms") val structuralDayEndTimeMs: Long,
	@ColumnInfo(name = "retained_from_time_ms") val retainedFromTimeMs: Long?,
	@ColumnInfo(name = "coverage") val coverage: String,
	@ColumnInfo(name = "partial_causes") val partialCauses: String,
	@ColumnInfo(name = "retained_step_count") val retainedStepCount: Long,
	@ColumnInfo(name = "fact_count") val factCount: Int,
	@ColumnInfo(name = "gap_count") val gapCount: Int,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
) {
	init {
		listOf(dayIdentity, archiveIdentity, dayContentChecksum, deletionScopeIdentity).forEach {
			require(ImportedAmbientStepsIdentity.isDigest(it))
		}
		require(importRevision > 0L)
		require(
			(importRevision == 1L && supersedesImportRevision == null) ||
				(importRevision > 1L && supersedesImportRevision == importRevision - 1L),
		)
		require(
			ImportedAmbientStepsIdentity.deletionScope(dayIdentity) == deletionScopeIdentity,
		)
		require(storedZoneId.isNotBlank())
		require(storedZoneId.length <= AmbientStepsPortableFormatV1.MAX_ZONE_ID_LENGTH)
		val zone = ZoneId.of(storedZoneId)
		val date = LocalDate.ofEpochDay(structuralEpochDay)
		require(date.atStartOfDay(zone).toInstant().toEpochMilli() == structuralDayStartTimeMs)
		require(date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli() ==
			structuralDayEndTimeMs)
		require(retainedFromTimeMs == null ||
			retainedFromTimeMs in (structuralDayStartTimeMs + 1L) until structuralDayEndTimeMs)
		require(coverage in PortableAmbientStepsCoverage.entries.map { it.name })
		val decodedCauses = ImportedAmbientStepsIdentity.decodePartialCauses(partialCauses)
		require((coverage == PortableAmbientStepsCoverage.COMPLETE.name) == decodedCauses.isEmpty())
		require(
			(retainedFromTimeMs != null) ==
				(PortableAmbientStepsPartialCause.RETENTION in decodedCauses),
		)
		require(retainedStepCount >= 0L)
		require(factCount in 1..AmbientStepsPortableFormatV1.MAX_FACTS_PER_DAY)
		require(gapCount in 0..AmbientStepsPortableFormatV1.MAX_GAPS_PER_DAY)
		require(collectedDataEpoch >= 0L && receivedAtMs >= 0L)
	}
}

/** Exact portable fact owned only by one day correction revision. */
@Entity(
	tableName = "imported_ambient_steps_fact",
	primaryKeys = ["day_identity", "day_import_revision", "fact_identity"],
	foreignKeys = [ForeignKey(
		entity = ImportedAmbientStepsDayRevisionEntity::class,
		parentColumns = ["day_identity", "import_revision"],
		childColumns = ["day_identity", "day_import_revision"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["day_identity", "day_import_revision"],
			name = "idx_imported_ambient_steps_fact_day",
		),
		Index(value = ["fact_identity"], name = "idx_imported_ambient_steps_fact_identity"),
		Index(
			value = ["interval_start_time_ms", "interval_end_time_ms"],
			name = "idx_imported_ambient_steps_fact_window",
		),
	],
)
data class ImportedAmbientStepsFactEntity(
	@ColumnInfo(name = "day_identity") val dayIdentity: String,
	@ColumnInfo(name = "day_import_revision") val dayImportRevision: Long,
	@ColumnInfo(name = "fact_identity") val factIdentity: String,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "interval_start_time_ms") val intervalStartTimeMs: Long,
	@ColumnInfo(name = "interval_end_time_ms") val intervalEndTimeMs: Long,
	@ColumnInfo(name = "step_count") val stepCount: Long,
) {
	init {
		listOf(dayIdentity, factIdentity, contentChecksum).forEach {
			require(ImportedAmbientStepsIdentity.isDigest(it))
		}
		require(dayImportRevision > 0L)
		require(intervalStartTimeMs >= 0L && intervalEndTimeMs > intervalStartTimeMs)
		require(stepCount >= 0L)
	}
}

/** Exact portable gap provenance; no local cursor or provider authority is invented. */
@Entity(
	tableName = "imported_ambient_steps_gap",
	primaryKeys = ["day_identity", "day_import_revision", "gap_identity"],
	foreignKeys = [ForeignKey(
		entity = ImportedAmbientStepsDayRevisionEntity::class,
		parentColumns = ["day_identity", "import_revision"],
		childColumns = ["day_identity", "day_import_revision"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["day_identity", "day_import_revision"],
			name = "idx_imported_ambient_steps_gap_day",
		),
		Index(value = ["gap_identity"], name = "idx_imported_ambient_steps_gap_identity"),
		Index(
			value = ["interval_start_time_ms", "interval_end_time_ms"],
			name = "idx_imported_ambient_steps_gap_window",
		),
	],
)
data class ImportedAmbientStepsGapEntity(
	@ColumnInfo(name = "day_identity") val dayIdentity: String,
	@ColumnInfo(name = "day_import_revision") val dayImportRevision: Long,
	@ColumnInfo(name = "gap_identity") val gapIdentity: String,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "interval_start_time_ms") val intervalStartTimeMs: Long,
	@ColumnInfo(name = "interval_end_time_ms") val intervalEndTimeMs: Long,
	@ColumnInfo(name = "reason") val reason: String,
) {
	init {
		listOf(dayIdentity, gapIdentity, contentChecksum).forEach {
			require(ImportedAmbientStepsIdentity.isDigest(it))
		}
		require(dayImportRevision > 0L)
		require(intervalStartTimeMs >= 0L && intervalEndTimeMs > intervalStartTimeMs)
		require(reason in PortableAmbientStepsGapReason.entries.map { it.name })
	}
}

/** Payload-free source-local deletion or retention authority for one portable day lineage. */
@Entity(
	tableName = "imported_ambient_steps_day_fence",
	primaryKeys = ["day_identity"],
	indices = [
		Index(
			value = ["structural_day_start_time_ms", "structural_day_end_time_ms"],
			name = "idx_imported_ambient_steps_fence_window",
		),
		Index(
			value = ["deletion_scope_identity"],
			unique = true,
			name = "idx_imported_ambient_steps_fence_scope",
		),
	],
)
@Suppress("LongParameterList")
data class ImportedAmbientStepsDayFenceEntity(
	@ColumnInfo(name = "day_identity") val dayIdentity: String,
	@ColumnInfo(name = "deletion_scope_identity") val deletionScopeIdentity: String,
	@ColumnInfo(name = "fence_kind") val fenceKind: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "source_evidence_revision") val sourceEvidenceRevision: Long,
	@ColumnInfo(name = "fenced_at_ms") val fencedAtMs: Long,
	@ColumnInfo(name = "retained_from_ms") val retainedFromMs: Long?,
	@ColumnInfo(name = "latest_import_revision") val latestImportRevision: Long,
	@ColumnInfo(name = "latest_content_checksum") val latestContentChecksum: String,
	@ColumnInfo(name = "structural_epoch_day") val structuralEpochDay: Long,
	@ColumnInfo(name = "stored_zone_id") val storedZoneId: String,
	@ColumnInfo(name = "structural_day_start_time_ms") val structuralDayStartTimeMs: Long,
	@ColumnInfo(name = "structural_day_end_time_ms") val structuralDayEndTimeMs: Long,
	@ColumnInfo(name = "revision_count") val revisionCount: Int,
	@ColumnInfo(name = "archive_count") val archiveCount: Int,
	@ColumnInfo(name = "fact_row_count") val factRowCount: Int,
	@ColumnInfo(name = "gap_row_count") val gapRowCount: Int,
	@ColumnInfo(name = "protected_identity_count") val protectedIdentityCount: Int,
	@ColumnInfo(name = "protected_identity_set_checksum") val protectedIdentitySetChecksum: String,
	@ColumnInfo(name = "lineage_checksum") val lineageChecksum: String,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		listOf(
			dayIdentity,
			deletionScopeIdentity,
			latestContentChecksum,
			protectedIdentitySetChecksum,
			lineageChecksum,
			effectChecksum,
		).forEach { require(ImportedAmbientStepsIdentity.isDigest(it)) }
		require(ImportedAmbientStepsIdentity.deletionScope(dayIdentity) == deletionScopeIdentity)
		require(fenceKind in FENCE_KINDS)
		require(collectedDataEpoch >= 0L && sourceEvidenceRevision >= 0L && fencedAtMs >= 0L)
		require((fenceKind == FENCE_RETENTION) == (retainedFromMs != null))
		require(retainedFromMs == null || retainedFromMs >= 0L)
		require(latestImportRevision > 0L)
		require(storedZoneId.isNotBlank())
		ZoneId.of(storedZoneId)
		require(structuralDayStartTimeMs >= 0L &&
			structuralDayEndTimeMs > structuralDayStartTimeMs)
		require(revisionCount in 1..MAX_REVISIONS)
		require(latestImportRevision == revisionCount.toLong())
		require(archiveCount in 1..MAX_ARCHIVES)
		require(factRowCount in revisionCount..MAX_FACT_ROWS)
		require(gapRowCount in 0..MAX_GAP_ROWS)
		require(protectedIdentityCount in 3..MAX_PROTECTED_IDENTITIES)
		require(effectChecksum == checksum(this))
	}

	companion object {
		const val FENCE_SELECTED_DELETE = "SELECTED_DELETE"
		const val FENCE_CONSENT_REVOKED = "CONSENT_REVOKED"
		const val FENCE_RETENTION = "RETENTION"

		private val FENCE_KINDS = setOf(
			FENCE_SELECTED_DELETE,
			FENCE_CONSENT_REVOKED,
			FENCE_RETENTION,
		)
		private const val MAX_REVISIONS = 16
		private const val MAX_ARCHIVES = 256
		private const val MAX_FACT_ROWS =
			MAX_REVISIONS * AmbientStepsPortableFormatV1.MAX_FACTS_PER_DAY
		private const val MAX_GAP_ROWS =
			MAX_REVISIONS * AmbientStepsPortableFormatV1.MAX_GAPS_PER_DAY
		private const val MAX_RECEIPTS = 65_536
		private const val MAX_PROTECTED_IDENTITIES =
			2 + MAX_ARCHIVES + MAX_FACT_ROWS + MAX_GAP_ROWS + MAX_RECEIPTS

		fun create(
			dayIdentity: String,
			deletionScopeIdentity: String,
			fenceKind: String,
			collectedDataEpoch: Long,
			sourceEvidenceRevision: Long,
			fencedAtMs: Long,
			retainedFromMs: Long?,
			latestImportRevision: Long,
			latestContentChecksum: String,
			structuralEpochDay: Long,
			storedZoneId: String,
			structuralDayStartTimeMs: Long,
			structuralDayEndTimeMs: Long,
			revisionCount: Int,
			archiveCount: Int,
			factRowCount: Int,
			gapRowCount: Int,
			protectedIdentities: List<ImportedAmbientStepsProtectedIdentityEntity>,
			lineageChecksum: String,
		): ImportedAmbientStepsDayFenceEntity {
			val protectedChecksum =
				ImportedAmbientStepsIdentity.protectedIdentitySetChecksum(protectedIdentities)
			val effectChecksum = ImportedAmbientStepsIdentity.digest(
				"tracker-imported-ambient-steps-day-fence-v1",
				listOf(
					dayIdentity,
					deletionScopeIdentity,
					fenceKind,
					collectedDataEpoch,
					sourceEvidenceRevision,
					fencedAtMs,
					retainedFromMs,
					latestImportRevision,
					latestContentChecksum,
					structuralEpochDay,
					storedZoneId,
					structuralDayStartTimeMs,
					structuralDayEndTimeMs,
					revisionCount,
					archiveCount,
					factRowCount,
					gapRowCount,
					protectedIdentities.size,
					protectedChecksum,
					lineageChecksum,
				),
			)
			return ImportedAmbientStepsDayFenceEntity(
				dayIdentity,
				deletionScopeIdentity,
				fenceKind,
				collectedDataEpoch,
				sourceEvidenceRevision,
				fencedAtMs,
				retainedFromMs,
				latestImportRevision,
				latestContentChecksum,
				structuralEpochDay,
				storedZoneId,
				structuralDayStartTimeMs,
				structuralDayEndTimeMs,
				revisionCount,
				archiveCount,
				factRowCount,
				gapRowCount,
				protectedIdentities.size,
				protectedChecksum,
				lineageChecksum,
				effectChecksum,
			)
		}

		private fun checksum(value: ImportedAmbientStepsDayFenceEntity) =
			ImportedAmbientStepsIdentity.digest(
				"tracker-imported-ambient-steps-day-fence-v1",
				listOf(
					value.dayIdentity,
					value.deletionScopeIdentity,
					value.fenceKind,
					value.collectedDataEpoch,
					value.sourceEvidenceRevision,
					value.fencedAtMs,
					value.retainedFromMs,
					value.latestImportRevision,
					value.latestContentChecksum,
					value.structuralEpochDay,
					value.storedZoneId,
					value.structuralDayStartTimeMs,
					value.structuralDayEndTimeMs,
					value.revisionCount,
					value.archiveCount,
					value.factRowCount,
					value.gapRowCount,
					value.protectedIdentityCount,
					value.protectedIdentitySetChecksum,
					value.lineageChecksum,
				),
			)
	}
}

/** Kind-qualified owner markers retained after source-local payload removal. */
@Entity(
	tableName = "imported_ambient_steps_protected_identity",
	primaryKeys = ["protected_identity", "owner_day_identity"],
	indices = [Index(
		value = ["owner_day_identity"],
		name = "idx_imported_ambient_steps_protected_owner",
	)],
)
data class ImportedAmbientStepsProtectedIdentityEntity(
	@ColumnInfo(name = "protected_identity") val protectedIdentity: String,
	@ColumnInfo(name = "owner_day_identity") val ownerDayIdentity: String,
	@ColumnInfo(name = "identity_kind") val identityKind: String,
) {
	init {
		require(ImportedAmbientStepsIdentity.isDigest(protectedIdentity))
		require(ImportedAmbientStepsIdentity.isDigest(ownerDayIdentity))
		require(identityKind in IDENTITY_KINDS)
	}

	companion object {
		const val ARCHIVE = "ARCHIVE"
		const val DAY = "DAY"
		const val FACT = "FACT"
		const val GAP = "GAP"
		const val DELETION_SCOPE = "DELETION_SCOPE"
		const val RECEIPT = "RECEIPT"

		private val IDENTITY_KINDS = setOf(ARCHIVE, DAY, FACT, GAP, DELETION_SCOPE, RECEIPT)
	}
}

/** Source-wide imported-origin deletion floor installed before consent-reset payload removal. */
@Entity(tableName = "imported_ambient_steps_source_fence")
data class ImportedAmbientStepsSourceFenceEntity(
	@androidx.room.PrimaryKey val id: Int = SINGLETON_ID,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "revoked_consent_epoch") val revokedConsentEpoch: Long,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(id == SINGLETON_ID)
		require(collectedDataEpoch >= 0L && revokedConsentEpoch >= 0L && deletedAtMs >= 0L)
		require(ImportedAmbientStepsIdentity.isDigest(effectChecksum))
		require(effectChecksum == checksum(collectedDataEpoch, revokedConsentEpoch, deletedAtMs))
	}

	companion object {
		const val SINGLETON_ID = 1

		fun create(
			collectedDataEpoch: Long,
			revokedConsentEpoch: Long,
			deletedAtMs: Long,
		) = ImportedAmbientStepsSourceFenceEntity(
			collectedDataEpoch = collectedDataEpoch,
			revokedConsentEpoch = revokedConsentEpoch,
			deletedAtMs = deletedAtMs,
			effectChecksum = checksum(collectedDataEpoch, revokedConsentEpoch, deletedAtMs),
		)

		private fun checksum(
			collectedDataEpoch: Long,
			revokedConsentEpoch: Long,
			deletedAtMs: Long,
		) = ImportedAmbientStepsIdentity.digest(
			"tracker-imported-ambient-steps-source-fence-v1",
			listOf(collectedDataEpoch, revokedConsentEpoch, deletedAtMs),
		)
	}
}

object ImportedAmbientStepsIdentity {
	private val DIGEST = Regex("sha256:[0-9a-f]{64}")

	fun isDigest(value: String): Boolean = DIGEST.matches(value)

	fun requireReceipt(jobId: String, archiveKey: String, sourceName: String) {
		listOf(jobId, archiveKey, sourceName).forEach { value ->
			require(value.isNotBlank())
			require(value.length <= AmbientStepsPortableFormatV1.MAX_IMPORT_RECEIPT_FIELD_LENGTH)
		}
	}

	fun deletionScope(dayIdentity: String): String =
		AmbientStepsPortableOpaqueIdentity.derive(
			AmbientStepsPortableIdentityKind.DELETION_SCOPE,
			dayIdentity,
		).value

	fun receipt(importJobId: String, archiveKey: String): String {
		requireReceipt(importJobId, archiveKey, "receipt")
		return digest(
			"tracker-imported-ambient-steps-receipt-identity-v1",
			listOf(importJobId, archiveKey),
		)
	}

	fun encodePartialCauses(causes: List<PortableAmbientStepsPartialCause>): String =
		causes.distinct().sortedBy { it.ordinal }.joinToString(",") { it.name }

	fun decodePartialCauses(value: String): List<PortableAmbientStepsPartialCause> {
		if (value.isEmpty()) return emptyList()
		val decoded = value.split(',').map(PortableAmbientStepsPartialCause::valueOf)
		require(decoded == decoded.distinct().sortedBy { it.ordinal })
		return decoded
	}

	fun protectedIdentitySetChecksum(
		values: List<ImportedAmbientStepsProtectedIdentityEntity>,
	): String = digest(
		"tracker-imported-ambient-steps-protected-identity-set-v1",
		values.sortedWith(
			compareBy(
				ImportedAmbientStepsProtectedIdentityEntity::protectedIdentity,
				ImportedAmbientStepsProtectedIdentityEntity::ownerDayIdentity,
				ImportedAmbientStepsProtectedIdentityEntity::identityKind,
			),
		).map { listOf(it.protectedIdentity, it.ownerDayIdentity, it.identityKind) },
	)

	fun digest(namespace: String, values: List<Any?>): String {
		val digest = MessageDigest.getInstance("SHA-256")
		fun append(value: Any?) {
			when (value) {
				null -> digest.update("N;".toByteArray(Charsets.UTF_8))
				is Int -> append(value.toLong())
				is Long -> digest.update("I$value;".toByteArray(Charsets.UTF_8))
				is String -> {
					val bytes = value.toByteArray(Charsets.UTF_8)
					digest.update("S${bytes.size}:".toByteArray(Charsets.UTF_8))
					digest.update(bytes)
					digest.update(";".toByteArray(Charsets.UTF_8))
				}
				is Collection<*> -> {
					digest.update("L${value.size}[".toByteArray(Charsets.UTF_8))
					value.forEach(::append)
					digest.update("];".toByteArray(Charsets.UTF_8))
				}
				else -> error("Unsupported imported Ambient Steps checksum value")
			}
		}
		append(listOf(namespace, values))
		return "sha256:" + digest.digest().joinToString("") { byte ->
			(byte.toInt() and 0xff).toString(16).padStart(2, '0')
		}
	}
}
