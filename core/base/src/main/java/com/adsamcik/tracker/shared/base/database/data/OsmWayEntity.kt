package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A driveable OSM way instance parsed from one immutable [OsmImportEntity]
 * generation.
 *
 * The upstream OSM way id is not a database primary key. The same OSM element
 * can appear in more than one retained region, and each import must retain its
 * own immutable copy until publication precedence selects an effective copy.
 * [wayInstanceId] is the local physical key; `(import_id, osm_way_id)` is the
 * logical identity. Cell rows reference [wayInstanceId], never the upstream
 * OSM id.
 *
 * Geometry is stored inline as a packed delta-encoded polyline so we never need
 * to keep a separate node table. The wire format is:
 *
 * ```
 *   count       : unsigned varint (number of points)
 *   lat0_e7     : signed varint  (absolute first latitude in 1e-7 degrees)
 *   lon0_e7     : signed varint  (absolute first longitude in 1e-7 degrees)
 *   d_lat_e7[i] : signed varint  (delta to previous lat, zigzag-encoded)
 *   d_lon_e7[i] : signed varint  (delta to previous lon, zigzag-encoded)
 * ```
 *
 * On a typical urban road, varint deltas compress to ~1-2 bytes per coord pair,
 * which keeps a city-region road graph under ~30 MB at rest.
 *
 * `maxspeed_kmh` is the resolved limit:
 *  - if the OSM `maxspeed=*` tag was present and parseable, `maxspeed_explicit = 1`
 *  - otherwise the value comes from the road-class default table
 *    (`OsmRoadClassDefaults`) and `maxspeed_explicit = 0`.
 *
 * Latitude bounds are ordered E7 extrema. Longitude bounds are a directed
 * circular interval: `bbox_min_lon_e7` is the canonical start and
 * `bbox_max_lon_e7` is the eastward end. Thus `start > end` explicitly means
 * an antimeridian crossing; consumers must not reinterpret a wide ordered
 * range as a complement.
 */
@Entity(
	tableName = "osm_way",
	foreignKeys = [
		ForeignKey(
			entity = OsmImportEntity::class,
			parentColumns = ["id"],
			childColumns = ["import_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(value = ["import_id"], name = "idx_osm_way_import"),
		Index(
			value = ["import_id", "osm_way_id"],
			unique = true,
			name = "idx_osm_way_import_osm_id",
		),
		Index(value = ["osm_way_id"], name = "idx_osm_way_osm_id"),
	],
)
data class OsmWayEntity(
	/**
	 * Upstream OSM way id. It remains named [id] for source compatibility with
	 * existing read-only matcher and test consumers; it is persisted as
	 * `osm_way_id` and is deliberately not the physical key.
	 */
	@ColumnInfo(name = "osm_way_id") val id: Long,

	/**
	 * Surrogate key for this import-scoped copy of [id]. New safe-intake code
	 * must pass `0` (or use [asNewImportScopedInstance]) and retain the id
	 * returned by [com.adsamcik.tracker.shared.base.database.dao.OsmWayDao.insertAll]
	 * when creating [OsmWayCellEntity] rows.
	 *
	 * The default mirrors [id] only so the centrally disabled legacy importer
	 * remains source-compatible while it is characterized. Its `ABORT` insert
	 * policy means an overlapping legacy import fails instead of replacing a
	 * READY region. It must not be used by the future multi-region coordinator.
	 */
	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "way_instance_id") val wayInstanceId: Long = id,

	@ColumnInfo(name = "import_id") val importId: Long,

	/** Optional upstream element revision used for READY-overlap precedence. */
	@ColumnInfo(name = "osm_version") val osmVersion: Int? = null,

	/** OSM `name=*` tag, or null. */
	val name: String?,

	/**
	 * OSM `highway=*` tag value. Constrained to driveable values by the
	 * importer; see `com.adsamcik.tracker.osm.OsmRoadClass`.
	 */
	@ColumnInfo(name = "road_class") val roadClass: String,

	/** Resolved speed limit in km/h. */
	@ColumnInfo(name = "maxspeed_kmh") val maxspeedKmh: Int,

	/** 1 if [maxspeedKmh] came from an OSM tag, 0 if it came from road-class defaults. */
	@ColumnInfo(name = "maxspeed_explicit") val maxspeedExplicit: Int,

	@ColumnInfo(name = "is_oneway") val isOneway: Int,

	/** Packed delta-encoded polyline; see class doc for layout. */
	@ColumnInfo(name = "geom_polyline_e7", typeAffinity = ColumnInfo.BLOB)
	val geomPolylineE7: ByteArray,

	@ColumnInfo(name = "bbox_min_lat_e7") val bboxMinLatE7: Int,
	@ColumnInfo(name = "bbox_max_lat_e7") val bboxMaxLatE7: Int,
	/** Canonical directed longitude-interval start (legacy column name retained). */
	@ColumnInfo(name = "bbox_min_lon_e7") val bboxMinLonE7: Int,
	/** Canonical directed longitude-interval eastward end (legacy column name retained). */
	@ColumnInfo(name = "bbox_max_lon_e7") val bboxMaxLonE7: Int,
) {
	/**
	 * Converts a parsed legacy-shaped row into a new import-scoped instance.
	 * The future safe intake coordinator must call this before inserting each
	 * way and use the returned surrogate ids for its cell rows in the same
	 * database transaction.
	 */
	fun asNewImportScopedInstance(): OsmWayEntity = copy(wayInstanceId = 0L)

	// Custom equals/hashCode because Kotlin's generated ones don't handle ByteArray sanely.
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is OsmWayEntity) return false
		if (id != other.id) return false
		if (wayInstanceId != other.wayInstanceId) return false
		if (importId != other.importId) return false
		if (osmVersion != other.osmVersion) return false
		if (name != other.name) return false
		if (roadClass != other.roadClass) return false
		if (maxspeedKmh != other.maxspeedKmh) return false
		if (maxspeedExplicit != other.maxspeedExplicit) return false
		if (isOneway != other.isOneway) return false
		if (!geomPolylineE7.contentEquals(other.geomPolylineE7)) return false
		if (bboxMinLatE7 != other.bboxMinLatE7) return false
		if (bboxMaxLatE7 != other.bboxMaxLatE7) return false
		if (bboxMinLonE7 != other.bboxMinLonE7) return false
		if (bboxMaxLonE7 != other.bboxMaxLonE7) return false
		return true
	}

	override fun hashCode(): Int {
		var result = id.hashCode()
		result = 31 * result + wayInstanceId.hashCode()
		result = 31 * result + importId.hashCode()
		result = 31 * result + (osmVersion ?: 0)
		result = 31 * result + (name?.hashCode() ?: 0)
		result = 31 * result + roadClass.hashCode()
		result = 31 * result + maxspeedKmh
		result = 31 * result + maxspeedExplicit
		result = 31 * result + isOneway
		result = 31 * result + geomPolylineE7.contentHashCode()
		result = 31 * result + bboxMinLatE7
		result = 31 * result + bboxMaxLatE7
		result = 31 * result + bboxMinLonE7
		result = 31 * result + bboxMaxLonE7
		return result
	}
}
