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
import com.crashlytics.android.Crashlytics
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.tasks.Task

class ActivityService : IntentService("ActivityService") {

    override fun onHandleIntent(intent: Intent?) {
        val result = ActivityRecognitionResult.extractResult(intent)
        val detectedActivity = result.mostProbableActivity

        lastActivity = ActivityInfo(detectedActivity.type, detectedActivity.confidence)
        if (mBackgroundTracking && lastActivity.confidence >= REQUIRED_CONFIDENCE) {
            if (mPowerManager == null)
                mPowerManager = this.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (TrackerService.isRunning) {
                if (TrackerService.isBackgroundActivated && !canContinueBackgroundTracking(this, lastActivity.resolvedActivity)) {
                    stopService(Intent(this, TrackerService::class.java))
                    ActivityRecognitionActivity.addLineIfDebug(this, lastActivity.activityName, "stopped tracking")
                } else {
                    ActivityRecognitionActivity.addLineIfDebug(this, lastActivity.activityName, null)
                }
            } else if (canBackgroundTrack(this, lastActivity.resolvedActivity) && !TrackingLocker.isLocked.value && !mPowerManager!!.isPowerSaveMode && Assist.canTrack(this)) {
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
        private const val REQUIRED_CONFIDENCE = 75
        private const val REQUEST_CODE_PENDING_INTENT = 4561201

        var lastActivity = ActivityInfo(DetectedActivity.UNKNOWN, 0)
            private set

        private var mTask: Task<*>? = null
        private var mPowerManager: PowerManager? = null

        private var mBackgroundTracking: Boolean = false

        private var mActiveRequests = SparseArray<ActivityRequestInfo>()
        private var mMinUpdateRate = Integer.MAX_VALUE

        /**
         * Request activity updates
         *
         * @param context    context
         * @param tClass     class that requests update
         * @param updateRate update rate in seconds
         * @return true if success
         */
        fun requestActivity(context: Context, tClass: Class<*>, updateRate: Int): Boolean =
                requestActivityInternal(context, tClass, updateRate, false)

        /**
         * Request activity updates
         *
         * @param context context
         * @param tClass  class that requests update
         * @return true if success
         */
        fun requestActivity(context: Context, tClass: Class<*>): Boolean =
                requestActivityInternal(context, tClass, Preferences.getPref(context).getInt(Preferences.PREF_ACTIVITY_UPDATE_RATE, Preferences.DEFAULT_ACTIVITY_UPDATE_RATE), false)

        /**
         * Request auto tracking updates
         * Checks if autotracking is allowed
         *
         * @param context context
         * @param tClass  class that requests update
         * @return true if success
         */
        fun requestAutoTracking(context: Context, tClass: Class<*>): Boolean {
            val preferences = Preferences.getPref(context)
            if (preferences.getInt(Preferences.PREF_AUTO_TRACKING, Preferences.DEFAULT_AUTO_TRACKING) > 0) {
                if (requestActivityInternal(context, tClass, preferences.getInt(Preferences.PREF_ACTIVITY_UPDATE_RATE, Preferences.DEFAULT_ACTIVITY_UPDATE_RATE), true)) {
                    mBackgroundTracking = true
                    return true
                }
            }
            return false
        }

        /**
         * Internal activity request
         */
        private fun requestActivityInternal(context: Context, tClass: Class<*>, updateRate: Int, backgroundTracking: Boolean): Boolean {
            setMinUpdateRate(context, updateRate)
            val hash = tClass.hashCode()
            val index = mActiveRequests.indexOfKey(hash)
            if (index < 0) {
                mActiveRequests.append(hash, ActivityRequestInfo(updateRate, backgroundTracking))
            } else {
                val ari = mActiveRequests.valueAt(index)
                ari.isBackgroundTracking = backgroundTracking
                ari.updateDelay = updateRate
            }

            return true
        }

        /**
         * Removes previous activity request
         */
        fun removeActivityRequest(context: Context, tClass: Class<*>) {
            val index = mActiveRequests.indexOfKey(tClass.hashCode())
            if (index >= 0) {
                val request = mActiveRequests.valueAt(index)

                mActiveRequests.removeAt(index)
                if (request.isBackgroundTracking ||
                        (mMinUpdateRate == request.updateDelay && mActiveRequests.size() > 0)) {
                    val ari = generateExtremeRequest()
                    mBackgroundTracking = ari.isBackgroundTracking
                    setMinUpdateRate(context, ari.updateDelay)
                }
            } else {
                Crashlytics.logException(Throwable("Trying to remove class that is not subscribed (" + tClass.name + ")"))
            }

            if (mActiveRequests.size() == 0) {
                ActivityRecognition.getClient(context).removeActivityUpdates(getActivityDetectionPendingIntent(context))
                mActiveRequests = SparseArray()
            }
        }

        private fun setMinUpdateRate(context: Context, minUpdateRate: Int) {
            if (minUpdateRate < ActivityService.mMinUpdateRate) {
                ActivityService.mMinUpdateRate = minUpdateRate
                initializeActivityClient(context, minUpdateRate)
            }
        }

        /**
         * Merges all request into a single request that returns has values to satisfy all requests
         * Eg. if 2 requests have different update delays, extreme request will have the value of the smaller delay
         */
        private fun generateExtremeRequest(): ActivityRequestInfo {
            if (mActiveRequests.size() == 0)
                return ActivityRequestInfo(Integer.MIN_VALUE, false)

            var backgroundTracking = false
            var min = Integer.MAX_VALUE
            for (i in 0 until mActiveRequests.size()) {
                val ari = mActiveRequests.valueAt(i)
                if (ari.updateDelay < min)
                    min = ari.updateDelay
                backgroundTracking = backgroundTracking or ari.isBackgroundTracking
            }
            return ActivityRequestInfo(min, backgroundTracking)
        }

        private fun initializeActivityClient(context: Context, delayInS: Int): Boolean {
            return if (Assist.checkPlayServices(context)) {
                val activityRecognitionClient = ActivityRecognition.getClient(context)
                mTask = activityRecognitionClient.requestActivityUpdates((delayInS * Constants.SECOND_IN_MILLISECONDS), getActivityDetectionPendingIntent(context))
                true
            } else {
                Crashlytics.logException(Throwable("Unavailable play services"))
                false
            }
        }

        /**
         * Gets a PendingIntent to be sent for each activity detection.
         */
        private fun getActivityDetectionPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context.applicationContext, ActivityService::class.java)
            // We use FLAG_UPDATE_CURRENT so that we getPref the same pending intent back when calling
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
            if (evalActivity == 3 || evalActivity == 0 || TrackerService.isRunning || Preferences.getPref(context).getBoolean(Preferences.PREF_STOP_UNTIL_RECHARGE, false))
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
}
