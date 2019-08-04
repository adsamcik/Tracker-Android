package com.adsamcik.signalcollector.tracker.data.collection

import android.annotation.SuppressLint
import android.net.wifi.ScanResult
import android.os.Build
import android.telephony.*
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.adsamcik.signalcollector.common.Reporter
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.data.*
import com.adsamcik.signalcollector.common.data.CellInfo
import com.squareup.moshi.JsonClass
import java.util.*

/**
 * Object containing raw collection data.
 * Data in here might have been reordered to different objects
 * but they have not been modified in any way.
 */
@JsonClass(generateAdapter = true)
data class MutableCollectionData(
		override val time: Long = Time.nowMillis,
		override var location: Location? = null,
		override var activity: ActivityInfo? = null,
		override var cell: CellData? = null,
		override var wifi: WifiData? = null) : CollectionData {


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
