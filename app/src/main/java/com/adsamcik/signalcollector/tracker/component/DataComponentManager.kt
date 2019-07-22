package com.adsamcik.signalcollector.tracker.component

import android.content.Context
import android.location.Location
import androidx.lifecycle.Observer
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.preference.observer.PreferenceObserver
import com.adsamcik.signalcollector.tracker.component.data.*
import com.adsamcik.signalcollector.tracker.data.CollectionTempData
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

class DataComponentManager(context: Context) : CoroutineScope {
	private val appContext = context.applicationContext
	private val dataComponentList = mutableListOf<DataTrackerComponent>()

	private val job = SupervisorJob()
	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	private val locationTrackerObserver = Observer<Boolean> {
		launch {
			onChange(it) { LocationTrackerComponent() }
			onChange(it) { ActivityTrackerComponent() }
		}
	}
	private val wifiTrackerObserver = Observer<Boolean> { launch { onChange(it) { WifiTrackerComponent() } } }
	private val cellTrackerObserver = Observer<Boolean> { launch { onChange(it) { CellTrackerComponent() } } }

	private lateinit var sessionComponent: SessionTrackerComponent
	val session: TrackerSession
		get() = sessionComponent.session


	suspend fun onEnable(isUserInitiated: Boolean) = coroutineScope {
		PreferenceObserver.observe(appContext,
				keyRes = R.string.settings_location_enabled_key,
				defaultRes = R.string.settings_location_enabled_default,
				observer = locationTrackerObserver)

		PreferenceObserver.observe(appContext,
				keyRes = R.string.settings_wifi_enabled_key,
				defaultRes = R.string.settings_wifi_enabled_default,
				observer = wifiTrackerObserver)

		PreferenceObserver.observe(appContext,
				keyRes = R.string.settings_cell_enabled_key,
				defaultRes = R.string.settings_cell_enabled_default,
				observer = cellTrackerObserver)

		sessionComponent = SessionTrackerComponent(isUserInitiated)
		dataComponentList.add(sessionComponent)

		dataComponentList.forEach { it.onEnable(appContext) }
	}

	suspend fun onDisable() = coroutineScope {
		PreferenceObserver.removeObserver(appContext, R.string.settings_location_enabled_key, locationTrackerObserver)
		PreferenceObserver.removeObserver(appContext, R.string.settings_wifi_enabled_key, wifiTrackerObserver)
		PreferenceObserver.removeObserver(appContext, R.string.settings_cell_enabled_key, cellTrackerObserver)

		dataComponentList.forEach { it.onDisable(appContext) }
		dataComponentList.clear()
	}

	suspend fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, collectionData: MutableCollectionData, tempData: CollectionTempData) {
		withContext(coroutineContext) {
			dataComponentList.map {
				async { it.onLocationUpdated(locationResult, previousLocation, collectionData, tempData) }
			}.awaitAll()
		}
	}

	private suspend inline fun <reified T> onChange(value: Boolean, factory: () -> T) where T : DataTrackerComponent {
		if (value) {
			addInstance(factory())
		} else {
			removeInstance<T>()
		}
	}

	private suspend fun <T> addInstance(instance: T) where T : DataTrackerComponent {
		assert(dataComponentList.none { it::class == instance::class }) { "Instance cannot be added again!" }
		dataComponentList.add(instance)
		instance.onEnable(appContext)
	}

	private suspend inline fun <reified T> removeInstance() where T : DataTrackerComponent {
		val index = dataComponentList.indexOfFirst { it::class == T::class }
		if (index >= 0) {
			dataComponentList[index].onDisable(appContext)
			dataComponentList.removeAt(index)
		}
	}
}