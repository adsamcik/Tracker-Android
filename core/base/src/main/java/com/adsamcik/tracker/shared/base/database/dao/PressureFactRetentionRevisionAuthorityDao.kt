package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.PressureFactRetentionRevisionAuthorityEntity

@Dao
interface PressureFactRetentionRevisionAuthorityDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIfAbsent(
		authorities: List<PressureFactRetentionRevisionAuthorityEntity>,
	): List<Long>

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

	@Query("DELETE FROM pressure_fact_retention_revision_authority")
	fun deleteAll(): Int

	@Query("SELECT COUNT(*) FROM pressure_fact_retention_revision_authority")
	suspend fun count(): Long
}

data class PressureRetentionRunRevisionCardinality(
	@ColumnInfo(name = "parent_count")
	val parentCount: Long,
	@ColumnInfo(name = "expected_revision_count")
	val expectedRevisionCount: Long,
	@ColumnInfo(name = "stored_revision_count")
	val storedRevisionCount: Long,
)
