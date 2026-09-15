package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveDayEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsProtectedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsSourceFenceEntity
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1

/** Source-specific imported Ambient Steps primitives. Admission owns transaction semantics. */
@Dao
abstract class ImportedAmbientStepsDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertArchive(value: ImportedAmbientStepsArchiveEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertReceipt(value: ImportedAmbientStepsReceiptEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertArchiveDays(values: List<ImportedAmbientStepsArchiveDayEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertDayRevision(value: ImportedAmbientStepsDayRevisionEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertFacts(values: List<ImportedAmbientStepsFactEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertGaps(values: List<ImportedAmbientStepsGapEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertFence(value: ImportedAmbientStepsDayFenceEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertProtectedIdentities(
		values: List<ImportedAmbientStepsProtectedIdentityEntity>,
	)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertSourceFence(value: ImportedAmbientStepsSourceFenceEntity)

	@Query("SELECT * FROM imported_ambient_steps_source_fence WHERE id = 1")
	abstract suspend fun sourceFence(): ImportedAmbientStepsSourceFenceEntity?

	@Query(
		"UPDATE imported_ambient_steps_source_fence SET " +
			"revoked_consent_epoch = :newRevokedConsentEpoch, deleted_at_ms = :deletedAtMs, " +
			"effect_checksum = :effectChecksum WHERE id = 1 " +
			"AND collected_data_epoch = :expectedCollectedDataEpoch " +
			"AND revoked_consent_epoch = :expectedRevokedConsentEpoch " +
			"AND :newRevokedConsentEpoch > revoked_consent_epoch",
	)
	abstract suspend fun advanceSourceFence(
		expectedCollectedDataEpoch: Long,
		expectedRevokedConsentEpoch: Long,
		newRevokedConsentEpoch: Long,
		deletedAtMs: Long,
		effectChecksum: String,
	): Int

	@Query(
		"SELECT * FROM imported_ambient_steps_archive WHERE archive_identity = :archiveIdentity",
	)
	abstract suspend fun archive(archiveIdentity: String): ImportedAmbientStepsArchiveEntity?

	@Query(
		"SELECT * FROM imported_ambient_steps_archive " +
			"WHERE archive_identity IN (:archiveIdentities) ORDER BY archive_identity LIMIT :limit",
	)
	abstract suspend fun archives(
		archiveIdentities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsArchiveEntity>

	@Query(
		"SELECT * FROM imported_ambient_steps_receipt " +
			"WHERE import_job_id = :jobId AND archive_key = :archiveKey",
	)
	abstract suspend fun receipt(
		jobId: String,
		archiveKey: String,
	): ImportedAmbientStepsReceiptEntity?

	@Query(
		"SELECT * FROM imported_ambient_steps_receipt WHERE receipt_identity = :receiptIdentity",
	)
	abstract suspend fun receiptByIdentity(
		receiptIdentity: String,
	): ImportedAmbientStepsReceiptEntity?

	@Query(
		"SELECT * FROM imported_ambient_steps_receipt " +
			"WHERE archive_identity IN (:archiveIdentities) " +
			"ORDER BY archive_identity, import_job_id, archive_key LIMIT :limit",
	)
	abstract suspend fun receiptsForArchives(
		archiveIdentities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsReceiptEntity>

	@Query(
		"SELECT * FROM imported_ambient_steps_archive_day " +
			"WHERE archive_identity = :archiveIdentity ORDER BY ordinal",
	)
	abstract suspend fun archiveDays(
		archiveIdentity: String,
	): List<ImportedAmbientStepsArchiveDayEntity>

	@Query(
		"SELECT * FROM imported_ambient_steps_archive_day " +
			"WHERE archive_identity IN (:archiveIdentities) " +
			"ORDER BY archive_identity, ordinal LIMIT :limit",
	)
	abstract suspend fun archiveDays(
		archiveIdentities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsArchiveDayEntity>

	@Query(
		"SELECT * FROM imported_ambient_steps_archive_day WHERE day_identity = :dayIdentity " +
			"ORDER BY archive_identity LIMIT :limit",
	)
	abstract suspend fun archiveDaysForDay(
		dayIdentity: String,
		limit: Int,
	): List<ImportedAmbientStepsArchiveDayEntity>

	suspend fun dayRevisionsForAdmission(
		dayIdentity: String,
	): List<ImportedAmbientStepsDayRevisionEntity> =
		loadDayRevisions(dayIdentity, MAX_REVISIONS_PER_DAY + 1)

	@Query(
		"SELECT * FROM imported_ambient_steps_day_revision WHERE day_identity = :dayIdentity " +
			"ORDER BY import_revision LIMIT :limit",
	)
	protected abstract suspend fun loadDayRevisions(
		dayIdentity: String,
		limit: Int,
	): List<ImportedAmbientStepsDayRevisionEntity>

	suspend fun factsForAdmission(dayIdentity: String): List<ImportedAmbientStepsFactEntity> =
		loadFacts(dayIdentity, MAX_TOTAL_FACT_ROWS_PER_LINEAGE + 1)

	@Query(
		"SELECT * FROM imported_ambient_steps_fact WHERE day_identity = :dayIdentity " +
			"ORDER BY day_import_revision, interval_start_time_ms, fact_identity LIMIT :limit",
	)
	protected abstract suspend fun loadFacts(
		dayIdentity: String,
		limit: Int,
	): List<ImportedAmbientStepsFactEntity>

	suspend fun gapsForAdmission(dayIdentity: String): List<ImportedAmbientStepsGapEntity> =
		loadGaps(dayIdentity, MAX_TOTAL_GAP_ROWS_PER_LINEAGE + 1)

	@Query(
		"SELECT * FROM imported_ambient_steps_gap WHERE day_identity = :dayIdentity " +
			"ORDER BY day_import_revision, interval_start_time_ms, gap_identity LIMIT :limit",
	)
	protected abstract suspend fun loadGaps(
		dayIdentity: String,
		limit: Int,
	): List<ImportedAmbientStepsGapEntity>

	@Query(
		"""
		WITH latest_revision AS (
		  SELECT day_identity, MAX(import_revision) AS import_revision
		  FROM imported_ambient_steps_day_revision
		  GROUP BY day_identity
		)
		SELECT day.*
		FROM imported_ambient_steps_day_revision AS day
		INNER JOIN latest_revision AS latest
		  ON latest.day_identity = day.day_identity
		 AND latest.import_revision = day.import_revision
		WHERE day.structural_day_start_time_ms < :toExclusiveMs
		  AND day.structural_day_end_time_ms > :fromInclusiveMs
		ORDER BY day.structural_day_start_time_ms, day.day_identity
		LIMIT :limit
		""",
	)
	abstract suspend fun latestDaysOverlapping(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
	): List<ImportedAmbientStepsDayRevisionEntity>

	/** Global bounded structural-owner audit used to prevent parallel identities for one civil day. */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT day_identity, MAX(import_revision) AS import_revision
		  FROM imported_ambient_steps_day_revision
		  GROUP BY day_identity
		)
		SELECT day.*
		FROM imported_ambient_steps_day_revision AS day
		INNER JOIN latest_revision AS latest
		  ON latest.day_identity = day.day_identity
		 AND latest.import_revision = day.import_revision
		ORDER BY day.structural_day_start_time_ms, day.day_identity
		LIMIT :limit
		""",
	)
	abstract suspend fun allLatestDayOwners(
		limit: Int,
	): List<ImportedAmbientStepsDayRevisionEntity>

	/** Earliest payload lineage affected by the current exact retained-data floor. */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT day_identity, MAX(import_revision) AS import_revision
		  FROM imported_ambient_steps_day_revision
		  GROUP BY day_identity
		)
		SELECT day.*
		FROM imported_ambient_steps_day_revision AS day
		INNER JOIN latest_revision AS latest
		  ON latest.day_identity = day.day_identity
		 AND latest.import_revision = day.import_revision
		WHERE day.structural_day_start_time_ms < :retainedFromMs
		  AND (
		    day.retained_from_time_ms IS NULL
		    OR day.retained_from_time_ms < :retainedFromMs
		  )
		ORDER BY day.structural_day_start_time_ms, day.day_identity
		LIMIT 1
		""",
	)
	abstract suspend fun nextRetentionCandidate(
		retainedFromMs: Long,
	): ImportedAmbientStepsDayRevisionEntity?

	@Query(
		"""
		WITH latest_revision AS (
		  SELECT day_identity, MAX(import_revision) AS import_revision
		  FROM imported_ambient_steps_day_revision
		  GROUP BY day_identity
		)
		SELECT day.*
		FROM imported_ambient_steps_day_revision AS day
		INNER JOIN latest_revision AS latest
		  ON latest.day_identity = day.day_identity
		 AND latest.import_revision = day.import_revision
		ORDER BY day.structural_day_start_time_ms, day.day_identity
		LIMIT 1
		""",
	)
	abstract suspend fun nextDayCandidate(): ImportedAmbientStepsDayRevisionEntity?

	@Query(
		"SELECT * FROM imported_ambient_steps_day_fence WHERE day_identity = :dayIdentity",
	)
	abstract suspend fun fence(dayIdentity: String): ImportedAmbientStepsDayFenceEntity?

	@Query(
		"SELECT * FROM imported_ambient_steps_day_fence " +
			"WHERE day_identity IN (:dayIdentities) ORDER BY day_identity",
	)
	abstract suspend fun fences(
		dayIdentities: List<String>,
	): List<ImportedAmbientStepsDayFenceEntity>

	@Query(
		"SELECT * FROM imported_ambient_steps_day_fence " +
			"WHERE structural_day_start_time_ms < :toExclusiveMs " +
			"AND structural_day_end_time_ms > :fromInclusiveMs " +
			"ORDER BY structural_day_start_time_ms, day_identity LIMIT :limit",
	)
	abstract suspend fun fencesOverlapping(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
	): List<ImportedAmbientStepsDayFenceEntity>

	@Query(
		"SELECT * FROM imported_ambient_steps_day_fence ORDER BY day_identity LIMIT :limit",
	)
	abstract suspend fun allFences(limit: Int): List<ImportedAmbientStepsDayFenceEntity>

	@Query(
		"SELECT * FROM imported_ambient_steps_protected_identity " +
			"WHERE owner_day_identity = :dayIdentity " +
			"ORDER BY protected_identity, identity_kind LIMIT :limit",
	)
	abstract suspend fun protectedIdentitiesForDay(
		dayIdentity: String,
		limit: Int,
	): List<ImportedAmbientStepsProtectedIdentityEntity>

	@Query(
		"SELECT * FROM imported_ambient_steps_protected_identity " +
			"WHERE protected_identity IN (:identities) " +
			"ORDER BY protected_identity, owner_day_identity LIMIT :limit",
	)
	abstract suspend fun protectedIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsProtectedIdentityEntity>

	@Query(
		"SELECT * FROM imported_ambient_steps_protected_identity WHERE " +
			":afterOwnerDayIdentity IS NULL OR owner_day_identity > :afterOwnerDayIdentity OR " +
			"(owner_day_identity = :afterOwnerDayIdentity AND " +
			"protected_identity > COALESCE(:afterProtectedIdentity, '')) " +
			"ORDER BY owner_day_identity, protected_identity LIMIT :limit",
	)
	abstract suspend fun protectedIdentityPage(
		afterOwnerDayIdentity: String?,
		afterProtectedIdentity: String?,
		limit: Int,
	): List<ImportedAmbientStepsProtectedIdentityEntity>

	@Query(
		"SELECT archive_identity FROM imported_ambient_steps_archive " +
			"WHERE archive_identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingArchiveIdentities(
		identities: List<String>,
		limit: Int,
	): List<String>

	@Query(
		"SELECT receipt_identity FROM imported_ambient_steps_receipt " +
			"WHERE receipt_identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingReceiptIdentities(
		identities: List<String>,
		limit: Int,
	): List<String>

	@Query(
		"SELECT DISTINCT day_identity, deletion_scope_identity " +
			"FROM imported_ambient_steps_day_revision " +
			"WHERE day_identity IN (:identities) OR deletion_scope_identity IN (:identities) " +
			"LIMIT :limit",
	)
	abstract suspend fun existingDayIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsDayIdentityOwner>

	@Query(
		"SELECT DISTINCT fact_identity, day_identity FROM imported_ambient_steps_fact " +
			"WHERE fact_identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingFactIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsFactIdentityOwner>

	@Query(
		"SELECT DISTINCT gap_identity, day_identity FROM imported_ambient_steps_gap " +
			"WHERE gap_identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingGapIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsGapIdentityOwner>

	@Query(
		"DELETE FROM imported_ambient_steps_day_revision WHERE day_identity = :dayIdentity",
	)
	abstract suspend fun deleteDayLineage(dayIdentity: String): Int

	/** Receipts cascade only after no retained day revision references the archive. */
	@Query(
		"DELETE FROM imported_ambient_steps_archive WHERE NOT EXISTS (" +
			"SELECT 1 FROM imported_ambient_steps_archive_day AS member " +
			"JOIN imported_ambient_steps_day_revision AS day " +
			"ON day.day_identity = member.day_identity " +
			"WHERE member.archive_identity = imported_ambient_steps_archive.archive_identity)",
	)
	abstract suspend fun deleteOrphanArchives(): Int

	@Query("SELECT COUNT(*) FROM imported_ambient_steps_archive")
	abstract suspend fun archiveCount(): Long

	@Query("SELECT COUNT(*) FROM imported_ambient_steps_receipt")
	abstract suspend fun receiptCount(): Long

	@Query("SELECT COUNT(*) FROM imported_ambient_steps_archive_day")
	abstract suspend fun archiveDayCount(): Long

	@Query("SELECT COUNT(*) FROM imported_ambient_steps_day_revision")
	abstract suspend fun dayRevisionCount(): Long

	@Query("SELECT COUNT(*) FROM imported_ambient_steps_fact")
	abstract suspend fun factCount(): Long

	@Query("SELECT COUNT(*) FROM imported_ambient_steps_gap")
	abstract suspend fun gapCount(): Long

	@Query("SELECT COUNT(*) FROM imported_ambient_steps_day_fence")
	abstract suspend fun fenceCount(): Long

	@Query("SELECT COUNT(*) FROM imported_ambient_steps_protected_identity")
	abstract suspend fun protectedIdentityCount(): Long

	@Query("DELETE FROM imported_ambient_steps_day_revision")
	abstract fun deleteAllDays()

	@Query("DELETE FROM imported_ambient_steps_receipt")
	abstract fun deleteAllReceipts()

	@Query("DELETE FROM imported_ambient_steps_archive")
	abstract fun deleteAllArchives()

	@Query("DELETE FROM imported_ambient_steps_protected_identity")
	abstract fun deleteAllProtectedIdentities()

	@Query("DELETE FROM imported_ambient_steps_day_fence")
	abstract fun deleteAllFences()

	@Query("DELETE FROM imported_ambient_steps_source_fence")
	abstract fun deleteSourceFence()

	companion object {
		const val MAX_REVISIONS_PER_DAY = 16
		const val MAX_RECEIPTS_PER_ARCHIVE = 256
		const val MAX_ARCHIVES_PER_DAY = 256
		const val MAX_ARCHIVE_MEMBERS_PER_LINEAGE = 65_536
		const val MAX_RECEIPTS_PER_LINEAGE =
			MAX_ARCHIVES_PER_DAY * MAX_RECEIPTS_PER_ARCHIVE
		const val MAX_TOTAL_FACT_ROWS_PER_LINEAGE =
			MAX_REVISIONS_PER_DAY * AmbientStepsPortableFormatV1.MAX_FACTS_PER_DAY
		const val MAX_TOTAL_GAP_ROWS_PER_LINEAGE =
			MAX_REVISIONS_PER_DAY * AmbientStepsPortableFormatV1.MAX_GAPS_PER_DAY
		const val MAX_PROTECTED_IDENTITIES_PER_DAY =
			2 + MAX_ARCHIVES_PER_DAY + MAX_TOTAL_FACT_ROWS_PER_LINEAGE +
				MAX_TOTAL_GAP_ROWS_PER_LINEAGE + MAX_RECEIPTS_PER_LINEAGE
		const val MAX_GLOBAL_ARCHIVES = 16_384L
		const val MAX_GLOBAL_RECEIPTS = 65_536L
		const val MAX_GLOBAL_ARCHIVE_DAYS = 262_144L
		const val MAX_GLOBAL_DAY_REVISIONS = 65_536L
		const val MAX_GLOBAL_FACTS = 1_048_576L
		const val MAX_GLOBAL_GAPS = 262_144L
		const val MAX_GLOBAL_FENCES = 65_536L
		const val MAX_GLOBAL_PROTECTED_IDENTITIES = 1_376_256L
		const val FENCE_AUDIT_PAGE_SIZE = 512
	}
}

data class ImportedAmbientStepsDayIdentityOwner(
	@ColumnInfo(name = "day_identity") val dayIdentity: String,
	@ColumnInfo(name = "deletion_scope_identity") val deletionScopeIdentity: String,
)

data class ImportedAmbientStepsFactIdentityOwner(
	@ColumnInfo(name = "fact_identity") val factIdentity: String,
	@ColumnInfo(name = "day_identity") val dayIdentity: String,
)

data class ImportedAmbientStepsGapIdentityOwner(
	@ColumnInfo(name = "gap_identity") val gapIdentity: String,
	@ColumnInfo(name = "day_identity") val dayIdentity: String,
)
