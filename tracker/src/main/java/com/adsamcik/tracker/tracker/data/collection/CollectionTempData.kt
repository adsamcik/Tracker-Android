package com.adsamcik.tracker.tracker.data.collection

import android.location.Location
import com.adsamcik.tracker.logger.assertTrue
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.TrackerDataConsumerComponent

internal class MutableCollectionTempData(timeMillis: Long, elapsedRealtimeNanos: Long) :
		CollectionTempData(
				timeMillis,
				elapsedRealtimeNanos
		) {
	override val map: MutableMap<String, Any> = mutableMapOf()

	fun set(key: String, value: Any) {
		map[key] = value
	}

	private fun <T : Any> set(key: TrackerComponentRequirement, value: T) {
		set(key.name, value)
	}

	fun setActivity(value: ActivityInfo) {
		set(TrackerComponentRequirement.ACTIVITY, value)
	}

	fun setLocationData(locationData: LocationData) {
		set(TrackerComponentRequirement.LOCATION, locationData)
	}

	fun setCellData(cellData: CellScanData) {
		set(TrackerComponentRequirement.CELL, cellData)
	}
}

@Suppress("TooManyFunctions")
internal abstract class CollectionTempData(val timeMillis: Long, val elapsedRealtimeNanos: Long) {
	protected abstract val map: Map<String, Any>

	fun containsKey(key: String): Boolean = map.containsKey(key)

	private fun tryGetRaw(key: String): Any? {
		return map[key]
	}

	fun <T> tryGet(key: String): T? {
		@Suppress("UNCHECKED_CAST")
		return tryGetRaw(key) as? T
	}

	private fun <T> tryGet(key: TrackerComponentRequirement): T? {
		@Suppress("UNCHECKED_CAST")
		return tryGet<T>(key.name)
	}

	fun <T : Any> get(key: String): T {
		@Suppress("UNCHECKED_CAST")
		return tryGetRaw(key) as T
	}

	private fun <T : Any> get(key: TrackerComponentRequirement): T {
		return get(key.name)
	}

	private fun validatePermissions(
			component: TrackerDataConsumerComponent,
			required: TrackerComponentRequirement
	) {
		assertTrue(component.requiredData.contains(required))
	}

	fun getActivity(component: TrackerDataConsumerComponent): ActivityInfo {
		validatePermissions(component, TrackerComponentRequirement.ACTIVITY)
		return get(TrackerComponentRequirement.ACTIVITY)
	}

	fun tryGetActivity(): ActivityInfo? {
		return tryGet(TrackerComponentRequirement.ACTIVITY)
	}

	fun getLocationData(component: TrackerDataConsumerComponent): LocationData {
		validatePermissions(component, TrackerComponentRequirement.LOCATION)
		return get(TrackerComponentRequirement.LOCATION)
	}

	fun tryGetLocationData(): LocationData? {
		return tryGet(TrackerComponentRequirement.LOCATION)
	}

	fun getLocation(component: TrackerDataConsumerComponent): Location {
		return getLocationData(component).lastLocation
	}

	fun tryGetLocation(): Location? {
		return tryGet<LocationData>(TrackerComponentRequirement.LOCATION)?.lastLocation
	}

	fun getWifiData(component: TrackerDataConsumerComponent): WifiScanData {
		validatePermissions(component, TrackerComponentRequirement.WIFI)
		return get(TrackerComponentRequirement.WIFI)
	}

	fun getCellData(component: TrackerDataConsumerComponent): CellScanData {
		validatePermissions(component, TrackerComponentRequirement.CELL)
		return get(TrackerComponentRequirement.CELL)
	}
}

