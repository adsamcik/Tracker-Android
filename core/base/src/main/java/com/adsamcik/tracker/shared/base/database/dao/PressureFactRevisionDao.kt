package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity

/** Deliberately narrow storage boundary for the dormant Pressure fact writer. */
@Dao
interface PressureFactRevisionDao {
	/** Appends one immutable revision, returning its row id or `-1` for an existing identity. */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insert(entity: PressureFactRevisionEntity): Long

	/** Returns a row only when every replay identity resolves to that exact stored revision. */
	@Query(
		"SELECT * FROM pressure_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId AND semantic_revision = :semanticRevision " +
			"AND mutation_id = :mutationId AND source_admission_ordinal = :sourceAdmissionOrdinal " +
			"LIMIT 1",
	)
	suspend fun replay(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
		semanticRevision: Long,
		mutationId: String,
		sourceAdmissionOrdinal: Long,
	): PressureFactRevisionEntity?

	@Query(
		"SELECT * FROM pressure_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId ORDER BY semantic_revision DESC LIMIT 1",
	)
	/** Returns the latest semantic revision for one exact Pressure writer-owned fact. */
	suspend fun latest(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
	): PressureFactRevisionEntity?

	/** Returns the first deterministic page attributed to one exact run scope. */
	@Query(
		"SELECT * FROM pressure_fact_revision WHERE service_run_id = :serviceRunId " +
			"AND logical_tracking_id = :logicalTrackingId " +
			"ORDER BY writer_projection_id, writer_projection_version, logical_fact_id, " +
			"semantic_revision LIMIT :limit",
	)
	suspend fun firstExactServiceRunPage(
		logicalTrackingId: String,
		serviceRunId: String,
		limit: Int,
	): List<PressureFactRevisionEntity>

	/** Seeks after an exact primary-key cursor without offset or per-row fan-out. */
	@Query(
		"SELECT * FROM pressure_fact_revision WHERE service_run_id = :serviceRunId " +
			"AND logical_tracking_id = :logicalTrackingId " +
			"AND (writer_projection_id, writer_projection_version, logical_fact_id, semantic_revision) " +
			"> (:afterWriterId, :afterWriterVersion, :afterLogicalFactId, :afterSemanticRevision) " +
			"ORDER BY writer_projection_id, writer_projection_version, logical_fact_id, " +
			"semantic_revision LIMIT :limit",
	)
	suspend fun exactServiceRunPageAfter(
		logicalTrackingId: String,
		serviceRunId: String,
		afterWriterId: String,
		afterWriterVersion: Int,
		afterLogicalFactId: String,
		afterSemanticRevision: Long,
		limit: Int,
	): List<PressureFactRevisionEntity>

	/** Detects rows that name the selected service run but contradict its logical-session owner. */
	@Query(
		"SELECT (EXISTS(SELECT 1 FROM pressure_fact_revision " +
			"WHERE service_run_id = :serviceRunId AND logical_tracking_id < :logicalTrackingId LIMIT 1) " +
			"OR EXISTS(SELECT 1 FROM pressure_fact_revision " +
			"WHERE service_run_id = :serviceRunId AND logical_tracking_id > :logicalTrackingId LIMIT 1))",
	)
	suspend fun hasServiceRunScopeMismatch(
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean

	/**
	 * True when a fact identity selected through this exact scope has any revision outside it.
	 *
	 * The correlated lookup avoids an unbounded identity list while proving that corrections cannot
	 * escape the logical-session/service-run deletion boundary.
	 */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM pressure_fact_revision AS selected " +
			"INNER JOIN pressure_fact_revision AS correction " +
			"ON correction.writer_projection_id = selected.writer_projection_id " +
			"AND correction.writer_projection_version = selected.writer_projection_version " +
			"AND correction.logical_fact_id = selected.logical_fact_id " +
			"WHERE selected.logical_tracking_id = :logicalTrackingId " +
			"AND selected.service_run_id = :serviceRunId " +
			"AND (correction.logical_tracking_id != :logicalTrackingId " +
			"OR correction.service_run_id != :serviceRunId) LIMIT 1)",
	)
	suspend fun hasCrossScopeRevisions(
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean

	/** Deletes one validated primary-key page through its exact final cursor. */
	@Query(
		"DELETE FROM pressure_fact_revision WHERE logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId AND writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND (logical_fact_id, semantic_revision) " +
			"<= (:throughLogicalFactId, :throughSemanticRevision)",
	)
	suspend fun deleteExactServiceRunPageThrough(
		logicalTrackingId: String,
		serviceRunId: String,
		writerProjectionId: String,
		writerProjectionVersion: Int,
		throughLogicalFactId: String,
		throughSemanticRevision: Long,
	): Int

	/** Counts all retained Pressure fact revisions across writer-owned session scopes. */
	@Query("SELECT COUNT(*) FROM pressure_fact_revision")
	suspend fun count(): Long

	/** Full collected-data clear only; no scoped Pressure deletion API is authorized here. */
	@Query("DELETE FROM pressure_fact_revision")
	fun deleteAll()
}
