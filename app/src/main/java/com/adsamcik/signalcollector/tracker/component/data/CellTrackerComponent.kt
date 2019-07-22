package com.adsamcik.signalcollector.tracker.component.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.extension.getSystemServiceTyped
import com.adsamcik.signalcollector.common.extension.hasReadPhonePermission
import com.adsamcik.signalcollector.common.extension.telephonyManager
import com.adsamcik.signalcollector.tracker.component.DataTrackerComponent
import com.adsamcik.signalcollector.tracker.data.CollectionTempData
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.google.android.gms.location.LocationResult

class CellTrackerComponent : DataTrackerComponent {

	private var context: Context? = null
	private var telephonyManager: TelephonyManager? = null
	private var subscriptionManager: SubscriptionManager? = null

	//Lint is really stupid and can't even check inside inline vals
	@SuppressLint("MissingPermission")
	override suspend fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, collectionData: MutableCollectionData, tempData: CollectionTempData) {
		val context = context ?: throw NullPointerException("Context must not be null")
		if (!Assist.isAirplaneModeEnabled(context)) {
			val telephonyManager = telephonyManager
					?: throw NullPointerException("Telephony manager must not be null")

			if (Build.VERSION.SDK_INT >= 22 && context.hasReadPhonePermission) {
				collectionData.addCell(telephonyManager, subscriptionManager!!)
			} else {
				collectionData.addCell(telephonyManager)
			}
		}
	}

	override suspend fun onEnable(context: Context) {
		this.context = context
		telephonyManager = context.telephonyManager
		if (Build.VERSION.SDK_INT >= 22) subscriptionManager = context.getSystemServiceTyped(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
	}

	override suspend fun onDisable(context: Context) {
		this.context = null
		telephonyManager = null
	}

}