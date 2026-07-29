package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Spatial grid index linking one import-scoped [OsmWayEntity] instance to
 * every coarse grid cell its bounding box overlaps.
 *
 * Cell keys are computed by [com.adsamcik.tracker.osm.io.OsmGridIndex] as
 * `(latE7 / CELL_E7) << 24 | (lonE7 / CELL_E7) & 0xFFFFFF`. The current cell
 * size is `0.01°` (E7: 100_000), roughly 1.1 km at the equator — tight enough
 * that the snap-to-nearest-road query loads a small candidate set even in
 * dense urban areas.
 *
 * One way can appear in many cells when its bbox straddles a grid line. The
 * source of truth for the cell-key encoding is [com.adsamcik.tracker.osm.io.OsmGridIndex];
 * any change to that constant invalidates every row in this table and must be
 * paired with a Room migration that drops the contents plus a reindex pass.
 */
@Entity(
	tableName = "osm_way_cell",
	primaryKeys = ["cell_key", "way_id"],
	foreignKeys = [
		ForeignKey(
			entity = OsmWayEntity::class,
			parentColumns = ["way_instance_id"],
			childColumns = ["way_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(value = ["cell_key"], name = "idx_osm_way_cell_cell"),
		Index(value = ["way_id"], name = "idx_osm_way_cell_way"),
	],
)
data class OsmWayCellEntity(
	@ColumnInfo(name = "cell_key") val cellKey: Long,
	/** Local [OsmWayEntity.wayInstanceId], retained under the legacy column name. */
	@ColumnInfo(name = "way_id") val wayId: Long,
)
