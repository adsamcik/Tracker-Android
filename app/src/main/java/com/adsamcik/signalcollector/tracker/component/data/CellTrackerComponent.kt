package com.adsamcik.signalcollector.tracker.component.data

import android.content.Context
import android.location.Location
import android.telephony.TelephonyManager
import androidx.lifecycle.LifecycleOwner
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.ActivityInfo
import com.adsamcik.signalcollector.app.Assist
import com.adsamcik.signalcollector.misc.extension.telephonyManager
import com.adsamcik.signalcollector.tracker.data.MutableCollectionData
import com.google.android.gms.location.LocationResult

class CellTrackerComponent : PreferenceDataTrackerComponent() {
	override val enabledKeyRes: Int
		get() = R.string.settings_cell_enabled_key
	override val enabledDefaultRes: Int
		get() = R.string.settings_cell_enabled_default

	private lateinit var context: Context
	private lateinit var telephonyManager: TelephonyManager

	override fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, distance: Float, activity: ActivityInfo, collectionData: MutableCollectionData) {
		if (!Assist.isAirplaneModeEnabled(context)) {
			collectionData.addCell(telephonyManager)
		}
	}

	override fun onEnable(context: Context, owner: LifecycleOwner) {
		super.onEnable(context, owner)
		this.context = context
		telephonyManager = context.telephonyManager
	}

}