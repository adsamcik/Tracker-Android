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
		Index(value = ["lat_e7", "lon_e7"], name = "idx_cell_sample_coords")
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
	val createdAt: Long
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
	DIRECT
}
