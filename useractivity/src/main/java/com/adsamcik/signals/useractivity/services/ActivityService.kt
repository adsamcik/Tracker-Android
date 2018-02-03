package com.adsamcik.signals.useractivity.services

import android.app.IntentService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.SparseArray
import com.adsamcik.signals.useractivity.ActivityInfo
import com.adsamcik.signals.useractivity.ActivityRequestInfo
import com.adsamcik.signals.utilities.Assist
import com.adsamcik.signals.utilities.Constants
import com.adsamcik.signals.utilities.ContextValueCallback
import com.adsamcik.signals.utilities.Preferences
import com.crashlytics.android.Crashlytics
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.tasks.Task

typealias ActivityCallback = ContextValueCallback<ActivityInfo>

class ActivityService : IntentService("ActivityService") {

    override fun onHandleIntent(intent: Intent?) {
        val result = ActivityRecognitionResult.extractResult(intent)
        val detectedActivity = result.mostProbableActivity

        lastActivity = com.adsamcik.signals.useractivity.ActivityInfo(detectedActivity.type, detectedActivity.confidence)

        for (i in 0 until activeRequests.size())
            activeRequests.valueAt(i).listener!!.invoke(this, lastActivity)


        /*Log.i(TAG, "_____activities detected");
		for (DetectedActivity da: result.getProbableActivities()) {
			Log.i(TAG, ActivityInfo.getActivityName(da.getType()) + " " + da.getConfidence() + "%"
			);
		}*/
    }

    companion object {
        private val TAG = "Signals" + ActivityService::class.java.simpleName
        private const val REQUEST_CODE_PENDING_INTENT = 4561201

        var lastActivity = com.adsamcik.signals.useractivity.ActivityInfo(DetectedActivity.UNKNOWN, 0)
            private set

        private var task: Task<*>? = null

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
        fun requestActivity(context: Context, tClass: Class<*>, updateRate: Int, listener: ActivityCallback): Boolean =
                requestActivity(context, tClass.hashCode(), updateRate, listener)

        /**
         * Request activity updates
         *
         * @param context context
         * @param tClass  class that requests update
         * @return true if success
         */
        fun requestActivity(context: Context, tClass: Class<*>, listener: ActivityCallback): Boolean =
                requestActivity(context,
                        tClass.hashCode(),
                        Preferences.getPref(context).getInt(Preferences.PREF_ACTIVITY_UPDATE_RATE, Preferences.DEFAULT_ACTIVITY_UPDATE_RATE),
                        listener)

        private fun requestActivity(context: Context, hash: Int, updateRate: Int, listener: ActivityCallback): Boolean {
            setMinUpdateRate(context, updateRate)
            val index = activeRequests.indexOfKey(hash)
            if (index < 0) {
                activeRequests.append(hash, ActivityRequestInfo(updateRate, listener))
            } else {
                val ari = activeRequests.valueAt(index)
                ari.updateFrequency = updateRate
            }

            return true
        }

        fun removeActivityRequest(context: Context, tClass: Class<*>) {
            val index = activeRequests.indexOfKey(tClass.hashCode())
            if (index >= 0) {
                val updateRate = activeRequests.valueAt(index).updateFrequency

                activeRequests.removeAt(index)
                if (activeRequests.size() == 0) {
                    ActivityRecognition.getClient(context).removeActivityUpdates(getActivityDetectionPendingIntent(context))
                    minUpdateRate = Int.MAX_VALUE
                } else if (minUpdateRate == updateRate) {
                    setMinUpdateRate(context, getUpdateFrequency())
                }
            } else {
                Crashlytics.logException(Throwable("Trying to remove class that is not subscribed (" + tClass.name + ")"))
            }
        }

        private fun setMinUpdateRate(context: Context, minUpdateRate: Int) {
            Companion.minUpdateRate = minUpdateRate
            initializeActivityClient(context, minUpdateRate)
        }

        private fun getUpdateFrequency(): Int {
            if (activeRequests.size() == 0)
                throw RuntimeException("Cannog get update frequency without requests")

            var min = activeRequests.valueAt(0).updateFrequency
            (1 until activeRequests.size())
                    .asSequence()
                    .map { activeRequests.valueAt(it) }
                    .filter { it.updateFrequency < min }
                    .forEach { min = it.updateFrequency }

            return min
        }

        private fun initializeActivityClient(context: Context, delayInS: Int): Boolean {
            return if (Assist.checkPlayServices(context)) {
                val activityRecognitionClient = ActivityRecognition.getClient(context)
                task = activityRecognitionClient.requestActivityUpdates((delayInS * Constants.SECOND_IN_MILLISECONDS).toLong(), getActivityDetectionPendingIntent(context))
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
    }
}
