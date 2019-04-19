package com.adsamcik.signalcollector.tracker.data.collection

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
		val inRange: List<WifiInfo>)