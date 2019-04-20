package com.adsamcik.signalcollector.tracker.component

import android.content.Context
import android.location.Location
import androidx.lifecycle.Observer
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.ActivityInfo
import com.adsamcik.signalcollector.preference.observer.PreferenceObserver
import com.adsamcik.signalcollector.tracker.component.data.*
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.adsamcik.signalcollector.tracker.data.session.TrackerSession
import com.google.android.gms.location.LocationResult

class DataComponentManager(context: Context) {
	private val appContext = context.applicationContext
	private val dataComponentList = mutableListOf<DataTrackerComponent>()

	private val locationTrackerObserver = Observer<Boolean> {
		onChange(it) { LocationTrackerComponent() }
		onChange(it) { ActivityTrackerComponent() }
	}
	private val wifiTrackerObserver = Observer<Boolean> { onChange(it) { WifiTrackerComponent() } }
	private val cellTrackerObserver = Observer<Boolean> { onChange(it) { CellTrackerComponent() } }

	private lateinit var sessionComponent: SessionTrackerComponent
	val session: TrackerSession
		get() = sessionComponent.session


	fun onEnable() {
		sessionComponent = SessionTrackerComponent()
		dataComponentList.add(sessionComponent)

		dataComponentList.forEach { it.onEnable(appContext) }

		PreferenceObserver.observe(appContext,
				keyRes = R.string.settings_location_enabled_key,
				observer = locationTrackerObserver,
				defaultRes = R.string.settings_location_enabled_default)

		PreferenceObserver.observe(appContext,
				keyRes = R.string.settings_wifi_enabled_key,
				observer = wifiTrackerObserver,
				defaultRes = R.string.settings_wifi_enabled_default)

		PreferenceObserver.observe(appContext,
				keyRes = R.string.settings_cell_enabled_key,
				observer = cellTrackerObserver,
				defaultRes = R.string.settings_cell_enabled_default)
	}

	fun onDisable() {
		PreferenceObserver.removeObserver(appContext, R.string.settings_location_enabled_key, locationTrackerObserver)
		PreferenceObserver.removeObserver(appContext, R.string.settings_wifi_enabled_key, wifiTrackerObserver)
		PreferenceObserver.removeObserver(appContext, R.string.settings_cell_enabled_key, cellTrackerObserver)
		dataComponentList.forEach { it.onDisable(appContext) }
		dataComponentList.clear()
	}

	fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, distance: Float, activity: ActivityInfo, collectionData: MutableCollectionData) {
		dataComponentList.forEach { it.onLocationUpdated(locationResult, previousLocation, distance, activity, collectionData) }
	}

	private inline fun <reified T> onChange(value: Boolean, factory: () -> T) where T : DataTrackerComponent {
		if (value) {
			addInstance(factory())
		} else {
			removeInstance<T>()
		}
	}

	private fun <T> addInstance(instance: T) where T : DataTrackerComponent {
		assert(dataComponentList.none { it::class == instance::class }) { "Instance cannot be added again!" }
		dataComponentList.add(instance)
		instance.onEnable(appContext)
	}

	private inline fun <reified T> removeInstance() where T : DataTrackerComponent {
		val index = dataComponentList.indexOfFirst { it::class == T::class }
		dataComponentList[index].onDisable(appContext)
		dataComponentList.removeAt(index)
	}
}