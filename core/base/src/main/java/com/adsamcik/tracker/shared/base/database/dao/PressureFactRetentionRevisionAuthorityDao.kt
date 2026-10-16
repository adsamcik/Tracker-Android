package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.PressureFactRetentionRevisionAuthorityEntity

/**
 * Persists and reads Pressure retention witnesses for exact fact revisions and bounded run
 * lineages.
 */
@Dao
interface PressureFactRetentionRevisionAuthorityDao {
	/**
	 * Inserts [PressureFactRetentionRevisionAuthorityEntity] rows. Existing conflicting rows remain
	 * unchanged. An ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIfAbsent(
		authorities: List<PressureFactRetentionRevisionAuthorityEntity>,
	): List<Long>

	/**
	 * Reads `pressure_fact_retention_revision_authority` rows. Query conditions include
	 * `writer_projection_id = :writerProjectionId`, `writer_projection_version =
	 * :writerProjectionVersion`, `logical_fact_id = :logicalFactId`. Orders by `semantic_revision`.
	 * Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM pressure_fact_retention_revision_authority " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId ORDER BY semantic_revision LIMIT :limit",
	)
	suspend fun forLineage(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
		limit: Int,
	): List<PressureFactRetentionRevisionAuthorityEntity>

	/**
	 * Reads `pressure_fact_retention_revision_authority` rows. Query conditions include
	 * `writer_projection_id = :writerProjectionId`, `writer_projection_version =
	 * :writerProjectionVersion`, `source_event_id = :sourceEventId`, `source_admission_ordinal =
	 * :sourceAdmissionOrdinal`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM pressure_fact_retention_revision_authority " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND source_event_id = :sourceEventId " +
			"AND source_admission_ordinal = :sourceAdmissionOrdinal LIMIT :limit",
	)
	suspend fun byEvidence(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		sourceEventId: String,
		sourceAdmissionOrdinal: Long,
		limit: Int,
	): List<PressureFactRetentionRevisionAuthorityEntity>

	/**
	 * Reads `pressure_fact_retention_revision_authority` rows. Query conditions include
	 * `writer_projection_id = :writerProjectionId`, `writer_projection_version =
	 * :writerProjectionVersion`, `source_event_id = :sourceEventId`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM pressure_fact_retention_revision_authority " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND source_event_id = :sourceEventId LIMIT :limit",
	)
	suspend fun byEventId(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		sourceEventId: String,
		limit: Int,
	): List<PressureFactRetentionRevisionAuthorityEntity>

	/**
	 * Reads `pressure_fact_retention_revision_authority` rows. Query conditions include
	 * `writer_projection_id = :writerProjectionId`, `writer_projection_version =
	 * :writerProjectionVersion`, `source_admission_ordinal = :sourceAdmissionOrdinal`. Returns at
	 * most `limit` rows.
	 */
	@Query(
		"SELECT * FROM pressure_fact_retention_revision_authority " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND source_admission_ordinal = :sourceAdmissionOrdinal LIMIT :limit",
	)
	suspend fun byAdmissionOrdinal(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		sourceAdmissionOrdinal: Long,
		limit: Int,
	): List<PressureFactRetentionRevisionAuthorityEntity>

	/**
	 * Reads `pressure_fact_retention_revision_authority` rows. Query conditions include
	 * `parent.service_run_id IN (:serviceRunIds)`. Orders by `parent.service_run_id,
	 * revision.writer_projection_id, revision.writer_projection_version, revision.logical_fact_id,
	 * revision.semantic_revision`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT revision.* FROM pressure_fact_retention_revision_authority AS revision " +
			"INNER JOIN pressure_fact_retention_authority AS parent " +
			"ON parent.writer_projection_id = revision.writer_projection_id " +
			"AND parent.writer_projection_version = revision.writer_projection_version " +
			"AND parent.logical_fact_id = revision.logical_fact_id " +
			"WHERE parent.service_run_id IN (:serviceRunIds) " +
			"ORDER BY parent.service_run_id, revision.writer_projection_id, " +
			"revision.writer_projection_version, revision.logical_fact_id, " +
			"revision.semantic_revision LIMIT :limit",
	)
	suspend fun forServiceRuns(
		serviceRunIds: List<String>,
		limit: Int,
	): List<PressureFactRetentionRevisionAuthorityEntity>

	/**
	 * Counts rows in `pressure_fact_retention_revision_authority`. Query conditions include
	 * `writer_projection_id = :writerProjectionId`, `writer_projection_version =
	 * :writerProjectionVersion`, `logical_fact_id = :logicalFactId`.
	 */
	@Query(
		"SELECT COUNT(*) FROM pressure_fact_retention_revision_authority " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId",
	)
	suspend fun countForLineage(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
	): Long

	/**
	 * Counts rows in `pressure_fact_retention_revision_authority`. Query conditions include
	 * `parent.logical_tracking_id = :logicalTrackingId`, `parent.service_run_id = :serviceRunId`.
	 */
	@Query(
		"SELECT COUNT(*) FROM pressure_fact_retention_revision_authority AS revision " +
			"INNER JOIN pressure_fact_retention_authority AS parent " +
			"ON parent.writer_projection_id = revision.writer_projection_id " +
			"AND parent.writer_projection_version = revision.writer_projection_version " +
			"AND parent.logical_fact_id = revision.logical_fact_id " +
			"WHERE parent.logical_tracking_id = :logicalTrackingId " +
			"AND parent.service_run_id = :serviceRunId",
	)
	suspend fun countForExactRun(logicalTrackingId: String, serviceRunId: String): Long

	/**
	 * Counts rows in `pressure_fact_retention_revision_authority`. Query conditions include
	 * `revision_parent.logical_tracking_id = :logicalTrackingId`, `revision_parent.service_run_id =
	 * :serviceRunId`, `parent.logical_tracking_id = :logicalTrackingId`, `parent.service_run_id =
	 * :serviceRunId`.
	 */
	@Query(
		"SELECT COUNT(*) AS parent_count, " +
			"COALESCE(SUM(parent.lineage_revision_count), 0) AS expected_revision_count, " +
			"(SELECT COUNT(*) FROM pressure_fact_retention_revision_authority AS revision " +
			"INNER JOIN pressure_fact_retention_authority AS revision_parent " +
			"ON revision_parent.writer_projection_id = revision.writer_projection_id " +
			"AND revision_parent.writer_projection_version = revision.writer_projection_version " +
			"AND revision_parent.logical_fact_id = revision.logical_fact_id " +
			"WHERE revision_parent.logical_tracking_id = :logicalTrackingId " +
			"AND revision_parent.service_run_id = :serviceRunId) AS stored_revision_count " +
			"FROM pressure_fact_retention_authority AS parent " +
			"WHERE parent.logical_tracking_id = :logicalTrackingId " +
			"AND parent.service_run_id = :serviceRunId",
	)
	suspend fun runCardinality(
		logicalTrackingId: String,
		serviceRunId: String,
	): PressureRetentionRunRevisionCardinality

	/**
	 * Reports whether a row exists within the query scope. Query conditions include
	 * `parent.logical_tracking_id = :logicalTrackingId`, `parent.service_run_id = :serviceRunId`.
	 */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM pressure_fact_retention_revision_authority AS revision " +
			"INNER JOIN pressure_fact_retention_authority AS parent " +
			"ON parent.writer_projection_id = revision.writer_projection_id " +
			"AND parent.writer_projection_version = revision.writer_projection_version " +
			"AND parent.logical_fact_id = revision.logical_fact_id " +
			"WHERE parent.logical_tracking_id = :logicalTrackingId " +
			"AND parent.service_run_id = :serviceRunId " +
			"GROUP BY revision.source_event_id HAVING COUNT(*) > 1)",
	)
	suspend fun hasDuplicateEventForExactRun(
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean

	/**
	 * Reports whether a row exists within the query scope. Query conditions include
	 * `parent.logical_tracking_id = :logicalTrackingId`, `parent.service_run_id = :serviceRunId`.
	 */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM pressure_fact_retention_revision_authority AS revision " +
			"INNER JOIN pressure_fact_retention_authority AS parent " +
			"ON parent.writer_projection_id = revision.writer_projection_id " +
			"AND parent.writer_projection_version = revision.writer_projection_version " +
			"AND parent.logical_fact_id = revision.logical_fact_id " +
			"WHERE parent.logical_tracking_id = :logicalTrackingId " +
			"AND parent.service_run_id = :serviceRunId " +
			"GROUP BY revision.source_admission_ordinal HAVING COUNT(*) > 1)",
	)
	suspend fun hasDuplicateAdmissionForExactRun(
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean

	/**
	 * Reports whether a row exists within the query scope. Query conditions include
	 * `revision.writer_projection_id = :writerProjectionId`, `revision.writer_projection_version =
	 * :writerProjectionVersion`.
	 */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM pressure_fact_retention_revision_authority AS revision " +
			"LEFT JOIN pressure_fact_retention_authority AS parent " +
			"ON parent.writer_projection_id = revision.writer_projection_id " +
			"AND parent.writer_projection_version = revision.writer_projection_version " +
			"AND parent.logical_fact_id = revision.logical_fact_id " +
			"WHERE revision.writer_projection_id = :writerProjectionId " +
			"AND revision.writer_projection_version = :writerProjectionVersion " +
			"AND parent.logical_fact_id IS NULL)",
	)
	suspend fun hasOrphanForWriter(
		writerProjectionId: String,
		writerProjectionVersion: Int,
	): Boolean

	/**
	 * Deletes `pressure_fact_retention_revision_authority` rows matching the supplied scope. Query
	 * conditions include `parent.logical_tracking_id = :logicalTrackingId`, `parent.service_run_id =
	 * :serviceRunId`. Returns the affected row count.
	 */
	@Query(
		"DELETE FROM pressure_fact_retention_revision_authority WHERE EXISTS (" +
			"SELECT 1 FROM pressure_fact_retention_authority AS parent " +
			"WHERE parent.writer_projection_id = " +
			"pressure_fact_retention_revision_authority.writer_projection_id " +
			"AND parent.writer_projection_version = " +
			"pressure_fact_retention_revision_authority.writer_projection_version " +
			"AND parent.logical_fact_id = " +
			"pressure_fact_retention_revision_authority.logical_fact_id " +
			"AND parent.logical_tracking_id = :logicalTrackingId " +
			"AND parent.service_run_id = :serviceRunId)",
	)
	fun deleteForExactRun(logicalTrackingId: String, serviceRunId: String): Int

	/**
	 * Deletes `pressure_fact_retention_revision_authority` rows. Returns the affected row count.
	 */
	@Query("DELETE FROM pressure_fact_retention_revision_authority")
	fun deleteAll(): Int

	/**
	 * Counts rows in `pressure_fact_retention_revision_authority`.
	 */
	@Query("SELECT COUNT(*) FROM pressure_fact_retention_revision_authority")
	suspend fun count(): Long
}

/**
 * Run identity and retained revision cardinality used to reject a truncated Pressure authority
 * read.
 */
data class PressureRetentionRunRevisionCardinality(
	@ColumnInfo(name = "parent_count")
	val parentCount: Long,
	@ColumnInfo(name = "expected_revision_count")
	val expectedRevisionCount: Long,
	@ColumnInfo(name = "stored_revision_count")
	val storedRevisionCount: Long,
)
