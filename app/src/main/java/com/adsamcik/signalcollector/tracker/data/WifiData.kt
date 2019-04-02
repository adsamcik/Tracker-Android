package com.adsamcik.signalcollector.tracker.data

import android.location.Location
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class WifiData(
		val location: Location,
		/**
		 * Time of collection of wifi data
		 */
		val time: Long,
		/**
		 * Array of collected wifi networks
		 */
		val inRange: Array<WifiInfo>) {

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as WifiData

		if (location != other.location) return false
		if (time != other.time) return false
		if (!inRange.contentEquals(other.inRange)) return false

		return true
	}

	override fun hashCode(): Int {
		var result = location.hashCode()
		result = 31 * result + time.hashCode()
		result = 31 * result + inRange.contentHashCode()
		return result
	}

}