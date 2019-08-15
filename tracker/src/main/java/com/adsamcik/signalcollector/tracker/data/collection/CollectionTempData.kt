package com.adsamcik.signalcollector.tracker.data.collection

import android.location.Location
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.tracker.BuildConfig
import com.adsamcik.signalcollector.tracker.component.TrackerComponentRequirement
import com.adsamcik.signalcollector.tracker.component.TrackerDataConsumerComponent
import com.google.android.gms.location.LocationResult

internal class MutableCollectionTempData(timeMillis: Long, elapsedRealtimeNanos: Long) : CollectionTempData(timeMillis,
		elapsedRealtimeNanos) {
	override val map: MutableMap<String, InternalData> = mutableMapOf()

	fun <T : Any> set(key: String, value: T) {
		set(key, InternalData(value as Any))
	}

	private fun <T : Any> set(key: TrackerComponentRequirement, value: T) {
		set(key.name, InternalData(value as Any))
	}

	private fun set(key: String, value: InternalData) {
		map[key] = value
	}

	fun setActivity(value: ActivityInfo) {
		set(TrackerComponentRequirement.ACTIVITY, value)
	}

	fun setLocationResult(locationResult: LocationResult) {
		set(TrackerComponentRequirement.LOCATION, locationResult)
	}

	fun setPreviousLocation(location: Location, distance: Float) {
		set(PREVIOUS_LOCATION, location)
		set(DISTANCE, distance)
	}

	fun setCellData(cellData: CellScanData) {
		set(TrackerComponentRequirement.CELL, cellData)
	}
}

internal abstract class CollectionTempData(val timeMillis: Long, val elapsedRealtimeNanos: Long) {
	protected abstract val map: Map<String, InternalData>

	fun containsKey(key: String): Boolean = map.containsKey(key)

	fun <T> tryGet(key: String): T? {
		@Suppress("UNCHECKED_CAST")
		return map[key]?.value as? T
	}

	private fun <T> tryGet(key: TrackerComponentRequirement): T? {
		@Suppress("UNCHECKED_CAST")
		return tryGet<T>(key.name)
	}

	fun <T : Any> get(key: String): T {
		@Suppress("UNCHECKED_CAST")
		return map[key]?.value as T
	}

	private fun <T : Any> get(key: TrackerComponentRequirement): T {
		return get(key.name)
	}

	private fun validatePermissions(component: TrackerDataConsumerComponent, required: TrackerComponentRequirement) {
		if (BuildConfig.DEBUG) {
			assert(component.requiredData.contains(required))
		}
	}

	fun getActivity(component: TrackerDataConsumerComponent): ActivityInfo {
		validatePermissions(component, TrackerComponentRequirement.ACTIVITY)
		return get(TrackerComponentRequirement.ACTIVITY)
	}

	fun tryGetActivity(): ActivityInfo? {
		return tryGet(TrackerComponentRequirement.ACTIVITY)
	}

	fun getDistance(component: TrackerDataConsumerComponent): Float {
		validatePermissions(component, TrackerComponentRequirement.LOCATION)
		return get(DISTANCE)
	}

	fun tryGetDistance(): Float? {
		return tryGet(DISTANCE)
	}

	fun getLocationResult(component: TrackerDataConsumerComponent): LocationResult {
		validatePermissions(component, TrackerComponentRequirement.LOCATION)
		return get(TrackerComponentRequirement.LOCATION)
	}

	fun tryGetLocationResult(): LocationResult? {
		return tryGet(TrackerComponentRequirement.LOCATION)
	}

	fun getLocation(component: TrackerDataConsumerComponent): Location {
		return getLocationResult(component).lastLocation
	}

	fun tryGetLocation(): Location? {
		return tryGet<LocationResult>(TrackerComponentRequirement.LOCATION)?.lastLocation
	}

	fun getPreviousLocation(component: TrackerDataConsumerComponent): Location {
		validatePermissions(component, TrackerComponentRequirement.LOCATION)
		return get(PREVIOUS_LOCATION)
	}

	fun tryGetPreviousLocation(): Location? {
		return tryGet(PREVIOUS_LOCATION)
	}

	fun getWifiData(component: TrackerDataConsumerComponent): WifiScanData {
		validatePermissions(component, TrackerComponentRequirement.WIFI)
		return get(TrackerComponentRequirement.WIFI)
	}

	fun getCellData(component: TrackerDataConsumerComponent): CellScanData {
		validatePermissions(component, TrackerComponentRequirement.CELL)
		return get(TrackerComponentRequirement.CELL)
	}

	protected data class InternalData(val value: Any)

	companion object {
		internal const val DISTANCE = "distance"
		internal const val PREVIOUS_LOCATION = "previousLocation"
	}
}

