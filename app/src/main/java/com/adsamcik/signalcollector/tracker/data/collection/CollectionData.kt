package com.adsamcik.signalcollector.tracker.data.collection

import android.os.Parcelable
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.common.data.CellData
import com.adsamcik.signalcollector.common.data.Location
import com.adsamcik.signalcollector.common.data.WifiData

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