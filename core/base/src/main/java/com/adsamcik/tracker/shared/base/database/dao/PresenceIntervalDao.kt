package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.PresenceInterval

data class CoverageLedgerRow(
	val totalMs: Long,
	val observedMs: Long,
	val inferredMs: Long,
	val unresolvedMs: Long,
)

data class SupportBucketRow(
	val resolutionM: Int,
	val supportMs: Long,
)

@Dao
interface PresenceIntervalDao : BaseDao<PresenceInterval> {
	@Query("SELECT COALESCE(MAX(id), 0) FROM presence_interval")
	suspend fun maxIdAllModels(): Long

	@Query(
		"""
		SELECT * FROM presence_interval
		WHERE model_key = :modelKey
		  AND end_time_ms > :fromMs
		  AND start_time_ms < :toMs
		ORDER BY start_time_ms ASC, id ASC
		""",
	)
	suspend fun getOverlapping(modelKey: String, fromMs: Long, toMs: Long): List<PresenceInterval>

	/** Stable, bounded snapshot page across model versions for diagnostic/research exports. */
	@Query(
		"""
		SELECT * FROM presence_interval
		WHERE id > :afterId AND id <= :throughId
		  AND end_time_ms > :fromMs
		  AND start_time_ms < :toMsExclusive
		ORDER BY id ASC
		LIMIT :limit
		""",
	)
	suspend fun getOverlappingChunk(
		fromMs: Long,
		toMsExclusive: Long,
		afterId: Long,
		throughId: Long,
		limit: Int,
	): List<PresenceInterval>

	@Query(
		"""
		SELECT
			COALESCE(SUM(MAX(0, MIN(end_time_ms, :toMs) - MAX(start_time_ms, :fromMs))), 0) AS totalMs,
			COALESCE(SUM(CASE WHEN resolution_state = 'OBSERVED'
				THEN MAX(0, MIN(end_time_ms, :toMs) - MAX(start_time_ms, :fromMs)) ELSE 0 END), 0) AS observedMs,
			COALESCE(SUM(CASE WHEN resolution_state = 'INFERRED'
				THEN MAX(0, MIN(end_time_ms, :toMs) - MAX(start_time_ms, :fromMs)) ELSE 0 END), 0) AS inferredMs,
			COALESCE(SUM(CASE WHEN resolution_state = 'UNRESOLVED'
				THEN MAX(0, MIN(end_time_ms, :toMs) - MAX(start_time_ms, :fromMs)) ELSE 0 END), 0) AS unresolvedMs
		FROM presence_interval
		WHERE model_key = :modelKey
		  AND end_time_ms > :fromMs
		  AND start_time_ms < :toMs
		""",
	)
	suspend fun getCoverage(modelKey: String, fromMs: Long, toMs: Long): CoverageLedgerRow

	@Query(
		"""
		SELECT CASE
			WHEN effective_r90_m <= 12.5 THEN 25
			WHEN effective_r90_m <= 25.0 THEN 50
			WHEN effective_r90_m <= 50.0 THEN 100
			WHEN effective_r90_m <= 125.0 THEN 250
			WHEN effective_r90_m <= 250.0 THEN 500
			WHEN effective_r90_m <= 500.0 THEN 1000
			ELSE 0 END AS resolutionM,
			SUM(MAX(0, MIN(end_time_ms, :toMs) - MAX(start_time_ms, :fromMs))) AS supportMs
		FROM presence_interval
		WHERE model_key = :modelKey
		  AND resolution_state != 'UNRESOLVED'
		  AND effective_r90_m IS NOT NULL
		  AND end_time_ms > :fromMs
		  AND start_time_ms < :toMs
		GROUP BY resolutionM
		""",
	)
	suspend fun getSupportHistogram(
		modelKey: String,
		fromMs: Long,
		toMs: Long,
	): List<SupportBucketRow>

	/** Delete every old fragment that intersects the rebuilt half-open window. */
	@Query(
		"DELETE FROM presence_interval WHERE model_key = :modelKey AND end_time_ms > :fromMs AND start_time_ms < :toMs",
	)
	suspend fun deleteOverlapping(modelKey: String, fromMs: Long, toMs: Long): Int

	/** Local invariant check used after replacing a materialized window. */
	@Query(
		"""
		SELECT COUNT(*)
		FROM presence_interval a
		JOIN presence_interval b
		  ON a.id < b.id
		 AND a.model_key = b.model_key
		 AND a.end_time_ms > b.start_time_ms
		 AND a.start_time_ms < b.end_time_ms
		WHERE a.model_key = :modelKey
		  AND a.end_time_ms > :fromMs
		  AND a.start_time_ms < :toMs
		""",
	)
	suspend fun countOverlappingPairs(modelKey: String, fromMs: Long, toMs: Long): Int

	@Query("SELECT COALESCE(MAX(id), 0) FROM presence_interval WHERE model_key = :modelKey")
	suspend fun maxId(modelKey: String): Long

	@Query("DELETE FROM presence_interval")
	fun deleteAll()
}
