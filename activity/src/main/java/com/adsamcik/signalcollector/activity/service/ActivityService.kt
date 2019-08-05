package com.adsamcik.signalcollector.activity.service

import android.app.IntentService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.SparseArray
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.ActivityRequestInfo
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.Reporter
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.common.data.GroupedActivity
import com.adsamcik.signalcollector.common.extension.powerManager
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.tracker.api.TrackerServiceApi
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
import com.adsamcik.signalcollector.tracker.service.TrackerService
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.tasks.Task

/**
 * Intent service that receives all activity updates.
 * Handles logging if it is enabled.
 */
internal class ActivityService : IntentService(this::class.java.simpleName) {
	override fun onHandleIntent(intent: Intent?) {
		val result = ActivityRecognitionResult.extractResult(intent)

		val detectedActivity = ActivityInfo(result.mostProbableActivity)

		lastActivity = detectedActivity
		lastActivityElapsedTimeMillis = Time.elapsedRealtimeMillis

		if (mBackgroundTracking && detectedActivity.confidence >= REQUIRED_CONFIDENCE) {
			if (TrackerService.isServiceRunning.value) {
				if (!TrackerService.sessionInfo.requireValue.isInitiatedByUser && !canContinueBackgroundTracking(this, detectedActivity.groupedActivity)) {
					TrackerServiceApi.stopService(this)
				}
			} else if (canBackgroundTrack(this, detectedActivity.groupedActivity) && canTrackerServiceBeStarted(powerManager.isPowerSaveMode)) {
				TrackerServiceApi.startService(this, false)
			}
		}
	}

	private fun canTrackerServiceBeStarted(isPowerSaveMode: Boolean) = !TrackerLocker.isLocked.value && !isPowerSaveMode && Assist.canTrack(this)

	/**
	 * Singleton part of the service that holds information about active requests and last known activity.
	 */
	companion object {
		private const val REQUIRED_CONFIDENCE = 75
		private const val REQUEST_CODE_PENDING_INTENT = 4561201

		private var mTask: Task<*>? = null


		/**
		 * Contains instance of last known activity
		 * Initialization value is Unknown activity with 0 confidence
		 */
		var lastActivity: ActivityInfo = ActivityInfo(DetectedActivity.UNKNOWN, 0)
			private set

		var lastActivityElapsedTimeMillis: Long = 0L
			private set





		fun initializeActivityClient(context: Context, delayInS: Int): Boolean {
			return if (Assist.checkPlayServices(context)) {
				val activityRecognitionClient = ActivityRecognition.getClient(context)
				mTask = activityRecognitionClient.requestActivityUpdates((delayInS * Time.SECOND_IN_MILLISECONDS), getActivityDetectionPendingIntent(context))
				//todo add handling of task failure
				true
			} else {
				Reporter.report(Throwable("Unavailable play services"))
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
