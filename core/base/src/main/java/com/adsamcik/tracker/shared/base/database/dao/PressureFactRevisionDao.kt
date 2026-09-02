package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity

/** Deliberately narrow storage boundary for the dormant Pressure fact writer. */
@Dao
interface PressureFactRevisionDao {
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
	suspend fun latest(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
	): PressureFactRevisionEntity?

	@Query("SELECT COUNT(*) FROM pressure_fact_revision")
	suspend fun count(): Long

	/** Full collected-data clear only; no scoped Pressure deletion API is authorized here. */
	@Query("DELETE FROM pressure_fact_revision")
	fun deleteAll()
}
