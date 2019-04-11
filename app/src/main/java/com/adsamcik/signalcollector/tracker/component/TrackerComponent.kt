package com.adsamcik.signalcollector.tracker.component

import android.content.Context
import android.content.IntentFilter
import android.location.Location
import android.net.wifi.WifiManager
import android.os.Build
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.preference.Preferences
import com.adsamcik.signalcollector.tracker.data.RawData
import com.google.android.gms.location.LocationResult

abstract class TrackerComponent(context: Context) {
	protected abstract val enabledKeyRes: Int
	protected abstract val enabledDefaultRes: Int

	protected val enabledKey: String
	protected val enabledDefault: Boolean

	init {
		val resources = context.resources
		enabledKey = resources.getString(enabledKeyRes)
		enabledDefault = resources.getString(enabledDefaultRes).toBoolean()
	}

	abstract fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, distance: Float, rawData: RawData)

	fun isEnabled(context: Context): Boolean = Preferences.getPref(context).getBoolean(enabledKey, enabledDefault)
	abstract fun onDestroy(context: Context)
}