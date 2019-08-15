package com.adsamcik.signalcollector.common.data

import android.os.Parcel
import android.os.Parcelable
import com.adsamcik.signalcollector.common.extension.requireArrayList
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
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
) : Parcelable {
	constructor(parcel: Parcel) : this(
			parcel.readParcelable(Location::class.java.classLoader),
			parcel.readLong(),
			parcel.requireArrayList(WifiInfo))

	override fun writeToParcel(parcel: Parcel, flags: Int) {
		parcel.writeParcelable(location, flags)
		parcel.writeLong(time)
		parcel.writeTypedList(inRange)
	}

	override fun describeContents(): Int {
		return 0
	}

	companion object CREATOR : Parcelable.Creator<WifiData> {
		override fun createFromParcel(parcel: Parcel): WifiData {
			return WifiData(parcel)
		}

		override fun newArray(size: Int): Array<WifiData?> {
			return arrayOfNulls(size)
		}
	}
}

