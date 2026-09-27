package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.PressureFactRetentionAuthorityEntity

@Dao
interface PressureFactRetentionAuthorityDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIfAbsent(authority: PressureFactRetentionAuthorityEntity): Long

	@Query(
		"SELECT * FROM pressure_fact_retention_authority " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId LIMIT 1",
	)
	suspend fun get(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
	): PressureFactRetentionAuthorityEntity?

	@Query(
		"SELECT * FROM pressure_fact_retention_authority " +
			"WHERE service_run_id IN (:serviceRunIds) " +
			"ORDER BY service_run_id, writer_projection_id, writer_projection_version, " +
			"logical_fact_id LIMIT :limit",
	)
	suspend fun forServiceRuns(
		serviceRunIds: List<String>,
		limit: Int,
	): List<PressureFactRetentionAuthorityEntity>

	@Query(
		"SELECT * FROM pressure_fact_retention_authority " +
			"WHERE logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId " +
			"ORDER BY writer_projection_id, writer_projection_version, logical_fact_id " +
			"LIMIT :limit",
	)
	suspend fun forExactRun(
		logicalTrackingId: String,
		serviceRunId: String,
		limit: Int,
	): List<PressureFactRetentionAuthorityEntity>

	@Query(
		"SELECT * FROM pressure_fact_retention_authority " +
			"WHERE logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId " +
			"AND (writer_projection_id > :afterWriterProjectionId " +
			"OR (writer_projection_id = :afterWriterProjectionId " +
			"AND writer_projection_version > :afterWriterProjectionVersion) " +
			"OR (writer_projection_id = :afterWriterProjectionId " +
			"AND writer_projection_version = :afterWriterProjectionVersion " +
			"AND logical_fact_id > :afterLogicalFactId)) " +
			"ORDER BY writer_projection_id, writer_projection_version, logical_fact_id " +
			"LIMIT :limit",
	)
	suspend fun pageForExactRunAfter(
		logicalTrackingId: String,
		serviceRunId: String,
		afterWriterProjectionId: String,
		afterWriterProjectionVersion: Int,
		afterLogicalFactId: String,
		limit: Int,
	): List<PressureFactRetentionAuthorityEntity>

	@Query(
		"SELECT * FROM pressure_fact_retention_authority " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"ORDER BY logical_fact_id LIMIT :limit",
	)
	suspend fun firstPageForWriter(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		limit: Int,
	): List<PressureFactRetentionAuthorityEntity>

	@Query(
		"SELECT * FROM pressure_fact_retention_authority " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id > :afterLogicalFactId " +
			"ORDER BY logical_fact_id LIMIT :limit",
	)
	suspend fun pageForWriterAfter(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		afterLogicalFactId: String,
		limit: Int,
	): List<PressureFactRetentionAuthorityEntity>

	@Query("SELECT COUNT(*) FROM pressure_fact_retention_authority")
	suspend fun count(): Long

	@Query(
		"SELECT COUNT(*) FROM pressure_fact_retention_authority " +
			"WHERE logical_tracking_id = :logicalTrackingId AND service_run_id = :serviceRunId",
	)
	suspend fun countForExactRun(logicalTrackingId: String, serviceRunId: String): Long

	@Query(
		"SELECT EXISTS(SELECT 1 FROM pressure_fact_retention_authority AS retained " +
			"INNER JOIN pressure_fact_revision AS fact " +
			"ON fact.writer_projection_id = retained.writer_projection_id " +
			"AND fact.writer_projection_version = retained.writer_projection_version " +
			"AND fact.logical_fact_id = retained.logical_fact_id " +
			"WHERE retained.logical_tracking_id = :logicalTrackingId " +
			"AND retained.service_run_id = :serviceRunId LIMIT 1)",
	)
	suspend fun hasLiveFactCollisionForExactRun(
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean

	@Query(
		"SELECT EXISTS(SELECT 1 FROM pressure_fact_retention_revision_authority AS retained_revision " +
			"INNER JOIN pressure_fact_retention_authority AS retained " +
			"ON retained.writer_projection_id = retained_revision.writer_projection_id " +
			"AND retained.writer_projection_version = retained_revision.writer_projection_version " +
			"AND retained.logical_fact_id = retained_revision.logical_fact_id " +
			"INNER JOIN pressure_fact_revision AS fact " +
			"ON fact.writer_projection_id = retained_revision.writer_projection_id " +
			"AND fact.writer_projection_version = retained_revision.writer_projection_version " +
			"AND fact.source_event_id = retained_revision.source_event_id " +
			"WHERE retained.logical_tracking_id = :logicalTrackingId " +
			"AND retained.service_run_id = :serviceRunId LIMIT 1)",
	)
	suspend fun hasLiveEventCollisionForExactRun(
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean

	@Query(
		"SELECT EXISTS(SELECT 1 FROM pressure_fact_retention_revision_authority AS retained_revision " +
			"INNER JOIN pressure_fact_retention_authority AS retained " +
			"ON retained.writer_projection_id = retained_revision.writer_projection_id " +
			"AND retained.writer_projection_version = retained_revision.writer_projection_version " +
			"AND retained.logical_fact_id = retained_revision.logical_fact_id " +
			"INNER JOIN pressure_fact_revision AS fact " +
			"ON fact.writer_projection_id = retained_revision.writer_projection_id " +
			"AND fact.writer_projection_version = retained_revision.writer_projection_version " +
			"AND fact.source_admission_ordinal = retained_revision.source_admission_ordinal " +
			"WHERE retained.logical_tracking_id = :logicalTrackingId " +
			"AND retained.service_run_id = :serviceRunId LIMIT 1)",
	)
	suspend fun hasLiveAdmissionCollisionForExactRun(
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean

	@Query(
		"DELETE FROM pressure_fact_retention_authority " +
			"WHERE logical_tracking_id = :logicalTrackingId AND service_run_id = :serviceRunId",
	)
	fun deleteExactRun(logicalTrackingId: String, serviceRunId: String): Int

	@Query("DELETE FROM pressure_fact_retention_authority")
	fun deleteAll(): Int
}
