package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Spatial grid index linking an [OsmWayEntity] to every coarse grid cell its
 * bounding box overlaps.
 *
 * Phase 2a uses a simple square-degree grid keyed as
 * `(latE7 / 800_000) << 24 | (lonE7 / 800_000) & 0xFFFFFF` — i.e. cells of
 * roughly 0.08° (~9 km at the equator). Coarser than S2 level 14 but adequate
 * for snap-to-nearest-road queries with a tolerance under 100 m, and it avoids
 * pulling in an S2 dependency that the rest of the tracker doesn't already use.
 *
 * One way can appear in many cells when its bbox straddles a grid line.
 */
@Entity(
	tableName = "osm_way_cell",
	primaryKeys = ["cell_key", "way_id"],
	foreignKeys = [
		ForeignKey(
			entity = OsmWayEntity::class,
			parentColumns = ["id"],
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
	@ColumnInfo(name = "way_id") val wayId: Long,
)
