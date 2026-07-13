package com.adsamcik.tracker.activity.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.ACTIVITY_LOG_SOURCE
import com.adsamcik.tracker.activity.api.backend.ActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.RecognizedActivity
import com.adsamcik.tracker.activity.api.backend.RecognitionConfig
import com.adsamcik.tracker.activity.api.backend.TransitionUpdate
import com.adsamcik.tracker.activity.logActivity
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.stats.api.threshold.ActivityTypeMapping
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

		val entryPoint = EntryPointAccessors.fromApplication(
			context.applicationContext,
			ActivityReceiverEntryPoint::class.java,
		)
		val backend = entryPoint.backend()

		if (hasActivityResult) {
			val result = requireNotNull(ActivityRecognitionResult.extractResult(intent))
			onActivityResult(result, backend)
		}

		if (hasActivityTransitionResult) {
			val result = requireNotNull(ActivityTransitionResult.extractResult(intent))
			onActivityTransitionResult(result, backend)

			if (!hasActivityResult) {
				setActivityResultFromTransition(result.transitionEvents.last(), backend)
			}
		}
	}

	private fun onActivityResult(
		result: ActivityRecognitionResult,
		backend: GmsActivityRecognitionBackend,
	) {
		val mostProbableActivity = result.mostProbableActivity
		val detectedActivity = RecognizedActivity(
			type = ActivityTypeMapping.fromPlayServicesCode(mostProbableActivity.type),
			confidence = mostProbableActivity.confidence.coerceIn(0, 100),
		)
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

	}

	/**
	 * Sets last activity from transition.
	 * Does not call callbacks as this should only update last activity for better access.
	 */
	private fun setActivityResultFromTransition(
		transition: ActivityTransitionEvent,
		backend: GmsActivityRecognitionBackend,
	) {
		val detectedActivity = RecognizedActivity(
			type = ActivityTypeMapping.fromPlayServicesCode(transition.activityType),
			confidence = TRANSITION_ACTIVITY_CONFIDENCE,
		)
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

		val transitionUpdates = result.transitionEvents.mapNotNull { event ->
			val transitionType = ActivityTransitionType.entries
				.firstOrNull { it.value == event.transitionType }
			if (transitionType == null) {
				com.adsamcik.tracker.logger.Reporter.report(
					IllegalArgumentException("Unknown activity transition type ${event.transitionType}"),
				)
				return@mapNotNull null
			}
			TransitionUpdate(
				activityType = ActivityTypeMapping.fromPlayServicesCode(event.activityType),
				transitionType = transitionType,
				elapsedRealTimeNanos = event.elapsedRealTimeNanos,
			)
		}
		backend.onTransitionResult(transitionUpdates)
	}

	companion object {
		private const val TRANSITION_ACTIVITY_CONFIDENCE = 100

		/**
		 * The most recently detected activity.  Updated on each activity or transition
		 * broadcast received.  Defaults to UNKNOWN before the first update.
		 *
		 * Written only by [ActivityReceiver.onReceive].
		 */
		@Volatile
		var lastActivity: RecognizedActivity = RecognizedActivity.UNKNOWN
			internal set

		/**
		 * Starts activity recognition via the Hilt-provided backend.
		 * Callers should check [android.Manifest.permission.ACTIVITY_RECOGNITION] before calling.
		 */
		suspend fun startActivityRecognition(
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
		suspend fun stopActivityRecognition(context: Context) {
			val backend = EntryPointAccessors.fromApplication(
				context.applicationContext,
				ActivityReceiverEntryPoint::class.java,
			).backend()
			backend.stopUpdates()
		}
	}
}
