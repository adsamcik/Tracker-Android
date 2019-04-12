package com.adsamcik.signalcollector.tracker.component.pre

import android.content.Context
import android.location.Location
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.ActivityInfo
import com.adsamcik.signalcollector.preference.Preferences
import com.google.android.gms.location.LocationResult

class PreLocationTrackerComponent(private val context: Context) : PreTrackerComponent {
	private val requiredAccuracyKey: String
	private val requiredAccuracyDefault: Int

	init {
		val resources = context.resources
		requiredAccuracyKey = resources.getString(R.string.settings_tracking_required_accuracy_key)
		requiredAccuracyDefault = resources.getInteger(R.integer.settings_tracking_required_accuracy_default)
	}

	override fun onNewLocation(locationResult: LocationResult, previousLocation: Location?, distance: Float, activity: ActivityInfo): Boolean {
		val location = locationResult.lastLocation

		if (!location.hasAccuracy()) return false

		val preferences = Preferences.getPref(context)
		return location.accuracy > preferences.getInt(requiredAccuracyKey, requiredAccuracyDefault)
	}


}