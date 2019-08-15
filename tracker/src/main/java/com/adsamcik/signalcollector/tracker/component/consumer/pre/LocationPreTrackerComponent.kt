package com.adsamcik.signalcollector.tracker.component.consumer.pre

import android.content.Context
import androidx.lifecycle.Observer
import com.adsamcik.signalcollector.common.preference.observer.PreferenceObserver
import com.adsamcik.signalcollector.tracker.R
import com.adsamcik.signalcollector.tracker.component.PreTrackerComponent
import com.adsamcik.signalcollector.tracker.component.TrackerComponentRequirement
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionTempData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal class LocationPreTrackerComponent : PreTrackerComponent, CoroutineScope {
	override val requiredData: Collection<TrackerComponentRequirement> = listOf(TrackerComponentRequirement.LOCATION)

	private val job = SupervisorJob()
	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	private var requiredAccuracy = 0

	private val observer = Observer<Int> { requiredAccuracy = it }

	override suspend fun onEnable(context: Context) {
		withContext(coroutineContext) {
			PreferenceObserver.observeIntRes(context,
					keyRes = R.string.settings_tracking_required_accuracy_key,
					defaultRes = R.integer.settings_tracking_required_accuracy_default,
					observer = observer)
		}
	}

	override suspend fun onDisable(context: Context) {
		withContext(coroutineContext) {
			PreferenceObserver.removeObserver(context, R.string.settings_tracking_required_accuracy_key, observer)
		}
	}

	override suspend fun onNewData(data: MutableCollectionTempData): Boolean {
		val location = data.getLocation(this)

		if (location.isFromMockProvider) return false

		if (!location.hasAccuracy()) return false

		return location.accuracy <= requiredAccuracy
	}


}
