package com.adsamcik.tracker.activity.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ACTIVITY_LOG_SOURCE
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.activity.api.backend.ActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.RecognitionConfig
import com.adsamcik.tracker.activity.api.backend.TransitionUpdate
import com.adsamcik.tracker.activity.logActivity
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint for accessing the activity recognition backend from
 * the BroadcastReceiver (which cannot use constructor injection).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ActivityReceiverEntryPoint {
	fun backend(): GmsActivityRecognitionBackend
}

/**
 * BroadcastReceiver that receives PendingIntent callbacks from Google Play
 * Services activity recognition.
 *
 * This receiver is a thin relay: it parses the GMS intent and forwards the
 * results to [GmsActivityRecognitionBackend], which exposes them as Flows
 * via the [ActivityRecognitionBackend] interface.
 *
 * It also notifies [ActivityRequestManager] for backward-compatible callback
 * dispatching to existing request holders.
 */
internal class ActivityReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val hasActivityResult = ActivityRecognitionResult.hasResult(intent)
		val hasActivityTransitionResult = ActivityTransitionResult.hasResult(intent)

		logActivity(
			LogData(
				message = "Received activity update with activity:$hasActivityResult and activity transition:$hasActivityTransitionResult",
				source = ACTIVITY_LOG_SOURCE,
			),
		)

		val backend = EntryPointAccessors.fromApplication(
			context.applicationContext,
			ActivityReceiverEntryPoint::class.java,
		).backend()

		if (hasActivityResult) {
			val result = requireNotNull(ActivityRecognitionResult.extractResult(intent))
			onActivityResult(context, result, backend)
		}

		if (hasActivityTransitionResult) {
			val result = requireNotNull(ActivityTransitionResult.extractResult(intent))
			onActivityTransitionResult(context, result, backend)

			if (!hasActivityResult) {
				setActivityResultFromTransition(result.transitionEvents.last(), backend)
			}
		}
	}

	private fun onActivityResult(
		context: Context,
		result: ActivityRecognitionResult,
		backend: GmsActivityRecognitionBackend,
	) {
		val detectedActivity = ActivityInfo(result.mostProbableActivity)
		val elapsedTimeMillis = Time.elapsedRealtimeMillis

		Companion.lastActivity = detectedActivity
		backend.onActivityResult(detectedActivity, elapsedTimeMillis)

		logActivity(
			LogData(
				message = "new activity",
				data = detectedActivity,
				source = ACTIVITY_LOG_SOURCE,
			),
		)

		ActivityRequestManager.onActivityUpdate(context, detectedActivity, elapsedTimeMillis)
	}

	/**
	 * Sets last activity from transition.
	 * Does not call callbacks as this should only update last activity for better access.
	 */
	private fun setActivityResultFromTransition(
		transition: ActivityTransitionEvent,
		backend: GmsActivityRecognitionBackend,
	) {
		val detectedActivity = ActivityInfo(transition.activityType, TRANSITION_ACTIVITY_CONFIDENCE)
		Companion.lastActivity = detectedActivity
		backend.onTransitionActivityResult(detectedActivity, transition.elapsedRealTimeNanos)

		logActivity(
			LogData(
				message = "new activity from transition",
				data = detectedActivity,
				source = ACTIVITY_LOG_SOURCE,
			),
		)
	}

	private fun onActivityTransitionResult(
		context: Context,
		result: ActivityTransitionResult,
		backend: GmsActivityRecognitionBackend,
	) {
		result.transitionEvents.forEach {
			logActivity(
				LogData(
					message = "new transition",
					data = it,
					source = ACTIVITY_LOG_SOURCE,
				),
			)
		}

		val transitionUpdates = result.transitionEvents.map { event ->
			TransitionUpdate(
				activityType = event.activityType,
				transitionType = event.transitionType,
				elapsedRealTimeNanos = event.elapsedRealTimeNanos,
			)
		}
		backend.onTransitionResult(transitionUpdates)

		ActivityRequestManager.onActivityTransition(context, result)
	}

	companion object {
		private const val TRANSITION_ACTIVITY_CONFIDENCE = 100

		/**
		 * The most recently detected activity.  Updated on each activity or transition
		 * broadcast received.  Defaults to UNKNOWN before the first update.
		 *
		 * Written only by [ActivityReceiver.onReceive]; read by callers like
		 * [ActivityRequestManager] that need last-known activity without a context.
		 */
		@Volatile
		var lastActivity: ActivityInfo = ActivityInfo(DetectedActivity.UNKNOWN, 0)
			internal set

		/**
		 * Starts activity recognition via the Hilt-provided backend.
		 * Callers should check [android.Manifest.permission.ACTIVITY_RECOGNITION] before calling.
		 */
		fun startActivityRecognition(
			context: Context,
			interval: Int,
			transitions: Collection<ActivityTransitionData>,
		): Boolean {
			val backend = EntryPointAccessors.fromApplication(
				context.applicationContext,
				ActivityReceiverEntryPoint::class.java,
			).backend()
			return backend.startUpdates(RecognitionConfig(interval, transitions))
		}

		/**
		 * Stops activity recognition via the Hilt-provided backend.
		 */
		fun stopActivityRecognition(context: Context) {
			val backend = EntryPointAccessors.fromApplication(
				context.applicationContext,
				ActivityReceiverEntryPoint::class.java,
			).backend()
			backend.stopUpdates()
		}
	}
}

