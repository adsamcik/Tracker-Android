package com.adsamcik.signalcollector.tracker.component.data

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.common.extension.getSystemServiceTyped
import com.adsamcik.signalcollector.common.extension.telephonyManager
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.google.android.gms.location.LocationResult

class CellTrackerComponent : DataTrackerComponent {

	private var context: Context? = null
	private var telephonyManager: TelephonyManager? = null
	private var subscriptionManager: SubscriptionManager? = null

	override fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, distance: Float, activity: ActivityInfo, collectionData: MutableCollectionData) {
		val context = context ?: throw NullPointerException("Context must not be null")
		if (!Assist.isAirplaneModeEnabled(context)) {
			val telephonyManager = telephonyManager
					?: throw NullPointerException("Telephony manager must not be null")

			if (Build.VERSION.SDK_INT >= 22 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
				collectionData.addCell(telephonyManager, subscriptionManager!!)
			} else {
				collectionData.addCell(telephonyManager)
			}
		}
	}

	override fun onEnable(context: Context) {
		this.context = context
		telephonyManager = context.telephonyManager
		if (Build.VERSION.SDK_INT >= 22) subscriptionManager = context.getSystemServiceTyped(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
	}

	override fun onDisable(context: Context) {
		this.context = null
		telephonyManager = null
	}

}