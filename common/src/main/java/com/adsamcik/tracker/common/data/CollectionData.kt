package com.adsamcik.tracker.common.data

import android.net.wifi.ScanResult
import android.os.Parcelable
import com.adsamcik.tracker.common.Time
import kotlinx.android.parcel.Parcelize

interface CollectionData : Parcelable {
	/**
	 * Time of collection in milliseconds since midnight, January 1, 1970 UTC (UNIX time)
	 */
	val time: Long

	/**
	 * Current location
	 */
	val location: Location?

	/**
	 * Current resolved activity
	 */
	val activity: ActivityInfo?

	/**
	 * Data about cells
	 */
	val cell: CellData?

	/**
	 * Data about Wi-Fi
	 */
	val wifi: WifiData?
}

/**
 * Object containing raw collection data.
 * Data in here might have been reordered to different objects
 * but they have not been modified in any way.
 */
@Parcelize
data class MutableCollectionData(
		override val time: Long = Time.nowMillis,
		override var location: Location? = null,
		override var activity: ActivityInfo? = null,
		override var cell: CellData? = null,
		override var wifi: WifiData? = null
) : CollectionData {
	/**
	 * Sets collection location.
	 *
	 * @param location location
	 * @return this
	 */
	fun setLocation(location: android.location.Location) {
		this.location = Location(location)
	}

	/**
	 * Sets wifi and time of wifi collection.
	 *
	 * @param data data
	 * @param time time of collection
	 * @return this
	 */
	fun setWifi(location: android.location.Location?, time: Long, data: Array<ScanResult>?) {
		if (data != null && time > 0) {
			val scannedWifi = data.map { scanResult -> WifiInfo(scanResult) }
			val wifiLocation = if (location != null) Location(location) else null
			this.wifi = WifiData(wifiLocation, time, scannedWifi)
		}
	}
}

