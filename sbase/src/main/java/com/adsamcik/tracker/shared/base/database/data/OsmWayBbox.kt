package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo

/**
 * Projection of [OsmWayEntity] used by the background reindexer to rebuild
 * `osm_way_cell` after a cell-size change without re-decoding polylines.
 *
 * Only the columns needed by `OsmGridIndex.cellKeysForBbox` are read, keeping
 * the per-batch memory footprint to ~24 bytes per row regardless of how large
 * the underlying packed polyline blob is.
 */
data class OsmWayBbox(
	@ColumnInfo(name = "id") val id: Long,
	@ColumnInfo(name = "bbox_min_lat_e7") val bboxMinLatE7: Int,
	@ColumnInfo(name = "bbox_max_lat_e7") val bboxMaxLatE7: Int,
	@ColumnInfo(name = "bbox_min_lon_e7") val bboxMinLonE7: Int,
	@ColumnInfo(name = "bbox_max_lon_e7") val bboxMaxLonE7: Int,
)
