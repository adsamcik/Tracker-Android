package com.adsamcik.tracker.shared.base.data

import android.os.Parcelable
import com.squareup.moshi.JsonClass
import kotlinx.android.parcel.Parcelize

@JsonClass(generateAdapter = false)
@Parcelize
data class WifiData(
		val location: Location?,
		/**
		 * Time of collection of wifi data
		 */
		val time: Long,
		/**
		 * Array of collected wifi networks
		 */
		val inRange: List<WifiInfo>
) : Parcelable

