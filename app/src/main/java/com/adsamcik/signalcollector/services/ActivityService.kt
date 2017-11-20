package com.adsamcik.signalcollector.services

import android.app.IntentService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.SparseArray
import com.adsamcik.signalcollector.activities.ActivityRecognitionActivity
import com.adsamcik.signalcollector.enums.ResolvedActivity
import com.adsamcik.signalcollector.utility.*
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.tasks.Task
import com.google.firebase.crash.FirebaseCrash

class ActivityService : IntentService("ActivityService") {

    override fun onHandleIntent(intent: Intent?) {
        val result = ActivityRecognitionResult.extractResult(intent)
        val detectedActivity = result.mostProbableActivity

        lastActivity = ActivityInfo(detectedActivity.type, detectedActivity.confidence)
        if (backgroundTracking && lastActivity.confidence >= REQUIRED_CONFIDENCE) {
            if (powerManager == null)
                powerManager = this.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (TrackerService.isRunning) {
                if (TrackerService.isBackgroundActivated && !canContinueBackgroundTracking(this, lastActivity.resolvedActivity)) {
                    stopService(Intent(this, TrackerService::class.java))
                    ActivityRecognitionActivity.addLineIfDebug(this, lastActivity.activityName, "stopped tracking")
                } else {
                    ActivityRecognitionActivity.addLineIfDebug(this, lastActivity.activityName, null)
                }
            } else if (canBackgroundTrack(this, lastActivity.resolvedActivity) && !TrackerService.isAutoLocked && !powerManager!!.isPowerSaveMode && Assist.canTrack(this)) {
                val trackerService = Intent(this, TrackerService::class.java)
                trackerService.putExtra("backTrack", true)
                startService(trackerService)
                ActivityRecognitionActivity.addLineIfDebug(this, lastActivity.activityName, "started tracking")
            } else {
                ActivityRecognitionActivity.addLineIfDebug(this, lastActivity.activityName, null)
            }
        }

        /*Log.i(TAG, "_____activities detected");
		for (DetectedActivity da: result.getProbableActivities()) {
			Log.i(TAG, ActivityInfo.getActivityName(da.getType()) + " " + da.getConfidence() + "%"
			);
		}*/
    }

    companion object {
        private val TAG = "Signals" + ActivityService::class.java.simpleName
        private val REQUIRED_CONFIDENCE = 75
        private val REQUEST_CODE_PENDING_INTENT = 4561201

        var lastActivity = ActivityInfo(DetectedActivity.UNKNOWN, 0)
            private set

        private var task: Task<*>? = null
        private var powerManager: PowerManager? = null

        private var backgroundTracking: Boolean = false

        private var activeRequests = SparseArray<ActivityRequestInfo>()
        private var minUpdateRate = Integer.MAX_VALUE

        /**
         * Request activity updates
         *
         * @param context    context
         * @param tClass     class that requests update
         * @param updateRate update rate in seconds
         * @return true if success
         */
        fun requestActivity(context: Context, tClass: Class<*>, updateRate: Int): Boolean {
            return requestActivity(context, tClass.hashCode(), updateRate, false)
        }

        /**
         * Request activity updates
         *
         * @param context context
         * @param tClass  class that requests update
         * @return true if success
         */
        fun requestActivity(context: Context, tClass: Class<*>): Boolean {
            return requestActivity(context, tClass.hashCode(), Preferences.get(context).getInt(Preferences.PREF_ACTIVITY_UPDATE_RATE, Preferences.DEFAULT_ACTIVITY_UPDATE_RATE), false)
        }

        private fun requestActivity(context: Context, hash: Int, updateRate: Int, backgroundTracking: Boolean): Boolean {
            setMinUpdateRate(context, updateRate)
            val index = activeRequests.indexOfKey(hash)
            if (index < 0) {
                activeRequests.append(hash, ActivityRequestInfo(hash, updateRate, backgroundTracking))
            } else {
                val ari = activeRequests.valueAt(index)
                ari.isBackgroundTracking = backgroundTracking
                ari.updateFrequency = updateRate
            }

            return true
        }

        fun requestAutoTracking(context: Context, tClass: Class<*>): Boolean {
            if (!backgroundTracking && Preferences.get(context).getInt(Preferences.PREF_AUTO_TRACKING, Preferences.DEFAULT_AUTO_TRACKING) > 0) {
                if (requestActivity(context, tClass.hashCode(), Preferences.get(context).getInt(Preferences.PREF_ACTIVITY_UPDATE_RATE, Preferences.DEFAULT_ACTIVITY_UPDATE_RATE), true)) {
                    backgroundTracking = true
                    return true
                }
            }
            return false
        }

        fun removeActivityRequest(context: Context, tClass: Class<*>) {
            val index = activeRequests.indexOfKey(tClass.hashCode())
            if (index >= 0) {
                val updateRate = activeRequests.valueAt(index).updateFrequency

                activeRequests.removeAt(index)
                if (minUpdateRate == updateRate && activeRequests.size() > 0) {
                    val ari = generateExtremeRequest()
                    backgroundTracking = ari.isBackgroundTracking
                    setMinUpdateRate(context, ari.updateFrequency)
                }
            } else {
                FirebaseCrash.report(Throwable("Trying to remove class that is not subscribed (" + tClass.name + ")"))
            }

            if (activeRequests.size() == 0) {
                ActivityRecognition.getClient(context).removeActivityUpdates(getActivityDetectionPendingIntent(context))
                activeRequests = SparseArray()
            }
        }

        fun removeAutoTracking(context: Context, tClass: Class<*>) {
            if (!backgroundTracking) {
                FirebaseCrash.report(Throwable("Trying to remove auto tracking request that never existed"))
                return
            }

            removeActivityRequest(context, tClass)
            backgroundTracking = false
        }

        private fun setMinUpdateRate(context: Context, minUpdateRate: Int) {
            if (minUpdateRate < ActivityService.minUpdateRate) {
                ActivityService.minUpdateRate = minUpdateRate
                initializeActivityClient(context, minUpdateRate)
            }
        }

        private fun generateExtremeRequest(): ActivityRequestInfo {
            if (activeRequests.size() == 0)
                return ActivityRequestInfo(0, Integer.MIN_VALUE, false)

            var backgroundTracking = false
            var min = Integer.MAX_VALUE
            for (i in 0 until activeRequests.size()) {
                val ari = activeRequests.valueAt(i)
                if (ari.updateFrequency < min)
                    min = ari.updateFrequency
                backgroundTracking = backgroundTracking or ari.isBackgroundTracking
            }
            return ActivityRequestInfo(0, min, backgroundTracking)
        }

        private fun initializeActivityClient(context: Context, delayInS: Int): Boolean {
            if (Assist.isPlayServiceAvailable(context)) {
                val activityRecognitionClient = ActivityRecognition.getClient(context)
                task = activityRecognitionClient.requestActivityUpdates((delayInS * Constants.SECOND_IN_MILLISECONDS).toLong(), getActivityDetectionPendingIntent(context))
                return true
            } else {
                FirebaseCrash.report(Throwable("Unavailable play services"))
                return false
            }
        }

        /**
         * Gets a PendingIntent to be sent for each activity detection.
         */
        private fun getActivityDetectionPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context.applicationContext, ActivityService::class.java)
            // We use FLAG_UPDATE_CURRENT so that we get the same pending intent back when calling
            // requestActivityUpdates() and removeActivityUpdates().
            return PendingIntent.getService(context, REQUEST_CODE_PENDING_INTENT, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /**
         * Checks if background tracking can be activated
         *
         * @param evalActivity evaluated activity
         * @return true if background tracking can be activated
         */
        private fun canBackgroundTrack(context: Context, @ResolvedActivity evalActivity: Int): Boolean {
            if (evalActivity == 3 || evalActivity == 0 || TrackerService.isRunning || Preferences.get(context).getBoolean(Preferences.PREF_STOP_TILL_RECHARGE, false))
                return false
            val `val` = Preferences.get(context).getInt(Preferences.PREF_AUTO_TRACKING, Preferences.DEFAULT_AUTO_TRACKING)
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
            val `val` = Preferences.get(context).getInt(Preferences.PREF_AUTO_TRACKING, Preferences.DEFAULT_AUTO_TRACKING)
            return `val` == 2 || `val` == 1 && (evalActivity == 1 || evalActivity == 3)
        }
    }
}
