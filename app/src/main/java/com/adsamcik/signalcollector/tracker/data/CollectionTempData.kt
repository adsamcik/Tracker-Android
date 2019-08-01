package com.adsamcik.signalcollector.tracker.data

import android.location.Location
import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.tracker.component.TrackerComponent
import com.adsamcik.signalcollector.tracker.component.TrackerComponentRequirement
import com.google.android.gms.location.LocationResult

internal class MutableCollectionTempData(elapseRealtimeNanos: Long) : CollectionTempData(elapseRealtimeNanos) {
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

	fun setDistance(distance: Float) {
		set(DISTANCE, distance)
	}

	fun setPreviousLocation(location: Location) {
		set(PREVIOUS_LOCATION, location)
	}
}

internal abstract class CollectionTempData(val elapsedRealtimeNanos: Long) {
	protected abstract val map: Map<String, InternalData>

	fun containsKey(key: String): Boolean = map.containsKey(key)

	fun <T> tryGet(key: String): T? {
		@Suppress("UNCHECKED_CAST")
		return map[key]?.value as? T
	}

	fun <T : Any> get(key: String): T {
		@Suppress("UNCHECKED_CAST")
		return map[key]?.value as T
	}

	private fun validatePermissions(component: TrackerComponent, required: TrackerComponentRequirement) {
		if (BuildConfig.DEBUG) {
			assert(component.requiredData.contains(required))
		}
	}

	fun getActivity(component: TrackerComponent): ActivityInfo {
		validatePermissions(component, TrackerComponentRequirement.ACTIVITY)
		return get(TrackerComponentRequirement.ACTIVITY.name)
	}

	fun tryGetActivity(): ActivityInfo? {
		return tryGet(TrackerComponentRequirement.ACTIVITY.name)
	}

	fun getDistance(component: TrackerComponent): Float {
		validatePermissions(component, TrackerComponentRequirement.LOCATION)
		return get(DISTANCE)
	}

	fun tryGetDistance(): Float? {
		return tryGet(DISTANCE)
	}

	fun getLocationResult(component: TrackerComponent): LocationResult {
		validatePermissions(component, TrackerComponentRequirement.LOCATION)
		return get(TrackerComponentRequirement.LOCATION.name)
	}

	fun tryGetLocationResult(): LocationResult? {
		return tryGet(TrackerComponentRequirement.LOCATION.name)
	}

	fun getLocation(component: TrackerComponent): Location {
		return getLocationResult(component).lastLocation
	}

	fun tryGetLocation(): Location? {
		return tryGet<LocationResult>(TrackerComponentRequirement.LOCATION.name)?.lastLocation
	}

	fun getPreviousLocation(component: TrackerComponent): Location {
		validatePermissions(component, TrackerComponentRequirement.LOCATION)
		return get(PREVIOUS_LOCATION)
	}

	fun tryGetPreviousLocation(): Location? {
		return tryGet(PREVIOUS_LOCATION)
	}

	protected data class InternalData(val value: Any)

	companion object {
		internal const val DISTANCE = "distance"
		internal const val PREVIOUS_LOCATION = "previousLocation"
	}
}