package com.adsamcik.tracker.tracker.data.collection

import com.adsamcik.tracker.shared.model.Location

/**
 * Immutable, implementation-free view of one tracker collection cycle.
 *
 * The tracker engine copies its Android/Room-backed collection data into this contract before
 * publishing it. Wi-Fi and cell details are deliberately limited to the fields needed by live
 * consumers so hardware identifiers do not cross the tracker API boundary.
 */
data class TrackerCollectionSnapshot(
	val time: Long = 0L,
	val location: Location? = null,
	val activity: TrackerActivitySnapshot? = null,
	val androidModelMslAltitudeM: Float? = null,
	val wifi: TrackerWifiSnapshot? = null,
	val cell: TrackerCellSnapshot? = null,
) {
	init {
		require(androidModelMslAltitudeM == null || androidModelMslAltitudeM.isFinite()) {
			"Android-model MSL altitude must be finite when present"
		}
	}
}

data class TrackerActivitySnapshot(
	val type: TrackerActivityType,
	val group: TrackerActivityGroup,
	val confidence: Int,
)

enum class TrackerActivityType {
	STILL,
	RUNNING,
	ON_FOOT,
	ON_BICYCLE,
	IN_VEHICLE,
	TILTING,
	UNKNOWN,
	WALKING,
}

enum class TrackerActivityGroup {
	STILL,
	ON_FOOT,
	IN_VEHICLE,
	UNKNOWN,
}

data class TrackerWifiSnapshot(
	val time: Long,
	val inRange: List<TrackerWifiAccessPointSnapshot>,
)

/**
 * Privacy-minimized access-point observation.
 *
 * BSSID, capabilities, and radio metadata intentionally stay inside the tracker engine.
 */
data class TrackerWifiAccessPointSnapshot(
	val ssid: String?,
	val level: Int,
)

data class TrackerCellSnapshot(
	val totalCount: Int,
	val registeredCells: List<TrackerCellInfoSnapshot>,
)

data class TrackerCellInfoSnapshot(
	val networkOperator: TrackerNetworkOperatorSnapshot,
	val type: TrackerCellType,
	val dbm: Int,
)

data class TrackerNetworkOperatorSnapshot(
	val mcc: String,
	val mnc: String,
	val name: String?,
)

enum class TrackerCellType {
	UNKNOWN,
	GSM,
	CDMA,
	WCDMA,
	LTE,
	NR,
	NONE,
}
