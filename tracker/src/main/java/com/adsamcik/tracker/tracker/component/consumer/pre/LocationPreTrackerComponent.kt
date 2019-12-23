package com.adsamcik.tracker.tracker.component.consumer.pre

import android.content.Context
import androidx.lifecycle.Observer
import com.adsamcik.androidcomponents.common_preferences.observer.PreferenceObserver
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal class LocationPreTrackerComponent : PreTrackerComponent, CoroutineScope {
	override val requiredData: Collection<TrackerComponentRequirement> = listOf(
			TrackerComponentRequirement.LOCATION
	)

	private val job = SupervisorJob()
	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	private var requiredAccuracy = 0

	private val observer = Observer<Int> { requiredAccuracy = it }

	override suspend fun onEnable(context: Context) {
		withContext(coroutineContext) {
			com.adsamcik.androidcomponents.common_preferences.observer.PreferenceObserver.observeIntRes(
					context,
					keyRes = R.string.settings_tracking_required_accuracy_key,
					defaultRes = R.integer.settings_tracking_required_accuracy_default,
					observer = observer
			)
		}
	}

	override suspend fun onDisable(context: Context) {
		withContext(coroutineContext) {
			com.adsamcik.androidcomponents.common_preferences.observer.PreferenceObserver.removeObserver(
					context,
					R.string.settings_tracking_required_accuracy_key,
					observer
			)
		}
	}

	override suspend fun onNewData(data: MutableCollectionTempData): Boolean {
		val location = data.getLocation(this)

		if (location.isFromMockProvider) return false

		if (!location.hasAccuracy()) return false

		return location.accuracy <= requiredAccuracy
	}


}
