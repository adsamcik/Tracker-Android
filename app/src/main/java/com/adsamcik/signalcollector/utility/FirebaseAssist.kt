package com.adsamcik.signalcollector.utility


import android.content.Context

import com.google.firebase.analytics.FirebaseAnalytics

object FirebaseAssist {
    val STOP_TILL_RECHARGE_EVENT = "stop_till_recharge"
    val STOP_EVENT = "notification_stop"
    val MANUAL_UPLOAD_EVENT = "manual_upload"
    val CLEARED_DATA_EVENT = "cleared_data"

    val PARAM_SOURCE = "source"

    val autoTrackingString = "auto_tracking"
    val autoUploadString = "auto_upload"
    val uploadNotificationString = "upload_notifications"

    fun updateValue(context: Context, name: String, value: String) {
        FirebaseAnalytics.getInstance(context).setUserProperty(name, value)
    }
}
