package com.adsamcik.signalcollector.tracker.component.data

import android.content.Context
import android.location.Location
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.Assist
import com.adsamcik.signalcollector.misc.extension.telephonyManager
import com.adsamcik.signalcollector.tracker.component.PreferenceDataTrackerComponent
import com.adsamcik.signalcollector.tracker.data.MutableCollectionData
import com.google.android.gms.location.LocationResult

class CellTrackerComponent(val context: Context) : PreferenceDataTrackerComponent() {
	override val enabledKeyRes: Int
		get() = R.string.settings_cell_enabled_key
	override val enabledDefaultRes: Int
		get() = R.string.settings_cell_enabled_default

	private val telephonyManager = context.telephonyManager

	override fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, distance: Float, collectionData: MutableCollectionData) {
		if (!Assist.isAirplaneModeEnabled(context)) {
			collectionData.addCell(telephonyManager)
		}
	}

	override fun onDestroy(context: Context) {}

}