package com.adsamcik.tracker.shared.base.data

import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import kotlinx.parcelize.Parcelize

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

	/**
	 * Datum-aware altitude derived from the raw provider location, when this collection cycle
	 * produced one. The raw [location] remains the provider observation and must not be used as an
	 * MSL estimate.
	 *
	 * A default keeps non-pipeline implementations of this lightweight interface conservative.
	 */
	val processedAltitude: ProcessedAltitudeData?
		get() = null

	/**
	 * Curated velocity estimate used by legacy live consumers. This is kept separate from
	 * [location], whose speed remains the unmodified platform observation.
	 */
	val estimatedSpeedMps: Float?
		get() = location?.speed
}

/**
 * Processed altitude kept separately from [Location.altitude].
 *
 * The collection [Location] is a direct copy of the Android provider fix and therefore retains
 * its raw WGS-84 ellipsoid altitude. This parcelable carries the optional MSL/fusion estimate and
 * its datum through the Android-only collection boundary without repurposing that raw field.
 */
@Parcelize
data class ProcessedAltitudeData(
	val altitudeM: Float?,
	val datum: AltitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,
	val source: AltitudeSource = AltitudeSource.UNKNOWN_LEGACY,
	val conversionStatus: AltitudeConversionStatus = AltitudeConversionStatus.UNKNOWN_LEGACY,
	val modelVersion: Int = 0,
	val estimatorVersion: Int = 0,
	val calibrationVersion: Int = 0,
	val rawAltitudeDatum: AltitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,
	/** Stable monotonic-clock domain of the provider fix that produced this estimate. */
	val clockDomainId: String? = null,
) : Parcelable

/**
 * Finite processed altitude that can safely be shown as Android-model mean sea level.
 *
 * Unknown, ellipsoid, and relative barometric values are deliberately hidden rather than being
 * presented as an absolute MSL altitude.
 */
val CollectionData.androidModelMslAltitudeM: Float?
	get() = processedAltitude
		?.takeIf { it.datum.isAndroidModelMsl }
		?.altitudeM
		?.takeIf(Float::isFinite)

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
	 * Distance in meters from the previously *accepted* location to this collection's location,
	 * as determined by the location filtering component (e.g. [LocationTrackerComponent]).
	 *
	 * Unlike the raw per-fix distance baked into [LocationData] by the collection trigger, this value
	 * bridges ordinary pre-tracker quality rejections from the last accepted anchor. A confirmed
	 * teleport re-acquisition is different: it starts a new anchor with zero cross-gap distance, so
	 * the accumulated session never fabricates a route through an explicitly unknown interval.
	 *
	 * `null` when no location was accepted for this cycle.
	 */
	var distanceFromPreviousM: Float?
		get() = if (bundle.containsKey(DISTANCE)) bundle.getFloat(DISTANCE) else null
		set(value) = set(DISTANCE, value)

	/** Raw WGS-84 GPS altitude before geoid correction or barometer fusion. */
	var rawGpsAltitudeM: Float?
		get() = if (bundle.containsKey(RAW_GPS_ALTITUDE)) bundle.getFloat(RAW_GPS_ALTITUDE) else null
		set(value) = set(RAW_GPS_ALTITUDE, value)

	/**
	 * Datum-aware processed altitude for the accepted location, if one was produced this cycle.
	 * This remains separate from [location], whose altitude is raw platform evidence.
	 */
	override var processedAltitude: ProcessedAltitudeData?
		get() = tryGet(PROCESSED_ALTITUDE)
		set(value) = set(PROCESSED_ALTITUDE, value)

	override var estimatedSpeedMps: Float?
		get() = if (bundle.containsKey(ESTIMATED_SPEED)) bundle.getFloat(ESTIMATED_SPEED) else null
		set(value) = set(ESTIMATED_SPEED, value)


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
	inline fun <reified T : Parcelable> get(key: String): T {
		return requireNotNull(tryGet(key))
	}

	/**
	 * Try retrieve parcelable value with key.
	 *
	 * @return Value or null if the value is not available
	 */
	inline fun <reified T : Parcelable> tryGet(key: String): T? {
		return if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			bundle.getParcelable(key, T::class.java)
		} else {
			@Suppress("DEPRECATION")
			bundle.getParcelable(key)
		}
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
	 * Set float value with key.
	 *
	 * @param key Key
	 * @param value Value (Removes existing value if null)
	 */
	fun set(key: String, value: Float?) {
		if (value == null) {
			bundle.remove(key)
		} else {
			bundle.putFloat(key, value)
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
		private const val DISTANCE = "Distance"
		private const val RAW_GPS_ALTITUDE = "RawGpsAltitude"
		private const val PROCESSED_ALTITUDE = "ProcessedAltitude"
		private const val ESTIMATED_SPEED = "EstimatedSpeedMps"
	}
}
