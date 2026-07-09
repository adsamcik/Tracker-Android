package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo

/**
 * Aggregate totals across the whole cell_sample table.
 * A distinct tower is a unique (mcc, mnc, cell_id) combination.
 */
data class CellSampleTotals(
	@ColumnInfo(name = "total_samples")
	val totalSamples: Long,
	@ColumnInfo(name = "distinct_towers")
	val distinctTowers: Long,
)

/**
 * Per radio-technology aggregate row. [networkType] is a [CellType] ordinal (as persisted by the
 * tracking pipeline). [avgAsu] is the mean signal strength in ASU over samples with a plausible
 * reading; callers normalize it against the technology's ASU range for a comparable quality.
 */
data class NetworkTypeSignalRow(
	@ColumnInfo(name = "network_type")
	val networkType: Int,
	@ColumnInfo(name = "sample_count")
	val sampleCount: Long,
	@ColumnInfo(name = "avg_asu")
	val avgAsu: Double,
	@ColumnInfo(name = "distinct_cells")
	val distinctCells: Long,
)

/**
 * A single frequently-seen cell tower, keyed by (mcc, mnc, cell_id).
 * [avgAsu] is the mean signal strength in ASU over samples with a plausible reading.
 */
data class TopCellTowerRow(
	@ColumnInfo(name = "cell_id")
	val cellId: Long,
	val mcc: Int,
	val mnc: Int,
	@ColumnInfo(name = "network_type")
	val networkType: Int,
	@ColumnInfo(name = "sample_count")
	val sampleCount: Long,
	@ColumnInfo(name = "avg_asu")
	val avgAsu: Double,
)
