package com.adsamcik.signalcollector.tracker.component.data

import android.content.Context
import android.location.Location
import android.telephony.TelephonyManager
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.common.misc.extension.telephonyManager
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.google.android.gms.location.LocationResult

class CellTrackerComponent : DataTrackerComponent {

	private var context: Context? = null
	private var telephonyManager: TelephonyManager? = null

	override fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, distance: Float, activity: ActivityInfo, collectionData: MutableCollectionData) {
		val context = context ?: throw NullPointerException("Context must not be null")
		if (!Assist.isAirplaneModeEnabled(context)) {
			val telephonyManager = telephonyManager
					?: throw NullPointerException("Telephony manager must not be null")
			collectionData.addCell(telephonyManager)
		}
	}

	override fun onEnable(context: Context) {
		this.context = context
		telephonyManager = context.telephonyManager
	}

	override fun onDisable(context: Context) {
		this.context = null
		telephonyManager = null
	}

}