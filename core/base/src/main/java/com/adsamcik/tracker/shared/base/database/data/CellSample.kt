package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Raw cell tower observation with optional coordinates.
 * Supports enrichment workflow where coordinates are back-filled later.
 */
@Entity(
	tableName = "cell_sample",
	indices = [
		Index(value = ["time_ms"], name = "idx_cell_sample_time"),
		Index(value = ["cell_id"], name = "idx_cell_sample_cell_id"),
		Index(value = ["lat_e7", "lon_e7"], name = "idx_cell_sample_coords"),
		Index(
			value = ["source_signal_id", "source_item_index"],
			unique = true,
			name = "idx_cell_sample_source_item",
		),
	]
)
data class CellSample(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/**
	 * Observation timestamp in milliseconds (wall clock time).
	 */
	@ColumnInfo(name = "time_ms")
	val timeMs: Long,

	/**
	 * Cell tower ID (CID). Long to support 5G NR NCI values.
	 */
	@ColumnInfo(name = "cell_id")
	val cellId: Long,

	/**
	 * Location Area Code (LAC).
	 */
	val lac: Int,

	/**
	 * Mobile Country Code (MCC).
	 */
	val mcc: Int,

	/**
	 * Mobile Network Code (MNC).
	 */
	val mnc: Int,

	/**
	 * Network type (e.g., LTE, UMTS, GSM).
	 */
	@ColumnInfo(name = "network_type")
	val networkType: Int,

	/**
	 * Signal strength (ASU or dBm, depending on network type).
	 */
	@ColumnInfo(name = "signal_strength")
	val signalStrength: Int,

	/**
	 * Latitude in E7 format. Null if location unknown at capture time.
	 */
	@ColumnInfo(name = "lat_e7")
	val latE7: Int?,

	/**
	 * Longitude in E7 format. Null if location unknown at capture time.
	 */
	@ColumnInfo(name = "lon_e7")
	val lonE7: Int?,

	/**
	 * Provenance indicates coordinate source.
	 */
	val provenance: CoordinateProvenance,

	/**
	 * Row creation timestamp (for auditing/debugging).
	 */
	@ColumnInfo(name = "created_at")
	val createdAt: Long,

	/**
	 * Altitude retained from the 2024.1 cell observation.
	 */
	@ColumnInfo(name = "legacy_alt_m")
	val legacyAltM: Double? = null,

	/** Original text MCC retained before conversion to the modern numeric field. */
	@ColumnInfo(name = "legacy_mcc")
	val legacyMcc: String? = null,

	/** Original text MNC retained before conversion to the modern numeric field. */
	@ColumnInfo(name = "legacy_mnc")
	val legacyMnc: String? = null,

	/** Original primary key from cell_location. */
	@ColumnInfo(name = "legacy_source_id")
	val legacySourceId: Long? = null,

	/** Exact latitude retained before E7 conversion. */
	@ColumnInfo(name = "legacy_lat")
	val legacyLat: Double? = null,

	/** Exact longitude retained before E7 conversion. */
	@ColumnInfo(name = "legacy_lon")
	val legacyLon: Double? = null,

	/** Stable pending-signal identity for this fan-out item. */
	@ColumnInfo(name = "source_signal_id")
	val sourceSignalId: String? = null,

	/** Zero-based item position within the source cell scan. */
	@ColumnInfo(name = "source_item_index")
	val sourceItemIndex: Int? = null,
)

/**
 * Tracks how coordinates were obtained for a record.
 */
enum class CoordinateProvenance {
	/** Coordinates unknown (null lat/lon) */
	UNKNOWN,
	/** Enriched from nearest location sample */
	NEAREST_LOCATION,
	/** Interpolated from surrounding location samples */
	INTERPOLATED,
	/** Derived from dwell cluster centroid */
	DWELL_CENTER,
	/** Captured with synchronized GPS */
	DIRECT,
	/** Migrated from the 2024.1 aggregate Wi-Fi or cell tables. */
	LEGACY_MIGRATION
}
