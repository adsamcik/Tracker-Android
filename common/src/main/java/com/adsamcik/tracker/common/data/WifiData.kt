package com.adsamcik.tracker.common.data

import android.os.Parcel
import android.os.Parcelable
import com.adsamcik.tracker.common.extension.requireArrayList
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

