package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Raw Wi-Fi observation with optional coordinates.
 * Records each scan result for later spatial analysis.
 */
@Entity(
	tableName = "wifi_observation",
	indices = [
		Index(value = ["time_ms"], name = "idx_wifi_obs_time"),
		Index(value = ["bssid"], name = "idx_wifi_obs_bssid"),
		Index(value = ["lat_e7", "lon_e7"], name = "idx_wifi_obs_coords")
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
	val createdAt: Long
)
