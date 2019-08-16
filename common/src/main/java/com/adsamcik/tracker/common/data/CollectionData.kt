package com.adsamcik.tracker.common.data

import android.net.wifi.ScanResult
import android.os.Parcel
import android.os.Parcelable
import com.adsamcik.tracker.common.Time

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
data class MutableCollectionData(
		override val time: Long = Time.nowMillis,
		override var location: Location? = null,
		override var activity: ActivityInfo? = null,
		override var cell: CellData? = null,
		override var wifi: WifiData? = null
) : CollectionData {


	constructor(parcel: Parcel) : this(
			parcel.readLong(),
			parcel.readParcelable(Location::class.java.classLoader),
			parcel.readParcelable(ActivityInfo::class.java.classLoader),
			parcel.readParcelable(CellData::class.java.classLoader),
			parcel.readParcelable(WifiData::class.java.classLoader))

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

	override fun writeToParcel(parcel: Parcel, flags: Int) {
		parcel.writeLong(time)
		parcel.writeParcelable(location, flags)
		parcel.writeParcelable(activity, flags)
		parcel.writeParcelable(cell, flags)
		parcel.writeParcelable(wifi, flags)
	}

	override fun describeContents(): Int {
		return 0
	}

	companion object CREATOR : Parcelable.Creator<MutableCollectionData> {
		override fun createFromParcel(parcel: Parcel): MutableCollectionData {
			return MutableCollectionData(parcel)
		}

		override fun newArray(size: Int): Array<MutableCollectionData?> {
			return arrayOfNulls(size)
		}
	}
}

