package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Stable, versioned analytical grid cell with explicit WGS84 display geometry. */
@Entity(
	tableName = "analysis_cell",
	indices = [
		Index(
			value = ["grid_version", "resolution_m", "x_index", "y_index"],
			name = "idx_analysis_cell_grid_key",
			unique = true,
		),
		Index(
			value = ["grid_version", "resolution_m", "min_lat_e7", "max_lat_e7"],
			name = "idx_analysis_cell_lat_bounds",
		),
	],
)
data class AnalysisCell(
	@PrimaryKey
	@ColumnInfo(name = "cell_id")
	val cellId: String,
	@ColumnInfo(name = "grid_version")
	val gridVersion: Int,
	@ColumnInfo(name = "resolution_m")
	val resolutionM: Int,
	@ColumnInfo(name = "x_index")
	val xIndex: Long,
	@ColumnInfo(name = "y_index")
	val yIndex: Long,
	@ColumnInfo(name = "min_lat_e7")
	val minLatE7: Int,
	@ColumnInfo(name = "min_lon_e7")
	val minLonE7: Int,
	@ColumnInfo(name = "max_lat_e7")
	val maxLatE7: Int,
	@ColumnInfo(name = "max_lon_e7")
	val maxLonE7: Int,
)

/** Immutable observed-presence generation for one closed UTC partition. */
@Entity(
	tableName = "presence_compaction_block",
	indices = [
		Index(
			value = ["metric_kind", "model_key", "grid_version", "partition_start_ms", "generation"],
			name = "idx_presence_block_generation",
			unique = true,
		),
		Index(
			value = ["metric_kind", "model_key", "grid_version", "status", "partition_start_ms", "partition_end_ms"],
			name = "idx_presence_block_query",
		),
	],
)
data class PresenceCompactionBlock(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,
	/** Stable analytical meaning; v1 is presence supported by accepted observations, not dwell. */
	@ColumnInfo(name = "metric_kind")
	val metricKind: String,
	@ColumnInfo(name = "partition_start_ms")
	val partitionStartMs: Long,
	@ColumnInfo(name = "partition_end_ms")
	val partitionEndMs: Long,
	@ColumnInfo(name = "model_key")
	val modelKey: String,
	@ColumnInfo(name = "grid_version")
	val gridVersion: Int,
	val generation: Int,
	/** STAGING, COMMITTED, or SUPERSEDED. */
	val status: String,
	@ColumnInfo(name = "source_max_presence_id")
	val sourceMaxPresenceId: Long,
	@ColumnInfo(name = "tracked_ms")
	val trackedMs: Long,
	@ColumnInfo(name = "spatially_observed_ms")
	val spatiallyObservedMs: Long,
	@ColumnInfo(name = "spatially_inferred_ms")
	val spatiallyInferredMs: Long,
	@ColumnInfo(name = "spatially_unresolved_ms")
	val spatiallyUnresolvedMs: Long,
	@ColumnInfo(name = "created_at")
	val createdAt: Long,
	@ColumnInfo(name = "committed_at")
	val committedAt: Long?,
)

@Entity(
	tableName = "presence_cell_contribution",
	primaryKeys = ["block_id", "cell_id"],
	foreignKeys = [
		ForeignKey(
			entity = PresenceCompactionBlock::class,
			parentColumns = ["id"],
			childColumns = ["block_id"],
			onDelete = ForeignKey.CASCADE,
		),
		ForeignKey(
			entity = AnalysisCell::class,
			parentColumns = ["cell_id"],
			childColumns = ["cell_id"],
			onDelete = ForeignKey.RESTRICT,
		),
	],
	indices = [Index(value = ["cell_id"], name = "idx_presence_contribution_cell")],
)
data class PresenceCellContribution(
	@ColumnInfo(name = "block_id")
	val blockId: Long,
	@ColumnInfo(name = "cell_id")
	val cellId: String,
	@ColumnInfo(name = "expected_ms")
	val expectedMs: Long,
	@ColumnInfo(name = "observed_ms")
	val observedMs: Long,
	@ColumnInfo(name = "inferred_ms")
	val inferredMs: Long,
)

/** Contiguous safe-to-delete watermark for one estimator/grid pipeline. */
@Entity(tableName = "presence_compaction_checkpoint")
data class PresenceCompactionCheckpoint(
	@PrimaryKey
	@ColumnInfo(name = "pipeline_key")
	val pipelineKey: String,
	@ColumnInfo(name = "model_key")
	val modelKey: String,
	@ColumnInfo(name = "grid_version")
	val gridVersion: Int,
	@ColumnInfo(name = "contiguous_compacted_through_ms")
	val contiguousCompactedThroughMs: Long,
	@ColumnInfo(name = "source_max_observation_id")
	val sourceMaxObservationId: Long,
	@ColumnInfo(name = "source_max_presence_id")
	val sourceMaxPresenceId: Long,
	@ColumnInfo(name = "updated_at")
	val updatedAt: Long,
)
