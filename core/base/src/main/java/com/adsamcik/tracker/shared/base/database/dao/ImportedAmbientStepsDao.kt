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
// Keep the ImportedAmbientStepsDao SQL contract together; query text accounts for this declaration's size.
@Suppress("LargeClass")
@Dao
abstract class ImportedAmbientStepsDao {
	/**
	 * Inserts [ImportedAmbientStepsArchiveEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertArchive(value: ImportedAmbientStepsArchiveEntity)

	/**
	 * Inserts [ImportedAmbientStepsReceiptEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertReceipt(value: ImportedAmbientStepsReceiptEntity)

	/**
	 * Inserts [ImportedAmbientStepsArchiveDayEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertArchiveDays(values: List<ImportedAmbientStepsArchiveDayEntity>)

	/**
	 * Inserts [ImportedAmbientStepsDayRevisionEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertDayRevision(value: ImportedAmbientStepsDayRevisionEntity)

	/**
	 * Inserts [ImportedAmbientStepsFactEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertFacts(values: List<ImportedAmbientStepsFactEntity>)

	/**
	 * Inserts [ImportedAmbientStepsGapEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertGaps(values: List<ImportedAmbientStepsGapEntity>)

	/**
	 * Inserts [ImportedAmbientStepsDayFenceEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertFence(value: ImportedAmbientStepsDayFenceEntity)

	/**
	 * Inserts [ImportedAmbientStepsDayFenceEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract fun insertFenceForFullClear(value: ImportedAmbientStepsDayFenceEntity)

	/**
	 * Inserts [ImportedAmbientStepsProtectedIdentityEntity] rows. A conflicting key aborts the
	 * insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertProtectedIdentities(
		values: List<ImportedAmbientStepsProtectedIdentityEntity>,
	)

	/**
	 * Inserts [ImportedAmbientStepsProtectedIdentityEntity] rows. A conflicting key aborts the
	 * insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract fun insertProtectedIdentitiesForFullClear(
		values: List<ImportedAmbientStepsProtectedIdentityEntity>,
	)

	/**
	 * Inserts [ImportedAmbientStepsSourceFenceEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertSourceFence(value: ImportedAmbientStepsSourceFenceEntity)

	/**
	 * Reads `imported_ambient_steps_source_fence` rows. Query conditions include `id = 1`. Returns
	 * null when no row matches.
	 */
	@Query("SELECT * FROM imported_ambient_steps_source_fence WHERE id = 1")
	abstract suspend fun sourceFence(): ImportedAmbientStepsSourceFenceEntity?

	/**
	 * Reads `imported_ambient_steps_source_fence` rows. Query conditions include `id = 1`. Returns
	 * null when no row matches.
	 */
	@Query("SELECT * FROM imported_ambient_steps_source_fence WHERE id = 1")
	abstract fun sourceFenceForFullClear(): ImportedAmbientStepsSourceFenceEntity?

	/**
	 * Updates `imported_ambient_steps_source_fence` rows matching the supplied scope. Query
	 * conditions include `id = 1`, `collected_data_epoch = :expectedCollectedDataEpoch`,
	 * `revoked_consent_epoch = :expectedRevokedConsentEpoch`, `effect_checksum =
	 * :expectedEffectChecksum`. Returns the affected row count.
	 */
	@Query(
		"UPDATE imported_ambient_steps_source_fence SET collected_data_epoch = :collectedDataEpoch, " +
			"revoked_consent_epoch = :revokedConsentEpoch, deleted_at_ms = :deletedAtMs, " +
			"deletion_completed = :deletionCompleted, completed_at_ms = :completedAtMs, " +
			"reopened_consent_epoch = :reopenedConsentEpoch, reopened_at_ms = :reopenedAtMs, " +
			"effect_checksum = :effectChecksum WHERE id = 1 " +
			"AND collected_data_epoch = :expectedCollectedDataEpoch " +
			"AND revoked_consent_epoch = :expectedRevokedConsentEpoch " +
			"AND effect_checksum = :expectedEffectChecksum",
	)
	abstract suspend fun replaceSourceFenceExact(
		expectedCollectedDataEpoch: Long,
		expectedRevokedConsentEpoch: Long,
		expectedEffectChecksum: String,
		collectedDataEpoch: Long,
		revokedConsentEpoch: Long,
		deletedAtMs: Long,
		deletionCompleted: Boolean,
		completedAtMs: Long?,
		reopenedConsentEpoch: Long?,
		reopenedAtMs: Long?,
		effectChecksum: String,
	): Int

	/**
	 * Updates `imported_ambient_steps_source_fence` rows matching the supplied scope. Query
	 * conditions include `id = 1`, `collected_data_epoch = :expectedCollectedDataEpoch`,
	 * `effect_checksum = :expectedEffectChecksum`. Returns the affected row count.
	 */
	@Query(
		"UPDATE imported_ambient_steps_source_fence SET collected_data_epoch = :collectedDataEpoch, " +
			"revoked_consent_epoch = :revokedConsentEpoch, deleted_at_ms = :deletedAtMs, " +
			"deletion_completed = :deletionCompleted, completed_at_ms = :completedAtMs, " +
			"reopened_consent_epoch = :reopenedConsentEpoch, reopened_at_ms = :reopenedAtMs, " +
			"effect_checksum = :effectChecksum WHERE id = 1 " +
			"AND collected_data_epoch = :expectedCollectedDataEpoch " +
			"AND effect_checksum = :expectedEffectChecksum",
	)
	abstract fun replaceSourceFenceForFullClear(
		expectedCollectedDataEpoch: Long,
		expectedEffectChecksum: String,
		collectedDataEpoch: Long,
		revokedConsentEpoch: Long,
		deletedAtMs: Long,
		deletionCompleted: Boolean,
		completedAtMs: Long?,
		reopenedConsentEpoch: Long?,
		reopenedAtMs: Long?,
		effectChecksum: String,
	): Int

	/**
	 * Reads `imported_ambient_steps_archive` rows. Query conditions include `archive_identity =
	 * :archiveIdentity`. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_archive WHERE archive_identity = :archiveIdentity",
	)
	abstract suspend fun archive(archiveIdentity: String): ImportedAmbientStepsArchiveEntity?

	/**
	 * Reads `imported_ambient_steps_archive` rows. Query conditions include `archive_identity IN
	 * (:archiveIdentities)`. Orders by `archive_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_archive " +
			"WHERE archive_identity IN (:archiveIdentities) ORDER BY archive_identity LIMIT :limit",
	)
	abstract suspend fun archives(
		archiveIdentities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsArchiveEntity>

	/**
	 * Reads `imported_ambient_steps_receipt` rows. Query conditions include `import_job_id = :jobId`,
	 * `archive_key = :archiveKey`. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_receipt " +
			"WHERE import_job_id = :jobId AND archive_key = :archiveKey",
	)
	abstract suspend fun receipt(
		jobId: String,
		archiveKey: String,
	): ImportedAmbientStepsReceiptEntity?

	/**
	 * Reads `imported_ambient_steps_receipt` rows. Query conditions include `receipt_identity =
	 * :receiptIdentity`. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_receipt WHERE receipt_identity = :receiptIdentity",
	)
	abstract suspend fun receiptByIdentity(
		receiptIdentity: String,
	): ImportedAmbientStepsReceiptEntity?

	/**
	 * Reads `imported_ambient_steps_receipt` rows. Query conditions include `archive_identity IN
	 * (:archiveIdentities)`. Orders by `archive_identity, import_job_id, archive_key`. Returns at
	 * most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_receipt " +
			"WHERE archive_identity IN (:archiveIdentities) " +
			"ORDER BY archive_identity, import_job_id, archive_key LIMIT :limit",
	)
	abstract suspend fun receiptsForArchives(
		archiveIdentities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsReceiptEntity>

	/**
	 * Counts rows in `imported_ambient_steps_receipt`. Query conditions include `archive_identity =
	 * :archiveIdentity`.
	 */
	@Query(
		"SELECT COUNT(*) AS receipt_count, MIN(received_at_ms) AS earliest_received_at_ms " +
			"FROM imported_ambient_steps_receipt WHERE archive_identity = :archiveIdentity",
	)
	abstract suspend fun receiptStats(
		archiveIdentity: String,
	): ImportedAmbientStepsReceiptStats

	/**
	 * Reads `imported_ambient_steps_archive_day` rows. Query conditions include `archive_identity =
	 * :archiveIdentity`. Orders by `ordinal`.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_archive_day " +
			"WHERE archive_identity = :archiveIdentity ORDER BY ordinal",
	)
	abstract suspend fun archiveDays(
		archiveIdentity: String,
	): List<ImportedAmbientStepsArchiveDayEntity>

	/**
	 * Reads `imported_ambient_steps_archive_day` rows. Query conditions include `archive_identity IN
	 * (:archiveIdentities)`. Orders by `archive_identity, ordinal`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_archive_day " +
			"WHERE archive_identity IN (:archiveIdentities) " +
			"ORDER BY archive_identity, ordinal LIMIT :limit",
	)
	abstract suspend fun archiveDays(
		archiveIdentities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsArchiveDayEntity>

	/**
	 * Reads `imported_ambient_steps_archive_day` rows. Query conditions include `day_identity =
	 * :dayIdentity`. Orders by `archive_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_archive_day WHERE day_identity = :dayIdentity " +
			"ORDER BY archive_identity LIMIT :limit",
	)
	abstract suspend fun archiveDaysForDay(
		dayIdentity: String,
		limit: Int,
	): List<ImportedAmbientStepsArchiveDayEntity>

	/**
	 * Updates `imported_ambient_steps_archive_day` rows matching the supplied scope. Query conditions
	 * include `day_identity = :dayIdentity`, `bound_day_import_revision = :dayImportRevision`,
	 * `bound_count_domain_graph_revision = :expectedGraphRevision`. Returns the affected row count.
	 */
	@Query(
		"UPDATE imported_ambient_steps_archive_day " +
			"SET bound_count_domain_graph_revision = :newGraphRevision " +
			"WHERE day_identity = :dayIdentity " +
			"AND bound_day_import_revision = :dayImportRevision " +
			"AND bound_count_domain_graph_revision = :expectedGraphRevision",
	)
	abstract suspend fun rebindDayRevisionCountDomainGraph(
		dayIdentity: String,
		dayImportRevision: Long,
		expectedGraphRevision: Long,
		newGraphRevision: Long,
	): Int

	/**
	 * Reads one imported Steps day in revision order with an overflow row; callers reject truncated
	 * correction lineages.
	 */
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

	/**
	 * Reads `imported_ambient_steps_day_revision` rows. Query conditions include `day_identity =
	 * :dayIdentity`. Orders by `import_revision`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_day_revision WHERE day_identity = :dayIdentity " +
			"ORDER BY import_revision LIMIT :limit",
	)
	abstract fun dayRevisionsForFullClear(
		dayIdentity: String,
		limit: Int,
	): List<ImportedAmbientStepsDayRevisionEntity>

	/**
	 * Reads the imported Steps fact rows for one day with an overflow row beyond the admission limit.
	 */
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

	/**
	 * Reads `imported_ambient_steps_fact` rows. Query conditions include `day_identity =
	 * :dayIdentity`. Orders by `day_import_revision, interval_start_time_ms, fact_identity`. Returns
	 * at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_fact WHERE day_identity = :dayIdentity " +
			"ORDER BY day_import_revision, interval_start_time_ms, fact_identity LIMIT :limit",
	)
	abstract fun factsForFullClear(
		dayIdentity: String,
		limit: Int,
	): List<ImportedAmbientStepsFactEntity>

	/**
	 * Reads the imported Steps gap rows for one day with an overflow row beyond the admission limit.
	 */
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

	/**
	 * Reads `imported_ambient_steps_gap` rows. Query conditions include `day_identity =
	 * :dayIdentity`. Orders by `day_import_revision, interval_start_time_ms, gap_identity`. Returns
	 * at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_gap WHERE day_identity = :dayIdentity " +
			"ORDER BY day_import_revision, interval_start_time_ms, gap_identity LIMIT :limit",
	)
	abstract fun gapsForFullClear(
		dayIdentity: String,
		limit: Int,
	): List<ImportedAmbientStepsGapEntity>

	/**
	 * Reads `imported_ambient_steps_day_revision` rows. Query conditions include `day.day_identity >
	 * :afterDayIdentity`. Orders by `day.day_identity`. Returns at most `limit` rows.
	 */
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
		WHERE :afterDayIdentity IS NULL OR day.day_identity > :afterDayIdentity
		ORDER BY day.day_identity
		LIMIT :limit
		""",
	)
	abstract fun fullClearDayCandidatePage(
		afterDayIdentity: String?,
		limit: Int,
	): List<ImportedAmbientStepsDayRevisionEntity>

	/**
	 * Reads `imported_ambient_steps_archive_day` rows. Query conditions include `day_identity =
	 * :dayIdentity`. Orders by `archive_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_archive_day WHERE day_identity = :dayIdentity " +
			"ORDER BY archive_identity LIMIT :limit",
	)
	abstract fun archiveDaysForFullClear(
		dayIdentity: String,
		limit: Int,
	): List<ImportedAmbientStepsArchiveDayEntity>

	/**
	 * Reads `imported_ambient_steps_archive` rows. Query conditions include `archive_identity IN
	 * (:archiveIdentities)`. Orders by `archive_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_archive " +
			"WHERE archive_identity IN (:archiveIdentities) ORDER BY archive_identity LIMIT :limit",
	)
	abstract fun archivesForFullClear(
		archiveIdentities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsArchiveEntity>

	/**
	 * Reads `imported_ambient_steps_archive_day` rows. Query conditions include `archive_identity IN
	 * (:archiveIdentities)`. Orders by `archive_identity, ordinal`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_archive_day " +
			"WHERE archive_identity IN (:archiveIdentities) " +
			"ORDER BY archive_identity, ordinal LIMIT :limit",
	)
	abstract fun allArchiveDaysForFullClear(
		archiveIdentities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsArchiveDayEntity>

	/**
	 * Reads `imported_ambient_steps_receipt` rows. Query conditions include `archive_identity IN
	 * (:archiveIdentities)`. Orders by `archive_identity, import_job_id, archive_key`. Returns at
	 * most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_receipt " +
			"WHERE archive_identity IN (:archiveIdentities) " +
			"ORDER BY archive_identity, import_job_id, archive_key LIMIT :limit",
	)
	abstract fun receiptsForFullClear(
		archiveIdentities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsReceiptEntity>

	/**
	 * Reads `imported_ambient_steps_receipt` rows. Query conditions include `receipt_identity =
	 * :receiptIdentity`. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_receipt WHERE receipt_identity = :receiptIdentity",
	)
	abstract fun receiptByIdentityForFullClear(
		receiptIdentity: String,
	): ImportedAmbientStepsReceiptEntity?

	/**
	 * Reads `imported_ambient_steps_day_revision` rows. Query conditions include
	 * `day.structural_day_start_time_ms < :toExclusiveMs`, `day.structural_day_end_time_ms >
	 * :fromInclusiveMs`. Orders by `day.structural_day_start_time_ms, day.day_identity`. Returns at
	 * most `limit` rows. Timestamp bounds use UTC milliseconds.
	 */
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

	/**
	 * Reads `imported_ambient_steps_day_revision` rows. Query conditions include `day.stored_zone_id
	 * IN (:storedZoneIds)`. Orders by `day.structural_epoch_day, day.stored_zone_id,
	 * day.day_identity`. Returns at most `limit` rows.
	 */
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
		WHERE day.structural_epoch_day BETWEEN :firstEpochDay AND :lastEpochDayInclusive
		  AND day.stored_zone_id IN (:storedZoneIds)
		ORDER BY day.structural_epoch_day, day.stored_zone_id, day.day_identity
		LIMIT :limit
		""",
	)
	abstract suspend fun latestDaysForStructuralRange(
		firstEpochDay: Long,
		lastEpochDayInclusive: Long,
		storedZoneIds: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsDayRevisionEntity>

	/** Exact imported organization membership, without adopting another day at the same time. */
	@Query("SELECT day.* FROM imported_ambient_steps_day_revision day WHERE day.day_identity IN (:identities) " +
		"AND day.import_revision = (SELECT MAX(n.import_revision) FROM imported_ambient_steps_day_revision n " +
		("WHERE n.day_identity = day.day_identity) ORDER BY day.structural_epoch_day, day.stored_zone_id, " +
            "day.day_identity LIMIT :limit"))
	abstract suspend fun latestDaysForIdentities(identities: List<String>,
        limit: Int): List<ImportedAmbientStepsDayRevisionEntity>

	/**
	 * Reads `imported_ambient_steps_day_fence` rows. Query conditions include `day_identity IN
	 * (:identities)`. Orders by `structural_epoch_day, stored_zone_id, day_identity`. Returns at most
	 * `limit` rows.
	 */
	@Query("SELECT * FROM imported_ambient_steps_day_fence WHERE day_identity IN (:identities) " +
		"ORDER BY structural_epoch_day, stored_zone_id, day_identity LIMIT :limit")
	abstract suspend fun fencesForIdentities(identities: List<String>,
        limit: Int): List<ImportedAmbientStepsDayFenceEntity>

	/**
	 * Reads `imported_ambient_steps_day_revision` rows. Orders by `day.structural_epoch_day,
	 * day.stored_zone_id, day.day_identity`. Returns at most `limit` rows.
	 */
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
		WHERE day.structural_epoch_day BETWEEN :firstEpochDay AND :lastEpochDayInclusive
		ORDER BY day.structural_epoch_day, day.stored_zone_id, day.day_identity
		LIMIT :limit
		""",
	)
	abstract suspend fun latestDaysForEpochRange(
		firstEpochDay: Long,
		lastEpochDayInclusive: Long,
		limit: Int,
	): List<ImportedAmbientStepsDayRevisionEntity>

	/**
	 * Reads `imported_ambient_steps_day_revision` rows. Orders by `latest_evidence_time_ms DESC,
	 * day.structural_epoch_day DESC, day.stored_zone_id ASC, day.day_identity ASC`. Returns at most
	 * `limit` rows. Timestamp bounds use UTC milliseconds.
	 */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT day_identity, MAX(import_revision) AS import_revision
		  FROM imported_ambient_steps_day_revision
		  GROUP BY day_identity
		)
		SELECT day.day_identity,
		       day.structural_epoch_day,
		       day.stored_zone_id,
		       day.structural_day_start_time_ms,
		       day.structural_day_end_time_ms,
		       MAX(fact.interval_end_time_ms) AS latest_evidence_time_ms
		FROM imported_ambient_steps_day_revision AS day
		INNER JOIN latest_revision AS latest
		  ON latest.day_identity = day.day_identity
		 AND latest.import_revision = day.import_revision
		INNER JOIN imported_ambient_steps_fact AS fact
		  ON fact.day_identity = day.day_identity
		 AND fact.day_import_revision = day.import_revision
		GROUP BY day.day_identity,
		         day.import_revision,
		         day.structural_epoch_day,
		         day.stored_zone_id,
		         day.structural_day_start_time_ms,
		         day.structural_day_end_time_ms
		HAVING :beforeLatestEvidenceTimeMs IS NULL
		    OR MAX(fact.interval_end_time_ms) < :beforeLatestEvidenceTimeMs
		    OR (
		      MAX(fact.interval_end_time_ms) = :beforeLatestEvidenceTimeMs
		      AND day.structural_epoch_day < :beforeEpochDay
		    )
		    OR (
		      MAX(fact.interval_end_time_ms) = :beforeLatestEvidenceTimeMs
		      AND day.structural_epoch_day = :beforeEpochDay
		      AND (
		        day.stored_zone_id > :beforeStoredZoneId
		        OR (
		          day.stored_zone_id = :beforeStoredZoneId
		          AND day.day_identity > :beforeDayIdentity
		        )
		      )
		    )
		ORDER BY latest_evidence_time_ms DESC,
		         day.structural_epoch_day DESC,
		         day.stored_zone_id ASC,
		         day.day_identity ASC
		LIMIT :limit
		""",
	)
	abstract suspend fun recentDayCandidatePage(
		beforeLatestEvidenceTimeMs: Long?,
		beforeEpochDay: Long?,
		beforeStoredZoneId: String?,
		beforeDayIdentity: String?,
		limit: Int,
	): List<ImportedAmbientStepsRecentDayCandidate>

	/**
	 * Reads `imported_ambient_steps_day_revision` rows. Query conditions include `day_identity IN
	 * (:dayIdentities)`. Orders by `day_identity, import_revision`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_day_revision " +
			"WHERE day_identity IN (:dayIdentities) " +
			"ORDER BY day_identity, import_revision LIMIT :limit",
	)
	abstract suspend fun dayRevisionsForHistory(
		dayIdentities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsDayRevisionEntity>

	/**
	 * Reads `imported_ambient_steps_archive_day` rows. Query conditions include `day_identity IN
	 * (:dayIdentities)`. Orders by `day_identity, archive_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_archive_day " +
			"WHERE day_identity IN (:dayIdentities) " +
			"ORDER BY day_identity, archive_identity LIMIT :limit",
	)
	abstract suspend fun archiveDaysForHistory(
		dayIdentities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsArchiveDayEntity>

	/**
	 * Reads `imported_ambient_steps_fact` rows. Query conditions include `day_identity IN
	 * (:dayIdentities)`. Orders by `day_identity, day_import_revision, interval_start_time_ms,
	 * fact_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_fact WHERE day_identity IN (:dayIdentities) " +
			"ORDER BY day_identity, day_import_revision, interval_start_time_ms, fact_identity " +
			"LIMIT :limit",
	)
	abstract suspend fun factsForHistory(
		dayIdentities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsFactEntity>

	/**
	 * Reads `imported_ambient_steps_gap` rows. Query conditions include `day_identity IN
	 * (:dayIdentities)`. Orders by `day_identity, day_import_revision, interval_start_time_ms,
	 * gap_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_gap WHERE day_identity IN (:dayIdentities) " +
			"ORDER BY day_identity, day_import_revision, interval_start_time_ms, gap_identity " +
			"LIMIT :limit",
	)
	abstract suspend fun gapsForHistory(
		dayIdentities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsGapEntity>

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

	/**
	 * Reads `imported_ambient_steps_day_revision` rows. Orders by `day.structural_day_start_time_ms,
	 * day.day_identity`. Returns at most 1 row. Returns null when no row matches.
	 */
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

	/**
	 * Reads `imported_ambient_steps_day_fence` rows. Query conditions include `day_identity =
	 * :dayIdentity`. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_day_fence WHERE day_identity = :dayIdentity",
	)
	abstract suspend fun fence(dayIdentity: String): ImportedAmbientStepsDayFenceEntity?

	/**
	 * Reads `imported_ambient_steps_day_fence` rows. Query conditions include `day_identity =
	 * :dayIdentity`. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_day_fence WHERE day_identity = :dayIdentity",
	)
	abstract fun fenceForFullClear(dayIdentity: String): ImportedAmbientStepsDayFenceEntity?

	/**
	 * Reads `imported_ambient_steps_day_fence` rows. Query conditions include `day_identity IN
	 * (:dayIdentities)`. Orders by `day_identity`.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_day_fence " +
			"WHERE day_identity IN (:dayIdentities) ORDER BY day_identity",
	)
	abstract suspend fun fences(
		dayIdentities: List<String>,
	): List<ImportedAmbientStepsDayFenceEntity>

	/**
	 * Reads `imported_ambient_steps_day_fence` rows. Query conditions include
	 * `structural_day_start_time_ms < :toExclusiveMs`, `structural_day_end_time_ms >
	 * :fromInclusiveMs`. Orders by `structural_day_start_time_ms, day_identity`. Returns at most
	 * `limit` rows. Timestamp bounds use UTC milliseconds.
	 */
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

	/**
	 * Reads `imported_ambient_steps_day_fence` rows. Query conditions include `stored_zone_id IN
	 * (:storedZoneIds)`. Orders by `structural_epoch_day, stored_zone_id, day_identity`. Returns at
	 * most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_day_fence " +
			"WHERE structural_epoch_day BETWEEN :firstEpochDay AND :lastEpochDayInclusive " +
			"AND stored_zone_id IN (:storedZoneIds) " +
			"ORDER BY structural_epoch_day, stored_zone_id, day_identity LIMIT :limit",
	)
	abstract suspend fun fencesForStructuralRange(
		firstEpochDay: Long,
		lastEpochDayInclusive: Long,
		storedZoneIds: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsDayFenceEntity>

	/**
	 * Reads `imported_ambient_steps_day_fence` rows. Orders by `structural_epoch_day, stored_zone_id,
	 * day_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_day_fence " +
			"WHERE structural_epoch_day BETWEEN :firstEpochDay AND :lastEpochDayInclusive " +
			"ORDER BY structural_epoch_day, stored_zone_id, day_identity LIMIT :limit",
	)
	abstract suspend fun fencesForEpochRange(
		firstEpochDay: Long,
		lastEpochDayInclusive: Long,
		limit: Int,
	): List<ImportedAmbientStepsDayFenceEntity>

	/**
	 * Reads `imported_ambient_steps_day_fence` rows. Orders by `day_identity`. Returns at most
	 * `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_day_fence ORDER BY day_identity LIMIT :limit",
	)
	abstract suspend fun allFences(limit: Int): List<ImportedAmbientStepsDayFenceEntity>

	/**
	 * Reads `imported_ambient_steps_day_fence` rows. Query conditions include `day_identity >
	 * :afterDayIdentity`. Orders by `day_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_day_fence WHERE " +
			":afterDayIdentity IS NULL OR day_identity > :afterDayIdentity " +
			"ORDER BY day_identity LIMIT :limit",
	)
	abstract fun fencePageForFullClear(
		afterDayIdentity: String?,
		limit: Int,
	): List<ImportedAmbientStepsDayFenceEntity>

	/**
	 * Reads `imported_ambient_steps_protected_identity` rows. Query conditions include
	 * `owner_day_identity = :dayIdentity`. Orders by `protected_identity, identity_kind`. Returns at
	 * most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_protected_identity " +
			"WHERE owner_day_identity = :dayIdentity " +
			"ORDER BY protected_identity, identity_kind LIMIT :limit",
	)
	abstract suspend fun protectedIdentitiesForDay(
		dayIdentity: String,
		limit: Int,
	): List<ImportedAmbientStepsProtectedIdentityEntity>

	/**
	 * Reads `imported_ambient_steps_protected_identity` rows. Query conditions include
	 * `owner_day_identity = :dayIdentity`. Orders by `protected_identity, identity_kind`. Returns at
	 * most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_protected_identity " +
			"WHERE owner_day_identity = :dayIdentity " +
			"ORDER BY protected_identity, identity_kind LIMIT :limit",
	)
	abstract fun protectedIdentitiesForFullClear(
		dayIdentity: String,
		limit: Int,
	): List<ImportedAmbientStepsProtectedIdentityEntity>

	/**
	 * Reads `imported_ambient_steps_protected_identity` rows. Query conditions include
	 * `protected_identity IN (:identities)`. Orders by `protected_identity, identity_kind`. Returns
	 * at most `limit` rows.
	 */
	@Query(
		"SELECT DISTINCT protected_identity, identity_kind " +
			"FROM imported_ambient_steps_protected_identity " +
			"WHERE protected_identity IN (:identities) " +
			"ORDER BY protected_identity, identity_kind LIMIT :limit",
	)
	abstract fun protectedIdentityKindsForFullClear(
		identities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsProtectedIdentityKind>

	/**
	 * Updates `imported_ambient_steps_day_fence` rows matching the supplied scope. Query conditions
	 * include `day_identity = :dayIdentity`, `collected_data_epoch = :expectedCollectedDataEpoch`,
	 * `effect_checksum = :expectedEffectChecksum`. Returns the affected row count.
	 */
	@Query(
		"UPDATE imported_ambient_steps_day_fence SET collected_data_epoch = :collectedDataEpoch, " +
			"source_evidence_revision = :sourceEvidenceRevision, effect_checksum = :effectChecksum " +
			"WHERE day_identity = :dayIdentity AND collected_data_epoch = :expectedCollectedDataEpoch " +
			"AND effect_checksum = :expectedEffectChecksum",
	)
	abstract fun replaceFenceForFullClear(
		dayIdentity: String,
		expectedCollectedDataEpoch: Long,
		expectedEffectChecksum: String,
		collectedDataEpoch: Long,
		sourceEvidenceRevision: Long,
		effectChecksum: String,
	): Int

	/**
	 * Reads `imported_ambient_steps_protected_identity` rows. Query conditions include
	 * `protected_identity IN (:identities)`. Orders by `protected_identity, owner_day_identity`.
	 * Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_steps_protected_identity " +
			"WHERE protected_identity IN (:identities) " +
			"ORDER BY protected_identity, owner_day_identity LIMIT :limit",
	)
	abstract suspend fun protectedIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsProtectedIdentityEntity>

	/**
	 * Reads `imported_ambient_steps_protected_identity` rows. Query conditions include
	 * `owner_day_identity > :afterOwnerDayIdentity`, `owner_day_identity = :afterOwnerDayIdentity`.
	 * Orders by `owner_day_identity, protected_identity`. Returns at most `limit` rows.
	 */
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

	/**
	 * Reads `imported_ambient_steps_archive` rows. Query conditions include `archive_identity IN
	 * (:identities)`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT archive_identity FROM imported_ambient_steps_archive " +
			"WHERE archive_identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingArchiveIdentities(
		identities: List<String>,
		limit: Int,
	): List<String>

	/**
	 * Reads `imported_ambient_steps_receipt` rows. Query conditions include `receipt_identity IN
	 * (:identities)`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT receipt_identity FROM imported_ambient_steps_receipt " +
			"WHERE receipt_identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingReceiptIdentities(
		identities: List<String>,
		limit: Int,
	): List<String>

	/**
	 * Reads `imported_ambient_steps_day_revision` rows. Query conditions include `day_identity IN
	 * (:identities)`, `deletion_scope_identity IN (:identities)`. Returns at most `limit` rows.
	 */
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

	/**
	 * Reads `imported_ambient_steps_fact` rows. Query conditions include `fact_identity IN
	 * (:identities)`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT DISTINCT fact_identity, day_identity FROM imported_ambient_steps_fact " +
			"WHERE fact_identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingFactIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsFactIdentityOwner>

	/**
	 * Reads `imported_ambient_steps_gap` rows. Query conditions include `gap_identity IN
	 * (:identities)`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT DISTINCT gap_identity, day_identity FROM imported_ambient_steps_gap " +
			"WHERE gap_identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingGapIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedAmbientStepsGapIdentityOwner>

	/**
	 * Deletes `imported_ambient_steps_day_revision` rows matching the supplied scope. Query
	 * conditions include `day_identity = :dayIdentity`. Returns the affected row count.
	 */
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

	/**
	 * Counts rows in `imported_ambient_steps_archive`.
	 */
	@Query("SELECT COUNT(*) FROM imported_ambient_steps_archive")
	abstract suspend fun archiveCount(): Long

	/**
	 * Counts rows in `imported_ambient_steps_receipt`.
	 */
	@Query("SELECT COUNT(*) FROM imported_ambient_steps_receipt")
	abstract suspend fun receiptCount(): Long

	/**
	 * Counts rows in `imported_ambient_steps_archive_day`.
	 */
	@Query("SELECT COUNT(*) FROM imported_ambient_steps_archive_day")
	abstract suspend fun archiveDayCount(): Long

	/**
	 * Counts rows in `imported_ambient_steps_day_revision`.
	 */
	@Query("SELECT COUNT(*) FROM imported_ambient_steps_day_revision")
	abstract suspend fun dayRevisionCount(): Long

	/**
	 * Counts rows in `imported_ambient_steps_fact`.
	 */
	@Query("SELECT COUNT(*) FROM imported_ambient_steps_fact")
	abstract suspend fun factCount(): Long

	/**
	 * Counts rows in `imported_ambient_steps_gap`.
	 */
	@Query("SELECT COUNT(*) FROM imported_ambient_steps_gap")
	abstract suspend fun gapCount(): Long

	/**
	 * Counts rows in `imported_ambient_steps_day_fence`.
	 */
	@Query("SELECT COUNT(*) FROM imported_ambient_steps_day_fence")
	abstract suspend fun fenceCount(): Long

	/**
	 * Counts rows in `imported_ambient_steps_day_fence`.
	 */
	@Query("SELECT COUNT(*) FROM imported_ambient_steps_day_fence")
	abstract fun fenceCountForFullClear(): Long

	/**
	 * Counts rows in `imported_ambient_steps_protected_identity`.
	 */
	@Query("SELECT COUNT(*) FROM imported_ambient_steps_protected_identity")
	abstract suspend fun protectedIdentityCount(): Long

	/**
	 * Counts rows in `imported_ambient_steps_protected_identity`.
	 */
	@Query("SELECT COUNT(*) FROM imported_ambient_steps_protected_identity")
	abstract fun protectedIdentityCountForFullClear(): Long

	/**
	 * Deletes `imported_ambient_steps_day_revision` rows.
	 */
	@Query("DELETE FROM imported_ambient_steps_day_revision")
	abstract fun deleteAllDays()

	/**
	 * Deletes `imported_ambient_steps_receipt` rows.
	 */
	@Query("DELETE FROM imported_ambient_steps_receipt")
	abstract fun deleteAllReceipts()

	/**
	 * Deletes `imported_ambient_steps_archive` rows.
	 */
	@Query("DELETE FROM imported_ambient_steps_archive")
	abstract fun deleteAllArchives()

	/**
	 * Limits for bounded all-day Steps admission, lineage and maintenance reads; callers detect
	 * overflow before loading more dependencies.
	 */
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

/**
 * Associates an imported civil-day identity with the deletion scope that owns it.
 */
data class ImportedAmbientStepsDayIdentityOwner(
	@ColumnInfo(name = "day_identity") val dayIdentity: String,
	@ColumnInfo(name = "deletion_scope_identity") val deletionScopeIdentity: String,
)

/**
 * Associates an imported provider-count fact identity with its owning saved day.
 */
data class ImportedAmbientStepsFactIdentityOwner(
	@ColumnInfo(name = "fact_identity") val factIdentity: String,
	@ColumnInfo(name = "day_identity") val dayIdentity: String,
)

/**
 * Associates an imported coverage-gap identity with its owning saved day.
 */
data class ImportedAmbientStepsGapIdentityOwner(
	@ColumnInfo(name = "gap_identity") val gapIdentity: String,
	@ColumnInfo(name = "day_identity") val dayIdentity: String,
)

/**
 * Saved Steps day, timezone and UTC bounds with its latest evidence time for recent-day paging.
 */
data class ImportedAmbientStepsRecentDayCandidate(
	@ColumnInfo(name = "day_identity") val dayIdentity: String,
	@ColumnInfo(name = "structural_epoch_day") val structuralEpochDay: Long,
	@ColumnInfo(name = "stored_zone_id") val storedZoneId: String,
	@ColumnInfo(name = "structural_day_start_time_ms") val structuralDayStartTimeMs: Long,
	@ColumnInfo(name = "structural_day_end_time_ms") val structuralDayEndTimeMs: Long,
	@ColumnInfo(name = "latest_evidence_time_ms") val latestEvidenceTimeMs: Long,
)

/**
 * Receipt cardinality and earliest import time; the time is null when no receipt exists.
 */
data class ImportedAmbientStepsReceiptStats(
	@ColumnInfo(name = "receipt_count") val receiptCount: Long,
	@ColumnInfo(name = "earliest_received_at_ms") val earliestReceivedAtMs: Long?,
)

/**
 * Protected imported Steps identity paired with the kind needed to reject resurrection.
 */
data class ImportedAmbientStepsProtectedIdentityKind(
	@ColumnInfo(name = "protected_identity") val protectedIdentity: String,
	@ColumnInfo(name = "identity_kind") val identityKind: String,
)
