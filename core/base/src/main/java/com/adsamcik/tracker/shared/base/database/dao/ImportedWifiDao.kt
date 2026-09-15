package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunZoneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionProtectedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionReceiptEntity

/** Wi-Fi-local imported-product storage. This DAO grants no live capture authority. */
@Dao
abstract class ImportedWifiDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryRevision(row: ImportedWifiEntryRevisionEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertReceipt(row: ImportedWifiReceiptEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertRun(row: ImportedWifiRunEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertRunZone(row: ImportedWifiRunZoneEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertObservation(row: ImportedWifiObservationEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	protected abstract suspend fun insertDeletionGenerationRow(row: ImportedWifiDeletionGenerationEntity)

	suspend fun insertDeletionGeneration(row: ImportedWifiDeletionGenerationEntity) {
		require(row.generation == 1L)
		insertDeletionGenerationRow(row)
	}

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun insertImportedDeletionGenerations(
		rows: List<ImportedWifiDeletionGenerationEntity>,
	): List<Long>

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun insertLocalDeletionGenerations(
		rows: List<WifiCaptureDeletionGenerationEntity>,
	): List<Long>

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun insertSourceDeletionFences(rows: List<SourceDeletionFenceEntity>): List<Long>

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryDeletion(row: ImportedWifiEntryDeletionEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertSelectedDeletionReceipt(row: WifiSelectedDeletionReceiptEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertSelectedDeletionProtectedIdentities(
		rows: List<WifiSelectedDeletionProtectedIdentityEntity>,
	)

	@Query(
		"SELECT * FROM wifi_selected_deletion_receipt WHERE selection_identity = :selection " +
			"AND origin = :origin",
	)
	abstract suspend fun selectedDeletionReceipt(
		selection: String,
		origin: String,
	): WifiSelectedDeletionReceiptEntity?

	@Query(
		"SELECT * FROM wifi_selected_deletion_protected_identity " +
			"WHERE selection_identity = :selection AND receipt_origin = :origin " +
			"ORDER BY identity_kind, protected_identity LIMIT :limit",
	)
	abstract suspend fun selectedDeletionProtectedIdentities(
		selection: String,
		origin: String,
		limit: Int,
	): List<WifiSelectedDeletionProtectedIdentityEntity>

	@Query(
		"SELECT * FROM wifi_selected_deletion_protected_identity " +
			"WHERE protected_identity IN (:identities) OR owner_entry_identity IN (:identities) " +
			"OR owner_run_identity IN (:identities) OR deletion_scope_digest IN (:identities) " +
			"OR aggregate_owner_identity IN (:identities) " +
			"ORDER BY selection_identity, receipt_origin, identity_kind, protected_identity LIMIT :limit",
	)
	abstract suspend fun selectedDeletionProtectedIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<WifiSelectedDeletionProtectedIdentityEntity>

	@Query(
		"SELECT * FROM wifi_selected_deletion_protected_identity " +
			"WHERE selection_identity != :excludedSelection AND (" +
			"protected_identity IN (:identities) OR owner_entry_identity IN (:identities) " +
			"OR owner_run_identity IN (:identities) OR deletion_scope_digest IN (:identities) " +
			"OR aggregate_owner_identity IN (:identities)) LIMIT 1",
	)
	abstract suspend fun foreignSelectedDeletionProtectedIdentityOwner(
		identities: List<String>,
		excludedSelection: String,
	): WifiSelectedDeletionProtectedIdentityEntity?

	@Query(
		"""
		SELECT DISTINCT 'ENTRY' AS owner_kind,
		       identity AS protected_identity,
		       identity AS entry_identity,
		       NULL AS run_identity,
		       NULL AS deletion_scope_digest,
		       NULL AS source_kind,
		       NULL AS purpose,
		       NULL AS scope_kind,
		       collected_data_epoch,
		       NULL AS receipt_origin,
		       NULL AS selection_identity
		FROM imported_wifi_entry_revision
		WHERE identity IN (:identities)
		UNION ALL
		SELECT DISTINCT 'RUN', identity, entry_identity, identity, deletion_scope_digest,
		       NULL, NULL, NULL, collected_data_epoch, NULL, NULL
		FROM imported_wifi_run
		WHERE identity IN (:identities)
		UNION ALL
		SELECT DISTINCT 'OBSERVATION', identity, entry_identity, run_identity, NULL,
		       NULL, NULL, NULL,
		       (SELECT header.collected_data_epoch
		        FROM imported_wifi_entry_revision AS header
		        WHERE header.identity = imported_wifi_observation.entry_identity
		          AND header.import_revision = imported_wifi_observation.entry_import_revision),
		       NULL,
		       NULL
		FROM imported_wifi_observation
		WHERE identity IN (:identities)
		UNION ALL
		SELECT DISTINCT 'DELETION_SCOPE', deletion_scope_digest, entry_identity, identity,
		       deletion_scope_digest, NULL, NULL, NULL, collected_data_epoch, NULL, NULL
		FROM imported_wifi_run
		WHERE deletion_scope_digest IN (:identities)
		UNION ALL
		SELECT 'ENTRY_DELETION', entry_identity, entry_identity, NULL, NULL,
		       NULL, NULL, NULL, collected_data_epoch, NULL, NULL
		FROM imported_wifi_entry_deletion
		WHERE entry_identity IN (:identities)
		UNION ALL
		SELECT 'RUN_DELETION', run_identity, entry_identity, run_identity,
		       deletion_scope_digest, NULL, NULL, NULL, collected_data_epoch, NULL, NULL
		FROM imported_wifi_deletion_generation
		WHERE run_identity IN (:identities)
		   OR entry_identity IN (:identities)
		   OR deletion_scope_digest IN (:identities)
		UNION ALL
		SELECT 'SOURCE_FENCE', scope_identity_digest, NULL, NULL, scope_identity_digest,
		       source_kind, purpose, scope_kind, collected_data_epoch, NULL, NULL
		FROM source_deletion_fence
		WHERE scope_identity_digest IN (:identities)
		UNION ALL
		SELECT 'SELECTED_PROTECTED', protected_identity, owner_entry_identity, owner_run_identity,
		       deletion_scope_digest, NULL, NULL, NULL, collected_data_epoch, receipt_origin,
		       selection_identity
		FROM wifi_selected_deletion_protected_identity
		WHERE protected_identity IN (:identities)
		   OR owner_entry_identity IN (:identities)
		   OR owner_run_identity IN (:identities)
		   OR deletion_scope_digest IN (:identities)
		   OR aggregate_owner_identity IN (:identities)
		ORDER BY protected_identity, owner_kind, entry_identity, run_identity
		LIMIT :limit
		""",
	)
	abstract suspend fun authorityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedWifiAuthorityOwner>

	@Query(
		"SELECT * FROM imported_wifi_entry_revision WHERE identity = :identity " +
			"ORDER BY import_revision LIMIT :limit",
	)
	protected abstract suspend fun loadEntryRevisions(
		identity: String,
		limit: Int,
	): List<ImportedWifiEntryRevisionEntity>

	suspend fun entryRevisionsForAdmission(identity: String): List<ImportedWifiEntryRevisionEntity> =
		loadEntryRevisions(identity, MAX_REVISIONS_PER_ENTRY + 1)

	@Query(
		"SELECT * FROM imported_wifi_entry_revision WHERE identity IN (:identities) " +
			"ORDER BY identity, import_revision LIMIT :limit",
	)
	abstract suspend fun entryRevisionsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedWifiEntryRevisionEntity>

	@Query(
		"SELECT * FROM imported_wifi_receipt WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, import_job_id, import_entry_key LIMIT :limit",
	)
	protected abstract suspend fun loadReceipts(
		identity: String,
		limit: Int,
	): List<ImportedWifiReceiptEntity>

	suspend fun receiptsForAdmission(identity: String): List<ImportedWifiReceiptEntity> =
		loadReceipts(identity, MAX_RECEIPTS_PER_ENTRY + 1)

	@Query(
		"SELECT * FROM imported_wifi_receipt WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, import_job_id, import_entry_key LIMIT :limit",
	)
	abstract suspend fun receiptsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedWifiReceiptEntity>

	@Query(
		"SELECT * FROM imported_wifi_receipt " +
			"WHERE import_job_id = :jobId AND import_entry_key = :entryKey",
	)
	abstract suspend fun receipt(jobId: String, entryKey: String): ImportedWifiReceiptEntity?

	@Query(
		"SELECT * FROM imported_wifi_run WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, start_time_ms, identity LIMIT :limit",
	)
	protected abstract suspend fun loadAllRuns(
		identity: String,
		limit: Int,
	): List<ImportedWifiRunEntity>

	suspend fun allRunsForAdmission(identity: String): List<ImportedWifiRunEntity> =
		loadAllRuns(identity, MAX_TOTAL_RUNS_PER_ENTRY_LINEAGE + 1)

	@Query(
		"SELECT * FROM imported_wifi_run WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, start_time_ms, identity LIMIT :limit",
	)
	abstract suspend fun runsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedWifiRunEntity>

	@Query(
		"SELECT * FROM imported_wifi_run_zone WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, run_identity, ordinal LIMIT :limit",
	)
	protected abstract suspend fun loadAllRunZones(
		identity: String,
		limit: Int,
	): List<ImportedWifiRunZoneEntity>

	suspend fun allRunZonesForAdmission(identity: String): List<ImportedWifiRunZoneEntity> =
		loadAllRunZones(identity, MAX_TOTAL_ZONES_PER_ENTRY_LINEAGE + 1)

	@Query(
		"SELECT * FROM imported_wifi_run_zone WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, run_identity, ordinal LIMIT :limit",
	)
	abstract suspend fun runZonesForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedWifiRunZoneEntity>

	@Query(
		"SELECT * FROM imported_wifi_observation WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, run_identity, coverage_start_time_ms, observed_time_ms, identity " +
			"LIMIT :limit",
	)
	protected abstract suspend fun loadAllObservations(
		identity: String,
		limit: Int,
	): List<ImportedWifiObservationEntity>

	suspend fun allObservationsForAdmission(identity: String): List<ImportedWifiObservationEntity> =
		loadAllObservations(identity, MAX_TOTAL_OBSERVATIONS_PER_ENTRY_LINEAGE + 1)

	@Query(
		"SELECT * FROM imported_wifi_observation WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, run_identity, coverage_start_time_ms, " +
			"observed_time_ms, identity LIMIT :limit",
	)
	abstract suspend fun observationsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedWifiObservationEntity>

	@Query(
		"""
		WITH latest_revision AS (
		  SELECT revision.*
		  FROM imported_wifi_entry_revision AS revision
		  WHERE revision.import_revision = (
		    SELECT MAX(latest.import_revision)
		    FROM imported_wifi_entry_revision AS latest
		    WHERE latest.identity = revision.identity
		  )
		), candidate AS (
		  SELECT latest_revision.identity,
		         latest_revision.import_revision,
		         latest_revision.content_checksum,
		         latest_revision.start_time_ms,
		         latest_revision.end_time_ms,
		         latest_revision.received_at_ms,
		         COALESCE((
		           SELECT MAX(run.start_time_ms)
		           FROM imported_wifi_run AS run
		           WHERE run.entry_identity = latest_revision.identity
		             AND run.entry_import_revision = latest_revision.import_revision
		         ), latest_revision.start_time_ms) AS newest_member_start_time_ms,
		         COALESCE((
		           SELECT MAX(run.identity)
		           FROM imported_wifi_run AS run
		           WHERE run.entry_identity = latest_revision.identity
		             AND run.entry_import_revision = latest_revision.import_revision
		             AND run.start_time_ms = (
		               SELECT MAX(latest_run.start_time_ms)
		               FROM imported_wifi_run AS latest_run
		               WHERE latest_run.entry_identity = latest_revision.identity
		                 AND latest_run.entry_import_revision = latest_revision.import_revision
		             )
		         ), latest_revision.identity) AS newest_member_identity
		  FROM latest_revision
		)
		SELECT * FROM candidate
		WHERE :beforeStartTimeMs IS NULL
		   OR newest_member_start_time_ms < :beforeStartTimeMs
		   OR (
		     newest_member_start_time_ms = :beforeStartTimeMs
		     AND newest_member_identity < COALESCE(:beforeIdentity, '')
		   )
		ORDER BY newest_member_start_time_ms DESC, newest_member_identity DESC
		LIMIT :limit
		""",
	)
	abstract suspend fun recentHistoryCandidatePage(
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): List<ImportedWifiHistoryCandidate>

	@Query(
		"""
		WITH latest_revision AS (
		  SELECT revision.*
		  FROM imported_wifi_entry_revision AS revision
		  WHERE revision.import_revision = (
		    SELECT MAX(latest.import_revision)
		    FROM imported_wifi_entry_revision AS latest
		    WHERE latest.identity = revision.identity
		  )
		), candidate AS (
		  SELECT latest_revision.identity,
		         latest_revision.import_revision,
		         latest_revision.content_checksum,
		         latest_revision.start_time_ms,
		         latest_revision.end_time_ms,
		         latest_revision.received_at_ms,
		         COALESCE((
		           SELECT MAX(run.start_time_ms)
		           FROM imported_wifi_run AS run
		           WHERE run.entry_identity = latest_revision.identity
		             AND run.entry_import_revision = latest_revision.import_revision
		         ), latest_revision.start_time_ms) AS newest_member_start_time_ms,
		         COALESCE((
		           SELECT MAX(run.identity)
		           FROM imported_wifi_run AS run
		           WHERE run.entry_identity = latest_revision.identity
		             AND run.entry_import_revision = latest_revision.import_revision
		             AND run.start_time_ms = (
		               SELECT MAX(latest_run.start_time_ms)
		               FROM imported_wifi_run AS latest_run
		               WHERE latest_run.entry_identity = latest_revision.identity
		                 AND latest_run.entry_import_revision = latest_revision.import_revision
		             )
		         ), latest_revision.identity) AS newest_member_identity
		  FROM latest_revision
		  WHERE latest_revision.end_time_ms > :fromInclusiveMs
		    AND latest_revision.start_time_ms < :toExclusiveMs
		)
		SELECT * FROM candidate
		WHERE :beforeStartTimeMs IS NULL
		   OR newest_member_start_time_ms < :beforeStartTimeMs
		   OR (
		     newest_member_start_time_ms = :beforeStartTimeMs
		     AND newest_member_identity < COALESCE(:beforeIdentity, '')
		   )
		ORDER BY newest_member_start_time_ms DESC, newest_member_identity DESC
		LIMIT :limit
		""",
	)
	abstract suspend fun historyCandidateRangePage(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): List<ImportedWifiHistoryCandidate>

	@Query(
		"SELECT revision.identity, revision.import_revision, revision.content_checksum, " +
			"revision.start_time_ms, revision.end_time_ms, revision.received_at_ms, " +
			"COALESCE((SELECT MAX(run.start_time_ms) FROM imported_wifi_run AS run " +
			"WHERE run.entry_identity = revision.identity " +
			"AND run.entry_import_revision = revision.import_revision), revision.start_time_ms) " +
			"AS newest_member_start_time_ms, " +
			"COALESCE((SELECT MAX(run.identity) FROM imported_wifi_run AS run " +
			"WHERE run.entry_identity = revision.identity " +
			"AND run.entry_import_revision = revision.import_revision " +
			"AND run.start_time_ms = (SELECT MAX(latest_run.start_time_ms) " +
			"FROM imported_wifi_run AS latest_run " +
			"WHERE latest_run.entry_identity = revision.identity " +
			"AND latest_run.entry_import_revision = revision.import_revision)), revision.identity) " +
			"AS newest_member_identity " +
			"FROM imported_wifi_entry_revision AS revision " +
			"WHERE revision.identity = :identity AND revision.import_revision = (" +
			"SELECT MAX(latest.import_revision) FROM imported_wifi_entry_revision AS latest " +
			"WHERE latest.identity = revision.identity) LIMIT 1",
	)
	abstract suspend fun latestHistoryCandidate(identity: String): ImportedWifiHistoryCandidate?

	@Query(
		"SELECT DISTINCT identity FROM imported_wifi_entry_revision " +
			"WHERE identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingEntryIdentities(identities: List<String>, limit: Int): List<String>

	@Query(
		"SELECT DISTINCT identity, entry_identity, deletion_scope_digest FROM imported_wifi_run " +
			"WHERE identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingRunIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedWifiRunIdentityOwner>

	@Query(
		"SELECT DISTINCT identity, entry_identity, run_identity FROM imported_wifi_observation " +
			"WHERE identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingObservationIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedWifiObservationIdentityOwner>

	@Query(
		"SELECT DISTINCT deletion_scope_digest, entry_identity, identity FROM imported_wifi_run " +
			"WHERE deletion_scope_digest IN (:digests) LIMIT :limit",
	)
	abstract suspend fun existingRunScopeOwners(
		digests: List<String>,
		limit: Int,
	): List<ImportedWifiRunScopeOwner>

	@Query("SELECT * FROM imported_wifi_entry_deletion WHERE entry_identity IN (:identities)")
	abstract suspend fun entryDeletions(identities: List<String>): List<ImportedWifiEntryDeletionEntity>

	@Query(
		"SELECT * FROM imported_wifi_entry_deletion WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity LIMIT :limit",
	)
	abstract suspend fun entryDeletionsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedWifiEntryDeletionEntity>

	@Query("SELECT * FROM imported_wifi_entry_deletion WHERE entry_identity = :identity")
	abstract suspend fun entryDeletion(identity: String): ImportedWifiEntryDeletionEntity?

	@Query("SELECT * FROM imported_wifi_deletion_generation WHERE run_identity IN (:identities)")
	abstract suspend fun deletionGenerationsByRun(
		identities: List<String>,
	): List<ImportedWifiDeletionGenerationEntity>

	@Query("SELECT * FROM imported_wifi_deletion_generation WHERE entry_identity IN (:identities) LIMIT :limit")
	abstract suspend fun deletionGenerationsByEntry(
		identities: List<String>,
		limit: Int,
	): List<ImportedWifiDeletionGenerationEntity>

	@Query(
		"SELECT * FROM imported_wifi_deletion_generation WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, run_identity LIMIT :limit",
	)
	abstract suspend fun deletionGenerationsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedWifiDeletionGenerationEntity>

	@Query(
		"SELECT * FROM imported_wifi_deletion_generation " +
			"WHERE deletion_scope_digest IN (:digests)",
	)
	abstract suspend fun deletionGenerationsByScope(
		digests: List<String>,
	): List<ImportedWifiDeletionGenerationEntity>

	@Query("SELECT COUNT(*) FROM imported_wifi_entry_revision")
	abstract suspend fun entryRevisionCount(): Long

	@Query("SELECT COUNT(DISTINCT identity) FROM imported_wifi_entry_revision")
	abstract suspend fun distinctEntryCount(): Long

	@Query("SELECT COUNT(*) FROM imported_wifi_receipt")
	abstract suspend fun receiptCount(): Long

	@Query("SELECT COUNT(*) FROM imported_wifi_run")
	abstract suspend fun runCount(): Long

	@Query("SELECT COUNT(*) FROM imported_wifi_run_zone")
	abstract suspend fun runZoneCount(): Long

	@Query("SELECT COUNT(*) FROM imported_wifi_observation")
	abstract suspend fun observationCount(): Long

	@Query("SELECT COUNT(*) FROM imported_wifi_entry_deletion")
	abstract suspend fun entryDeletionCount(): Long

	@Query("SELECT COUNT(*) FROM imported_wifi_deletion_generation")
	abstract suspend fun deletionGenerationCount(): Long

	@Query("SELECT COUNT(*) FROM wifi_selected_deletion_receipt")
	abstract suspend fun selectedDeletionReceiptCount(): Long

	@Query("SELECT COUNT(*) FROM wifi_selected_deletion_protected_identity")
	abstract suspend fun selectedDeletionProtectedIdentityCount(): Long

	/** Local identifiers are returned only to the Wi-Fi importer for in-memory irreversible hashing. */
	@Query("SELECT COUNT(*) FROM logical_tracking_session")
	abstract suspend fun localEntryOwnerCount(): Long

	@Query(
		"SELECT logical_tracking_id FROM logical_tracking_session " +
			"WHERE (:afterIdentity IS NULL OR logical_tracking_id > :afterIdentity) " +
			"ORDER BY logical_tracking_id LIMIT :limit",
	)
	abstract suspend fun localEntryOwnerPage(
		afterIdentity: String?,
		limit: Int,
	): List<String>

	@Query("SELECT COUNT(*) FROM source_service_run")
	abstract suspend fun localRunOwnerCount(): Long

	@Query(
		"SELECT logical_tracking_id, service_run_id FROM source_service_run " +
			"WHERE (:afterIdentity IS NULL OR service_run_id > :afterIdentity) " +
			"ORDER BY service_run_id LIMIT :limit",
	)
	abstract suspend fun localRunOwnerPage(
		afterIdentity: String?,
		limit: Int,
	): List<WifiLocalRunOwner>

	@Query("SELECT COUNT(*) FROM (" + LOCAL_OBSERVATION_OWNER_UNION + ")")
	abstract suspend fun localObservationOwnerCount(): Long

	@Query(
		"SELECT * FROM (" + LOCAL_OBSERVATION_OWNER_UNION + ") " +
			"WHERE (:afterIdentity IS NULL OR logical_fact_id > :afterIdentity) " +
			"ORDER BY logical_fact_id, logical_tracking_id, service_run_id LIMIT :limit",
	)
	abstract suspend fun localObservationOwnerPage(
		afterIdentity: String?,
		limit: Int,
	): List<WifiLocalObservationOwner>

	/** All fence namespaces reserve their opaque identity; this query never grants deletion authority. */
	@Query(
		"SELECT * FROM source_deletion_fence WHERE scope_identity_digest IN (:identities) " +
			"ORDER BY scope_identity_digest, source_kind, purpose, scope_kind LIMIT :limit",
	)
	abstract suspend fun deletionFenceIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<SourceDeletionFenceEntity>

	@Query("SELECT COUNT(*) FROM wifi_capture_deletion_generation")
	abstract suspend fun localDeletionOwnerCount(): Long

	@Query(
		"SELECT logical_tracking_id, service_run_id FROM wifi_capture_deletion_generation " +
			"WHERE (:afterIdentity IS NULL OR service_run_id > :afterIdentity) " +
			"ORDER BY service_run_id LIMIT :limit",
	)
	abstract suspend fun localDeletionOwnerPage(
		afterIdentity: String?,
		limit: Int,
	): List<WifiLocalRunOwner>

	@Query(
		"SELECT * FROM wifi_capture_deletion_generation " +
			"WHERE (:afterLogicalTrackingId IS NULL OR logical_tracking_id > :afterLogicalTrackingId " +
			"OR (logical_tracking_id = :afterLogicalTrackingId AND service_run_id > :afterServiceRunId)) " +
			"ORDER BY logical_tracking_id, service_run_id LIMIT :limit",
	)
	abstract suspend fun localDeletionGenerationPage(
		afterLogicalTrackingId: String?,
		afterServiceRunId: String?,
		limit: Int,
	): List<WifiCaptureDeletionGenerationEntity>

	@Query("SELECT COUNT(*) FROM ski_run_segment WHERE session_id IN (:segmentIds)")
	abstract suspend fun selectedSkiSegmentCount(segmentIds: List<Long>): Long

	@Query("SELECT EXISTS(SELECT 1 FROM pending_signal WHERE session_id IN (:segmentIds) LIMIT 1)")
	abstract suspend fun hasSelectedPendingSignal(segmentIds: List<Long>): Boolean

	@Query("SELECT EXISTS(SELECT 1 FROM quarantined_signal WHERE session_id IN (:segmentIds) LIMIT 1)")
	abstract suspend fun hasSelectedQuarantinedSignal(segmentIds: List<Long>): Boolean

	@Query(
		"DELETE FROM session_segment WHERE logical_tracking_id = :logicalTrackingId " +
			"AND id IN (:segmentIds) AND service_run_id IN (:serviceRunIds)",
	)
	abstract suspend fun deleteSelectedLocalSegments(
		logicalTrackingId: String,
		segmentIds: List<Long>,
		serviceRunIds: List<String>,
	): Int

	@Query("DELETE FROM imported_wifi_receipt")
	abstract fun deleteAllReceipts()

	@Query("DELETE FROM imported_wifi_entry_revision")
	abstract fun deleteAllEntryRevisions()

	@Query("DELETE FROM imported_wifi_entry_deletion")
	abstract fun deleteAllEntryDeletions()

	@Query("DELETE FROM imported_wifi_deletion_generation")
	abstract fun deleteAllDeletionGenerations()

	@Query(
		"DELETE FROM imported_wifi_observation WHERE entry_identity = :identity " +
			"AND aggregate_owner_identity IS NOT NULL",
	)
	abstract suspend fun deleteDependentObservations(identity: String): Int

	@Query("DELETE FROM imported_wifi_observation WHERE entry_identity = :identity")
	abstract suspend fun deleteRemainingObservations(identity: String): Int

	@Query("DELETE FROM imported_wifi_entry_revision WHERE identity = :identity")
	abstract suspend fun deleteEntryRevisions(identity: String): Int

	companion object {
		private const val LOCAL_OBSERVATION_OWNER_UNION =
			"SELECT logical_fact_id, logical_tracking_id, service_run_id FROM wifi_captured_fact_cursor " +
				"UNION SELECT logical_fact_id, logical_tracking_id, service_run_id " +
				"FROM wifi_captured_fact_revision " +
				"UNION SELECT aggregate_owner_logical_fact_id AS logical_fact_id, " +
				"logical_tracking_id, service_run_id FROM wifi_captured_fact_revision " +
				"WHERE aggregate_owner_logical_fact_id IS NOT NULL"

		const val MAX_IMPORTED_ENTRIES = 4_096
		const val MAX_HISTORY_ENTRY_CANDIDATES = 100
		const val MAX_REVISIONS_PER_ENTRY = 16
		const val MAX_RECEIPTS_PER_ENTRY = 256
		const val MAX_RUNS_PER_ENTRY = 64
		const val MAX_ZONES_PER_RUN = 256
		const val MAX_OBSERVATIONS_PER_ENTRY = 4_096
		const val MAX_TOTAL_RUNS_PER_ENTRY_LINEAGE = MAX_REVISIONS_PER_ENTRY * MAX_RUNS_PER_ENTRY
		const val MAX_TOTAL_ZONES_PER_ENTRY_LINEAGE =
			MAX_TOTAL_RUNS_PER_ENTRY_LINEAGE * MAX_ZONES_PER_RUN
		const val MAX_TOTAL_OBSERVATIONS_PER_ENTRY_LINEAGE =
			MAX_REVISIONS_PER_ENTRY * MAX_OBSERVATIONS_PER_ENTRY
		const val MAX_GLOBAL_AUTHORITY_ROWS = 262_144
		const val OWNER_PAGE_SIZE = 256
	}
}

data class ImportedWifiHistoryCandidate(
	val identity: String,
	@ColumnInfo(name = "import_revision") val importRevision: Long,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
	@ColumnInfo(name = "newest_member_start_time_ms") val newestMemberStartTimeMs: Long,
	@ColumnInfo(name = "newest_member_identity") val newestMemberIdentity: String,
)

data class ImportedWifiRunIdentityOwner(
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
)

data class ImportedWifiAuthorityOwner(
	@ColumnInfo(name = "owner_kind") val ownerKind: String,
	@ColumnInfo(name = "protected_identity") val protectedIdentity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String?,
	@ColumnInfo(name = "run_identity") val runIdentity: String?,
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String?,
	@ColumnInfo(name = "source_kind") val sourceKind: Int?,
	val purpose: String?,
	@ColumnInfo(name = "scope_kind") val scopeKind: String?,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long?,
	@ColumnInfo(name = "receipt_origin") val receiptOrigin: String?,
	@ColumnInfo(name = "selection_identity") val selectionIdentity: String?,
) {
	companion object {
		const val ENTRY = "ENTRY"
		const val RUN = "RUN"
		const val OBSERVATION = "OBSERVATION"
		const val DELETION_SCOPE = "DELETION_SCOPE"
		const val ENTRY_DELETION = "ENTRY_DELETION"
		const val RUN_DELETION = "RUN_DELETION"
		const val SOURCE_FENCE = "SOURCE_FENCE"
		const val SELECTED_PROTECTED = "SELECTED_PROTECTED"
	}
}

data class ImportedWifiObservationIdentityOwner(
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
)

data class ImportedWifiRunScopeOwner(
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "identity") val runIdentity: String,
)

data class WifiLocalRunOwner(
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
)

data class WifiLocalObservationOwner(
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
)
