package com.adsamcik.signalcollector.receivers


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.adsamcik.signalcollector.jobs.DisableTillRechargeJob
import com.adsamcik.signalcollector.services.TrackerService
import com.adsamcik.signalcollector.utility.FirebaseAssist
import com.google.firebase.analytics.FirebaseAnalytics

class NotificationReceiver : BroadcastReceiver() {
    private val TAG = "SignalsNotifiReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val value = intent.getIntExtra(ACTION_STRING, -1)
        val params = Bundle()
        params.putString(FirebaseAssist.PARAM_SOURCE, "notification")
        when (value) {
            0 -> {
                DisableTillRechargeJob.stopTillRecharge(context)
                FirebaseAnalytics.getInstance(context).logEvent(FirebaseAssist.STOP_TILL_RECHARGE_EVENT, params)
            }
            1 -> {
                FirebaseAnalytics.getInstance(context).logEvent(FirebaseAssist.STOP_EVENT, params)
                context.stopService(Intent(context, TrackerService::class.java))
            }
            else -> Log.w(TAG, "Unknown value " + value)
        }
    }

    companion object {
        val ACTION_STRING = "action"
    }
}
