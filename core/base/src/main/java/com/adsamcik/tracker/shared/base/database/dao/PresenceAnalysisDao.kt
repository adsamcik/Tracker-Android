package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.AnalysisCell
import com.adsamcik.tracker.shared.base.database.data.PresenceCellContribution
import com.adsamcik.tracker.shared.base.database.data.PresenceCompactionBlock
import com.adsamcik.tracker.shared.base.database.data.PresenceCompactionCheckpoint

data class PresenceCellAggregateRow(
	val cellId: String,
	val resolutionM: Int,
	val minLatE7: Int,
	val minLonE7: Int,
	val maxLatE7: Int,
	val maxLonE7: Int,
	val expectedMs: Long,
	val observedMs: Long,
	val inferredMs: Long,
)

@Dao
interface PresenceAnalysisDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertCells(cells: Collection<AnalysisCell>): List<Long>

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertBlock(block: PresenceCompactionBlock): Long

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertContributions(contributions: Collection<PresenceCellContribution>)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun putCheckpoint(checkpoint: PresenceCompactionCheckpoint)

	@Query("SELECT * FROM presence_compaction_checkpoint WHERE pipeline_key = :pipelineKey")
	suspend fun getCheckpoint(pipelineKey: String): PresenceCompactionCheckpoint?

	/** Compare-and-set the observation watermark without masking a concurrently advanced horizon. */
	@Query(
		"""
		UPDATE presence_compaction_checkpoint
		SET source_max_observation_id = :newSourceMaxObservationId,
		    updated_at = :updatedAt
		WHERE pipeline_key = :pipelineKey
		  AND contiguous_compacted_through_ms = :expectedCompactedThroughMs
		  AND source_max_observation_id = :expectedSourceMaxObservationId
		""",
	)
	suspend fun advanceObservationWatermark(
		pipelineKey: String,
		expectedCompactedThroughMs: Long,
		expectedSourceMaxObservationId: Long,
		newSourceMaxObservationId: Long,
		updatedAt: Long,
	): Int

	@Query(
		"""
		SELECT COALESCE(MAX(generation), 0) FROM presence_compaction_block
		WHERE metric_kind = :metricKind AND model_key = :modelKey AND grid_version = :gridVersion
		  AND partition_start_ms = :partitionStartMs
		""",
	)
	suspend fun maxGeneration(
		metricKind: String,
		modelKey: String,
		gridVersion: Int,
		partitionStartMs: Long,
	): Int

	@Query(
		"""
		SELECT EXISTS(
			SELECT 1 FROM presence_compaction_block
			WHERE metric_kind = :metricKind AND model_key = :modelKey AND grid_version = :gridVersion
			  AND partition_start_ms = :partitionStartMs AND status = 'COMMITTED'
		)
		""",
	)
	suspend fun hasCommittedBlock(
		metricKind: String,
		modelKey: String,
		gridVersion: Int,
		partitionStartMs: Long,
	): Boolean

	@Query(
		"""
		SELECT * FROM presence_compaction_block
		WHERE metric_kind = :metricKind AND model_key = :modelKey AND grid_version = :gridVersion
		  AND partition_start_ms = :partitionStartMs AND status = 'COMMITTED'
		LIMIT 1
		""",
	)
	suspend fun getCommittedBlock(
		metricKind: String,
		modelKey: String,
		gridVersion: Int,
		partitionStartMs: Long,
	): PresenceCompactionBlock?

	@Query(
		"""
		SELECT COUNT(*) FROM presence_compaction_block
		WHERE metric_kind = :metricKind AND model_key = :modelKey AND grid_version = :gridVersion
		  AND partition_start_ms = :partitionStartMs AND status = 'COMMITTED'
		""",
	)
	suspend fun countCommittedBlocks(
		metricKind: String,
		modelKey: String,
		gridVersion: Int,
		partitionStartMs: Long,
	): Int

	@Query(
		"""
		UPDATE presence_compaction_block SET status = 'SUPERSEDED'
		WHERE metric_kind = :metricKind AND model_key = :modelKey AND grid_version = :gridVersion
		  AND partition_start_ms = :partitionStartMs AND status = 'COMMITTED'
		""",
	)
	suspend fun supersedeCommitted(
		metricKind: String,
		modelKey: String,
		gridVersion: Int,
		partitionStartMs: Long,
	): Int

	@Query(
		"UPDATE presence_compaction_block SET status = 'COMMITTED', committed_at = :committedAt WHERE id = :blockId AND status = 'STAGING'",
	)
	suspend fun commitBlock(blockId: Long, committedAt: Long): Int

	@Query(
		"""
		SELECT COUNT(DISTINCT ac.cell_id)
		FROM presence_cell_contribution dc
		JOIN presence_compaction_block b ON b.id = dc.block_id
		JOIN analysis_cell ac ON ac.cell_id = dc.cell_id
		WHERE b.metric_kind = :metricKind
		  AND b.model_key = :modelKey AND b.grid_version = :gridVersion AND b.status = 'COMMITTED'
		  AND b.partition_start_ms >= :fromMs AND b.partition_end_ms <= :toMs
		  AND ac.resolution_m = :resolutionM
		  AND ac.max_lat_e7 >= :southE7 AND ac.min_lat_e7 <= :northE7
		  AND ac.max_lon_e7 >= :westE7 AND ac.min_lon_e7 <= :eastE7
		""",
	)
	suspend fun countViewportCells(
		metricKind: String,
		modelKey: String,
		gridVersion: Int,
		resolutionM: Int,
		fromMs: Long,
		toMs: Long,
		southE7: Int,
		northE7: Int,
		westE7: Int,
		eastE7: Int,
	): Int

	@Query(
		"""
		SELECT ac.cell_id AS cellId, ac.resolution_m AS resolutionM,
		       ac.min_lat_e7 AS minLatE7, ac.min_lon_e7 AS minLonE7,
		       ac.max_lat_e7 AS maxLatE7, ac.max_lon_e7 AS maxLonE7,
		       SUM(dc.expected_ms) AS expectedMs,
		       SUM(dc.observed_ms) AS observedMs,
		       SUM(dc.inferred_ms) AS inferredMs
		FROM presence_cell_contribution dc
		JOIN presence_compaction_block b ON b.id = dc.block_id
		JOIN analysis_cell ac ON ac.cell_id = dc.cell_id
		WHERE b.metric_kind = :metricKind
		  AND b.model_key = :modelKey AND b.grid_version = :gridVersion AND b.status = 'COMMITTED'
		  AND b.partition_start_ms >= :fromMs AND b.partition_end_ms <= :toMs
		  AND ac.resolution_m = :resolutionM
		  AND ac.max_lat_e7 >= :southE7 AND ac.min_lat_e7 <= :northE7
		  AND ac.max_lon_e7 >= :westE7 AND ac.min_lon_e7 <= :eastE7
		GROUP BY ac.cell_id
		""",
	)
	suspend fun getViewportCells(
		metricKind: String,
		modelKey: String,
		gridVersion: Int,
		resolutionM: Int,
		fromMs: Long,
		toMs: Long,
		southE7: Int,
		northE7: Int,
		westE7: Int,
		eastE7: Int,
	): List<PresenceCellAggregateRow>

	@Query("DELETE FROM presence_cell_contribution")
	fun deleteAllContributions()

	@Query("DELETE FROM presence_compaction_block")
	fun deleteAllBlocks()

	@Query("DELETE FROM presence_compaction_checkpoint")
	fun deleteAllCheckpoints()

	@Query("DELETE FROM analysis_cell")
	fun deleteAllCells()
}
