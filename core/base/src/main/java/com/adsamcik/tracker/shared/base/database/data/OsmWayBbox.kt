package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo

/**
 * Projection of [OsmWayEntity] used by the background reindexer to rebuild
 * `osm_way_cell` after a cell-size change without re-decoding polylines.
 *
 * Only the columns needed by `OsmGridIndex.cellCoverageForBbox` are read, keeping
 * the per-batch memory footprint to ~24 bytes per row regardless of how large
 * the underlying packed polyline blob is.
 * [OsmWayDao][com.adsamcik.tracker.shared.base.database.dao.OsmWayDao] joins
 * this projection to a published import with the directed-bbox producer
 * version, so legacy numeric extrema can never arrive here as circular
 * endpoints.
 */
data class OsmWayBbox(
	@ColumnInfo(name = "id") val id: Long,
	@ColumnInfo(name = "bbox_min_lat_e7") val bboxMinLatE7: Int,
	@ColumnInfo(name = "bbox_max_lat_e7") val bboxMaxLatE7: Int,
	/** Directed circular-longitude start; the legacy column name is retained. */
	@ColumnInfo(name = "bbox_min_lon_e7") val bboxMinLonE7: Int,
	/** Directed circular-longitude eastward end; the legacy column name is retained. */
	@ColumnInfo(name = "bbox_max_lon_e7") val bboxMaxLonE7: Int,
)
