package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Header row for one user-imported OpenStreetMap region (one .osm.pbf file).
 *
 * The four E7 extent columns are diagnostics only: they retain independent
 * axis extrema observed while parsing so an import can be described in UI or
 * logs. They are deliberately **not** a bounding box or circular interval and
 * no spatial query may consume them. Spatial consumers use the versioned
 * directed bounds on child [OsmWayEntity] rows instead.
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

	/** Lowest observed latitude, retained only for descriptive diagnostics. */
	@ColumnInfo(name = "min_lat_e7") val diagnosticMinLatitudeE7: Int,
	/** Highest observed latitude, retained only for descriptive diagnostics. */
	@ColumnInfo(name = "max_lat_e7") val diagnosticMaxLatitudeE7: Int,
	/**
	 * Aggregate ordered longitude extrema retained for display/diagnostics only.
	 * They are not a spatial interval and must never drive candidate selection.
	 */
	@ColumnInfo(name = "min_lon_e7") val diagnosticMinLongitudeE7: Int,
	@ColumnInfo(name = "max_lon_e7") val diagnosticMaxLongitudeE7: Int,

	/**
	 * Encoding of child [OsmWayEntity] longitude bboxes. Legacy development
	 * imports are deleted on startup rather than being reinterpreted as directed
	 * arcs; all released schemas predate OSM storage.
	 */
	@ColumnInfo(name = "way_bbox_encoding_version", defaultValue = "0")
	val wayBboxEncodingVersion: Int = WAY_BBOX_ENCODING_LEGACY_ORDERED,

	/**
	 * Publication state for this import. Only [STATUS_READY] rows may be used
	 * by road-graph consumers; [STATUS_BUILDING] rows are private staging data.
	 */
	@ColumnInfo(name = "status", defaultValue = "'READY'")
	val status: String = STATUS_READY,

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
) {
	companion object {
		/** Legacy numeric extrema cannot safely be read as directed intervals. */
		const val WAY_BBOX_ENCODING_LEGACY_ORDERED: Int = 0
		/** Child ways persist canonical directed `(start, eastward-end)` endpoints. */
		const val WAY_BBOX_ENCODING_DIRECTED_V1: Int = 1
		const val STATUS_BUILDING: String = "BUILDING"
		const val STATUS_READY: String = "READY"
		const val STATUS_FAILED: String = "FAILED"
	}
}
