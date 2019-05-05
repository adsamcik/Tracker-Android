package com.adsamcik.signalcollector.common.misc


import android.content.Context

import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Singleton class that provides access to assist functions and variables for Firebase
 */
object FirebaseAssist {
	const val STOP_TILL_RECHARGE_EVENT: String = "stop_till_recharge"
	const val STOP_EVENT: String = "notification_stop"
	const val MANUAL_UPLOAD_EVENT: String = "manual_upload"
	const val CLEARED_DATA_EVENT: String = "cleared_data"

	const val PARAM_SOURCE: String = "source"

	const val autoTrackingString: String = "auto_tracking"
	const val autoUploadString: String = "auto_upload"
	const val uploadNotificationString: String = "upload_notifications"

	fun updateValue(context: Context, name: String, value: String) {
		FirebaseAnalytics.getInstance(context).setUserProperty(name, value)
	}
}
