package com.adsamcik.signalcollector.tracker.data

import com.adsamcik.signalcollector.activity.ActivityInfo

interface CollectionData {
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