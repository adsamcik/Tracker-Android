package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Raw Wi-Fi observation with optional coordinates.
 * Records each scan result for later spatial analysis.
 */
@Entity(
	tableName = "wifi_observation",
	indices = [
		Index(value = ["time_ms", "id"], name = "idx_wifi_obs_time_id"),
		Index(value = ["bssid", "time_ms"], name = "idx_wifi_obs_bssid_time"),
		Index(value = ["lat_e7", "lon_e7"], name = "idx_wifi_obs_coords"),
		Index(
			value = ["source_signal_id", "source_item_index"],
			unique = true,
			name = "idx_wifi_observation_source_item",
		),
	]
)
data class WifiObservation(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/**
	 * Observation timestamp in milliseconds (wall clock time).
	 */
	@ColumnInfo(name = "time_ms")
	val timeMs: Long,

	/**
	 * Wi-Fi access point BSSID (MAC address).
	 */
	val bssid: String,

	/**
	 * SSID (network name). May be empty or "<unknown ssid>".
	 */
	val ssid: String,

	/**
	 * Capability string (security/protocol info).
	 */
	val capabilities: String,

	/**
	 * Frequency in MHz.
	 */
	val frequency: Int,

	/**
	 * Signal level in dBm.
	 */
	val level: Int,

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

	/** Stable pending-signal identity for this fan-out item. */
	@ColumnInfo(name = "source_signal_id")
	val sourceSignalId: String? = null,

	/** Zero-based item position within the source Wi-Fi scan. */
	@ColumnInfo(name = "source_item_index")
	val sourceItemIndex: Int? = null,

	@Embedded
	val observationStamp: ObservationStampColumns = ObservationStampColumns(),
)
