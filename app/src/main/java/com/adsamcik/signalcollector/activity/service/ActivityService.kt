package com.adsamcik.signalcollector.activity.service

import android.app.IntentService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.SparseArray
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.ActivityInfo
import com.adsamcik.signalcollector.activity.ActivityRequestInfo
import com.adsamcik.signalcollector.activity.GroupedActivity
import com.adsamcik.signalcollector.app.Assist
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.debug.activity.ActivityRecognitionActivity
import com.adsamcik.signalcollector.misc.extension.powerManager
import com.adsamcik.signalcollector.misc.extension.startForegroundService
import com.adsamcik.signalcollector.misc.extension.stopService
import com.adsamcik.signalcollector.preference.Preferences
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
import com.adsamcik.signalcollector.tracker.service.TrackerService
import com.crashlytics.android.Crashlytics
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.tasks.Task
import kotlin.reflect.KClass

/**
 * Intent service that receives all activity updates.
 * Handles logging if it is enabled.
 */
class ActivityService : IntentService("ActivityService") {
	private lateinit var mPowerManager: PowerManager

	override fun onHandleIntent(intent: Intent?) {
		val result = ActivityRecognitionResult.extractResult(intent)

		mPowerManager = powerManager

		val detectedActivity = ActivityInfo(result.mostProbableActivity)
		lastActivity = detectedActivity
		if (mBackgroundTracking && detectedActivity.confidence >= REQUIRED_CONFIDENCE) {
			if (TrackerService.isServiceRunning.value) {
				if (TrackerService.isBackgroundActivated && !canContinueBackgroundTracking(this, detectedActivity.groupedActivity)) {
					stopService<TrackerService>()
					ActivityRecognitionActivity.addLineIfDebug(this, result.time, detectedActivity, "stopped tracking")
				} else {
					ActivityRecognitionActivity.addLineIfDebug(this, result.time, detectedActivity, null)
				}
			} else if (canBackgroundTrack(this, detectedActivity.groupedActivity) &&
					!TrackerLocker.isLocked.value &&
					!mPowerManager.isPowerSaveMode &&
					Assist.canTrack(this)) {

				startForegroundService<TrackerService> {
					putExtra("backTrack", true)
				}

				ActivityRecognitionActivity.addLineIfDebug(this, result.time, detectedActivity, "started tracking")
			} else {
				ActivityRecognitionActivity.addLineIfDebug(this, result.time, detectedActivity, null)
			}
		}

		/*Log.i(TAG, "_____activities detected");
		for (DetectedActivity da: result.getProbableActivities()) {
			Log.i(TAG, ActivityInfo.getActivityName(da.getType()) + " " + da.getConfidence() + "%"
			);
		}*/
	}

	/**
	 * Singleton part of the service that holds information about active requests and last known activity.
	 */
	companion object {
		private val TAG = "Signals" + ActivityService::class.java.simpleName
		private const val REQUIRED_CONFIDENCE = 75
		private const val REQUEST_CODE_PENDING_INTENT = 4561201

		private var mTask: Task<*>? = null

		private var mBackgroundTracking: Boolean = false

		private var mActiveRequests = SparseArray<ActivityRequestInfo>()
		private var mMinUpdateRate = Integer.MAX_VALUE

		/**
		 * Contains instance of last known activity
		 * Initialization value is Unknown activity with 0 confidence
		 */
		var lastActivity: ActivityInfo = ActivityInfo(DetectedActivity.UNKNOWN, 0)
			private set

		/**
		 * Request activity updates
		 *
		 * @param context    context
		 * @param tClass     class that requests update
		 * @param updateRate update rate in seconds
		 * @return true if success
		 */
		fun requestActivity(context: Context, tClass: KClass<*>, updateRate: Int): Boolean =
				requestActivityInternal(context, tClass, updateRate, false)

		/**
		 * Request activity updates
		 *
		 * @param context context
		 * @param tClass  class that requests update
		 * @return true if success
		 */
		fun requestActivity(context: Context, tClass: KClass<*>): Boolean {
			val preferences = Preferences.getPref(context)
			val resources = context.resources
			val key = resources.getString(R.string.settings_activity_freq_key)
			val default = resources.getString(R.string.settings_activity_freq_default).toInt()
			return requestActivityInternal(context, tClass, preferences.getInt(key, default), false)
		}

		fun requestAutoTracking(context: Context, tClass: KClass<*>) {
			val resources = context.resources
			val keyUpdateFrequency = resources.getString(R.string.settings_activity_freq_key)
			val defaultUpdateFrequency = resources.getString(R.string.settings_activity_freq_default).toInt()
			val updateRate = Preferences.getPref(context).getInt(keyUpdateFrequency, defaultUpdateFrequency)
			requestAutoTracking(context, tClass, updateRate)
		}

		/**
		 * Request auto tracking updates
		 * Checks if autotracking is allowed
		 *
		 * @param context context
		 * @param tClass  class that requests update
		 * @return true if success
		 */
		fun requestAutoTracking(context: Context, tClass: KClass<*>, updateRate: Int): Boolean {
			val preferences = Preferences.getPref(context)
			val resources = context.resources
			val keyAutoTracking = resources.getString(R.string.settings_tracking_activity_key)
			val defaultAutoTracking = resources.getString(R.string.settings_tracking_activity_default).toInt()

			if (preferences.getInt(keyAutoTracking, defaultAutoTracking) > 0) {
				if (requestActivityInternal(context, tClass, updateRate, true)) {
					mBackgroundTracking = true
					return true
				}
			}
			return false
		}

		/**
		 * Internal activity request
		 */
		private fun requestActivityInternal(context: Context, tClass: KClass<*>, updateRate: Int, backgroundTracking: Boolean): Boolean {
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
		fun removeActivityRequest(context: Context, tClass: KClass<*>) {
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
				Crashlytics.logException(Throwable("Trying to remove class that is not subscribed (" + tClass.simpleName + ")"))
			}

			if (mActiveRequests.size() == 0) {
				ActivityRecognition.getClient(context).removeActivityUpdates(getActivityDetectionPendingIntent(context))
				mActiveRequests = SparseArray()
			}
		}

		private fun setMinUpdateRate(context: Context, minUpdateRate: Int) {
			if (minUpdateRate < mMinUpdateRate) {
				mMinUpdateRate = minUpdateRate
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
				//todo add handling of task failure
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
		 * @param groupedActivity evaluated activity
		 * @return true if background tracking can be activated
		 */
		private fun canBackgroundTrack(context: Context, groupedActivity: GroupedActivity): Boolean {
			val resources = context.resources
			val keyStopUntilRecharge = resources.getString(R.string.settings_disabled_recharge_key)
			val defaultStopUntilRecharge = resources.getString(R.string.settings_disabled_recharge_default).toBoolean()

			if (groupedActivity == GroupedActivity.UNKNOWN ||
					groupedActivity == GroupedActivity.STILL ||
					TrackerService.isServiceRunning.value ||
					Preferences.getPref(context).getBoolean(keyStopUntilRecharge, defaultStopUntilRecharge))
				return false

			val keyAutoTracking = resources.getString(R.string.settings_tracking_activity_key)
			val defaultAutoTracking = resources.getString(R.string.settings_tracking_activity_default).toInt()

			val preference = Preferences.getPref(context).getInt(keyAutoTracking, defaultAutoTracking)
			val prefActivity = GroupedActivity.values()[preference]
			return prefActivity != GroupedActivity.STILL && (prefActivity == groupedActivity || prefActivity.ordinal > groupedActivity.ordinal)
		}

		/**
		 * Checks if background tracking should stop
		 *
		 * @param groupedActivity evaluated activity
		 * @return true if background tracking can continue running
		 */
		private fun canContinueBackgroundTracking(context: Context, groupedActivity: GroupedActivity): Boolean {
			if (groupedActivity == GroupedActivity.STILL)
				return false

			val resources = context.resources
			val keyAutoTracking = resources.getString(R.string.settings_tracking_activity_key)
			val defaultAutoTracking = resources.getString(R.string.settings_tracking_activity_default).toInt()
			val preference = Preferences.getPref(context).getInt(keyAutoTracking, defaultAutoTracking)
			val prefActivity = GroupedActivity.values()[preference]
			return prefActivity == GroupedActivity.IN_VEHICLE ||
					(prefActivity == GroupedActivity.ON_FOOT &&
							(groupedActivity == GroupedActivity.ON_FOOT || groupedActivity == GroupedActivity.UNKNOWN))
		}
	}
}
