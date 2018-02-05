package com.adsamcik.signals.tracking.automation

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.adsamcik.signals.tracking.services.TrackerService
import com.adsamcik.signals.useractivity.ActivityInfo
import com.adsamcik.signals.useractivity.ActivityRecognitionDebug
import com.adsamcik.signals.useractivity.services.ActivityService
import com.adsamcik.signals.base.Assist
import com.adsamcik.signals.base.Preferences
import com.adsamcik.signals.base.enums.ResolvedActivity

object BackgroundActivityWatcher {
    private const val REQUIRED_CONFIDENCE = 75
    private var instance: BackgroundActivityWatcher? = null
    private var powerManager: PowerManager? = null

    fun startWatching(context: Context) {
        if (instance != null)
            throw RuntimeException("Trying to start watching while watching is already active")

        if (powerManager == null)
            powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        ActivityService.requestActivity(context, this.javaClass, onUpdate)
    }

    fun stopWatching(context: Context) {
        if(instance == null)
            throw RuntimeException("Trying to stop watching while watching is not active")

        ActivityService.removeActivityRequest(context, this.javaClass)
    }


    private val onUpdate = fun(context: Context, activityInfo: ActivityInfo) {
        if (activityInfo.confidence >= REQUIRED_CONFIDENCE) {
            if (TrackerService.isRunning) {
                if (TrackerService.isBackgroundActivated && !canContinueBackgroundTracking(context, ActivityService.lastActivity.resolvedActivity)) {
                    context.stopService(Intent(context, TrackerService::class.java))
                    ActivityRecognitionDebug.addLineIfDebug(context, ActivityService.lastActivity.activityName, "stopped tracking")
                } else {
                    ActivityRecognitionDebug.addLineIfDebug(context, ActivityService.lastActivity.activityName, null)
                }
            } else if (canBackgroundTrack(context, ActivityService.lastActivity.resolvedActivity) &&
                    !TrackerService.isAutoLocked &&
                    !powerManager!!.isPowerSaveMode &&
                    Assist.canTrack(context)) {

                val trackerService = Intent(context, TrackerService::class.java)
                trackerService.putExtra("backTrack", true)
                context.startService(trackerService)
                ActivityRecognitionDebug.addLineIfDebug(context, ActivityService.lastActivity.activityName, "started tracking")
            } else {
                ActivityRecognitionDebug.addLineIfDebug(context, ActivityService.lastActivity.activityName, null)
            }
        }
    }

    /**
     * Checks if background tracking can be activated
     *
     * @param evalActivity evaluated activity
     * @return true if background tracking can be activated
     */
    private fun canBackgroundTrack(context: Context, @ResolvedActivity evalActivity: Int): Boolean {
        if (evalActivity == 3 || evalActivity == 0 || TrackerService.isRunning || Preferences.getPref(context).getBoolean(Preferences.PREF_STOP_TILL_RECHARGE, false))
            return false
        val `val` = Preferences.getPref(context).getInt(Preferences.PREF_AUTO_TRACKING, Preferences.DEFAULT_AUTO_TRACKING)
        return `val` != 0 && (`val` == evalActivity || `val` > evalActivity)
    }

    /**
     * Checks if background tracking should stop
     *
     * @param evalActivity evaluated activity
     * @return true if background tracking can continue running
     */
    private fun canContinueBackgroundTracking(context: Context, @ResolvedActivity evalActivity: Int): Boolean {
        if (evalActivity == 0)
            return false
        val `val` = Preferences.getPref(context).getInt(Preferences.PREF_AUTO_TRACKING, Preferences.DEFAULT_AUTO_TRACKING)
        return `val` == 2 || `val` == 1 && (evalActivity == 1 || evalActivity == 3)
    }
}