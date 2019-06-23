package com.adsamcik.signalcollector.tracker.component.pre

import android.content.Context
import android.location.Location
import androidx.lifecycle.Observer
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.preference.observer.PreferenceObserver
import com.adsamcik.signalcollector.tracker.component.PreTrackerComponent
import com.google.android.gms.location.LocationResult

class PreLocationTrackerComponent : PreTrackerComponent {
	private var requiredAccuracy = 0

	private val observer = Observer<Int> { requiredAccuracy = it }

	override suspend fun onEnable(context: Context) {
		PreferenceObserver.observeIntRes(context,
				keyRes = R.string.settings_tracking_required_accuracy_key,
				defaultRes = R.integer.settings_tracking_required_accuracy_default,
				observer = observer)
	}

	override suspend fun onDisable(context: Context) {
		PreferenceObserver.removeObserver(context, R.string.settings_tracking_required_accuracy_key, observer)
	}

	override suspend fun onNewLocation(locationResult: LocationResult, previousLocation: Location?, distance: Float): Boolean {
		val location = locationResult.lastLocation

		if (!location.hasAccuracy()) return false

		return location.accuracy <= requiredAccuracy
	}


}