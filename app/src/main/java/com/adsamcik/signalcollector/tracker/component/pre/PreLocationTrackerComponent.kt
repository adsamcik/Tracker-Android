package com.adsamcik.signalcollector.tracker.component.pre

import android.content.Context
import android.location.Location
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.ActivityInfo
import com.google.android.gms.location.LocationResult

class PreLocationTrackerComponent(context: Context) : PreTrackerComponent {
	private val enabledKey: String
	private val enabledDefault: Int

	init {
		val resources = context.resources
		enabledKey = resources.getString(R.string.settings_tracking_required_accuracy_key)
		enabledDefault = resources.getInteger(R.integer.settings_tracking_required_accuracy_default)
	}

	override fun onNewLocation(locationResult: LocationResult, previousLocation: Location?, distance: Float, activity: ActivityInfo): Boolean {
		TODO()
	}


}