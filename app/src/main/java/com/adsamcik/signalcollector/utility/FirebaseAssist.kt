package com.adsamcik.signalcollector.utility


import android.content.Context

import com.google.firebase.analytics.FirebaseAnalytics

object FirebaseAssist {
    const val STOP_TILL_RECHARGE_EVENT = "stop_till_recharge"
    const val STOP_EVENT = "notification_stop"
    const val MANUAL_UPLOAD_EVENT = "manual_upload"
    const val CLEARED_DATA_EVENT = "cleared_data"

    const val PARAM_SOURCE = "source"

    const val autoTrackingString = "auto_tracking"
    const val autoUploadString = "auto_upload"
    const val uploadNotificationString = "upload_notifications"

    fun updateValue(context: Context, name: String, value: String) {
        FirebaseAnalytics.getInstance(context).setUserProperty(name, value)
    }
}
