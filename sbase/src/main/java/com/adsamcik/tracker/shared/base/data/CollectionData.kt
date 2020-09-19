package com.adsamcik.tracker.shared.base.data

import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

/**
 * Immutable collection data interface.
 */
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
@Suppress("MemberVisibilityCanBePrivate")
@Parcelize
class MutableCollectionData(val bundle: Bundle = Bundle()) : CollectionData {

	constructor(time: Long) : this() {
		this.time = time
	}

	override var time: Long
		get() = get(TIME)
		set(value) = set(TIME, value)

	override var location: Location?
		get() = tryGet(LOCATION)
		set(value) = set(LOCATION, value)

	override var activity: ActivityInfo?
		get() = tryGet(ACTIVITY)
		set(value) = set(ACTIVITY, value)

	override var cell: CellData?
		get() = tryGet(CELL)
		set(value) = set(CELL, value)

	override var wifi: WifiData?
		get() = tryGet(WIFI)
		set(value) = set(WIFI, value)


	/**
	 * Retrieve long value with key.
	 */
	fun get(key: String): Long {
		return bundle.getLong(key)
	}

	/**
	 * Retrieve parcelable value with key.
	 *
	 * Throws an [IllegalArgumentException] if the value is null.
	 */
	fun <T : Parcelable> get(key: String): T {
		return requireNotNull(bundle.getParcelable(key))
	}

	/**
	 * Try retrieve parcelable value with key.
	 *
	 * @return Value or null if the value is not available
	 */
	fun <T : Parcelable> tryGet(key: String): T? {
		return bundle.getParcelable(key)
	}

	/**
	 * Set parcelable value with key.
	 *
	 * @param key Key
	 * @param value Value (Removes existing value if null)
	 */
	fun set(key: String, value: Parcelable?) {
		if (value == null) {
			bundle.remove(key)
		} else {
			bundle.putParcelable(key, value)
		}
	}

	/**
	 * Set long value with key.
	 *
	 * @param key Key
	 * @param value Value (Removes existing value if null)
	 */
	fun set(key: String, value: Long?) {
		if (value == null) {
			bundle.remove(key)
		} else {
			bundle.putLong(key, value)
		}
	}

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
	fun setWifi(
			location: android.location.Location?,
			time: Long,
			data: Array<ScanResult>?,
			wifiManager: WifiManager
	) {
		if (data != null && time > 0) {
			val scannedWifi = data.map { scanResult -> WifiInfo(scanResult, wifiManager) }
			val wifiLocation = if (location != null) Location(location) else null
			this.wifi = WifiData(wifiLocation, time, scannedWifi)
		}
	}

	companion object {
		private const val WIFI = "WiFi"
		private const val CELL = "Cell"
		private const val TIME = "Time"
		private const val LOCATION = "Location"
		private const val ACTIVITY = "Activity"
	}
}

