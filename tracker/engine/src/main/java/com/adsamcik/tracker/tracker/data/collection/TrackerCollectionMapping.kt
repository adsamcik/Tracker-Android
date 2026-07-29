package com.adsamcik.tracker.tracker.data.collection

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.CellData
import com.adsamcik.tracker.shared.base.data.CellInfo
import com.adsamcik.tracker.shared.base.data.CellType
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.data.NetworkOperator
import com.adsamcik.tracker.shared.base.data.WifiData
import com.adsamcik.tracker.shared.base.data.WifiInfo
import com.adsamcik.tracker.shared.base.data.androidModelMslAltitudeM
import com.adsamcik.tracker.shared.base.mapper.toModel

/**
 * Copies the legacy Android collection object into the tracker API's implementation-free view.
 *
 * Keeping this mapper in the engine makes the privacy reduction explicit: stable Wi-Fi hardware
 * identifiers and cell identifiers never enter [TrackerCollectionSnapshot].
 */
internal fun CollectionData.toSnapshot(): TrackerCollectionSnapshot = TrackerCollectionSnapshot(
	time = time,
	location = location?.toModel(),
	activity = activity?.toSnapshot(),
	androidModelMslAltitudeM = androidModelMslAltitudeM,
	wifi = wifi?.toSnapshot(),
	cell = cell?.toSnapshot(),
)

private fun ActivityInfo.toSnapshot(): TrackerActivitySnapshot {
	val resolvedType = DetectedActivity.entries.firstOrNull { it.value == activityType }
		?: DetectedActivity.UNKNOWN
	return TrackerActivitySnapshot(
		type = resolvedType.toSnapshot(),
		group = resolvedType.groupedActivity.toSnapshot(),
		confidence = confidence,
	)
}

private fun DetectedActivity.toSnapshot(): TrackerActivityType = when (this) {
	DetectedActivity.STILL -> TrackerActivityType.STILL
	DetectedActivity.RUNNING -> TrackerActivityType.RUNNING
	DetectedActivity.ON_FOOT -> TrackerActivityType.ON_FOOT
	DetectedActivity.ON_BICYCLE -> TrackerActivityType.ON_BICYCLE
	DetectedActivity.IN_VEHICLE -> TrackerActivityType.IN_VEHICLE
	DetectedActivity.TILTING -> TrackerActivityType.TILTING
	DetectedActivity.UNKNOWN -> TrackerActivityType.UNKNOWN
	DetectedActivity.WALKING -> TrackerActivityType.WALKING
}

private fun GroupedActivity.toSnapshot(): TrackerActivityGroup = when (this) {
	GroupedActivity.STILL -> TrackerActivityGroup.STILL
	GroupedActivity.ON_FOOT -> TrackerActivityGroup.ON_FOOT
	GroupedActivity.IN_VEHICLE -> TrackerActivityGroup.IN_VEHICLE
	GroupedActivity.UNKNOWN -> TrackerActivityGroup.UNKNOWN
}

private fun WifiData.toSnapshot(): TrackerWifiSnapshot = TrackerWifiSnapshot(
	time = time,
	inRange = inRange.map(WifiInfo::toSnapshot),
)

private fun WifiInfo.toSnapshot(): TrackerWifiAccessPointSnapshot =
	TrackerWifiAccessPointSnapshot(
		ssid = ssid,
		level = level,
	)

private fun CellData.toSnapshot(): TrackerCellSnapshot = TrackerCellSnapshot(
	totalCount = totalCount,
	registeredCells = registeredCells.map(CellInfo::toSnapshot),
)

private fun CellInfo.toSnapshot(): TrackerCellInfoSnapshot = TrackerCellInfoSnapshot(
	networkOperator = networkOperator.toSnapshot(),
	type = type.toSnapshot(),
	dbm = dbm,
)

private fun NetworkOperator.toSnapshot(): TrackerNetworkOperatorSnapshot =
	TrackerNetworkOperatorSnapshot(
		mcc = mcc,
		mnc = mnc,
		name = name,
	)

private fun CellType.toSnapshot(): TrackerCellType = when (this) {
	CellType.Unknown -> TrackerCellType.UNKNOWN
	CellType.GSM -> TrackerCellType.GSM
	CellType.CDMA -> TrackerCellType.CDMA
	CellType.WCDMA -> TrackerCellType.WCDMA
	CellType.LTE -> TrackerCellType.LTE
	CellType.NR -> TrackerCellType.NR
	CellType.None -> TrackerCellType.NONE
}
