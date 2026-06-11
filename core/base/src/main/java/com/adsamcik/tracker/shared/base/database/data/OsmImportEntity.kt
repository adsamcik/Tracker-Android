package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Header row for one user-imported OpenStreetMap region (one .osm.pbf file).
 *
 * The bounding box is stored in E7 (1e-7 degree) integers to match how all other
 * tracker geometry is persisted and to avoid floating-point comparison issues
 * when the importer needs to recognise an "already imported" region.
 *
 * `file_uri` is the SAF URI the user selected. **It is recorded only as a
 * human-readable breadcrumb** for the "re-import this region" UX — the URI's
 * persistable permission is intentionally NOT held across app restarts (privacy
 * + Play-policy compliance). If the URI no longer resolves the user has to pick
 * the file again.
 *
 * OSM data is © OpenStreetMap contributors, licensed under the
 * Open Database License (ODbL 1.0). The presence of a row in this table is the
 * trigger for showing the attribution string in the About / Settings surface.
 */
@Entity(tableName = "osm_import")
data class OsmImportEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,

	/** User-friendly file name shown in the UI, e.g. "Prague.osm.pbf". */
	@ColumnInfo(name = "display_name") val displayName: String,

	/** Raw SAF URI string. Logged only; not held with takePersistableUriPermission. */
	@ColumnInfo(name = "file_uri") val fileUri: String,

	/** Wall-clock import time (epoch ms). */
	@ColumnInfo(name = "imported_at") val importedAt: Long,

	/** Number of driveable ways persisted into [OsmWayEntity]. */
	@ColumnInfo(name = "way_count") val wayCount: Long,

	/** Number of nodes that fed those ways (peak parser memory hint). */
	@ColumnInfo(name = "node_count") val nodeCount: Long,

	@ColumnInfo(name = "min_lat_e7") val minLatE7: Int,
	@ColumnInfo(name = "max_lat_e7") val maxLatE7: Int,
	@ColumnInfo(name = "min_lon_e7") val minLonE7: Int,
	@ColumnInfo(name = "max_lon_e7") val maxLonE7: Int,

	/**
	 * Explicit completion marker for the cell-index rebuild. Set to 1 only
	 * after [OsmWayCellReindexer][com.adsamcik.tracker.osm.reindex.OsmWayCellReindexer]
	 * successfully finishes rebuilding `osm_way_cell` rows for this import.
	 *
	 * If a reindex is interrupted (OOM, force-stop, OS kill), this stays 0 and
	 * the self-healing heuristic re-triggers the rebuild on next launch.
	 */
	@ColumnInfo(name = "cell_index_built", defaultValue = "0")
	val cellIndexBuilt: Int = 0,
)
