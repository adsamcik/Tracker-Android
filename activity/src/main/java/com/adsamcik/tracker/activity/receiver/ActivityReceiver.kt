package com.adsamcik.tracker.activity.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.activity.logActivity
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Reporter
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.tasks.Task

/**
 * Intent service that receives all activity updates.
 * Handles logging if it is enabled.
 */
internal class ActivityReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent?) {
		val hasActivityResult = ActivityRecognitionResult.hasResult(intent)
		if (hasActivityResult) {
			val result = requireNotNull(ActivityRecognitionResult.extractResult(intent))
			onActivityResult(context, result)
		}

		if (ActivityTransitionResult.hasResult(intent)) {
			val result = requireNotNull(ActivityTransitionResult.extractResult(intent))
			onActivityTransitionResult(context, result)

			if (!hasActivityResult) {
				setActivityResultFromTransition(result.transitionEvents.last())
			}
		}
	}

	private fun onActivityResult(context: Context, result: ActivityRecognitionResult) {
		val detectedActivity = ActivityInfo(result.mostProbableActivity)
		val elapsedTimeMillis = Time.elapsedRealtimeMillis

		lastActivity = detectedActivity
		lastActivityElapsedTimeMillis = elapsedTimeMillis

		logActivity(
				com.adsamcik.tracker.logger.LogData(
						message = "new activity",
						data = detectedActivity
				)
		)

		ActivityRequestManager.onActivityUpdate(context, detectedActivity, elapsedTimeMillis)
	}

	/**
	 * Sets last activity from transition.
	 * Does not call callbacks as this should only update last activity for better access.
	 */
	private fun setActivityResultFromTransition(transition: ActivityTransitionEvent) {
		val detectedActivity = ActivityInfo(transition.activityType, TRANSITION_ACTIVITY_CONFIDENCE)
		lastActivity = detectedActivity
		lastActivityElapsedTimeMillis = transition.elapsedRealTimeNanos

		logActivity(
				com.adsamcik.tracker.logger.LogData(
						message = "new activity from transition",
						data = detectedActivity
				)
		)
	}

	private fun onActivityTransitionResult(context: Context, result: ActivityTransitionResult) {
		result.transitionEvents.forEach {
			logActivity(com.adsamcik.tracker.logger.LogData(message = "new transition", data = it))
		}

		ActivityRequestManager.onActivityTransition(context, result)
	}


	/**
	 * Singleton part of the service that holds information about active requests and last known activity.
	 */
	companion object {
		private const val REQUEST_CODE_PENDING_INTENT = 4561201
		private const val ACTIVITY_INTENT = "com.adsamcik.tracker.ACTIVITY_RESULT"
		private const val TRANSITION_ACTIVITY_CONFIDENCE = 100

		private var recognitionClientTask: Task<*>? = null
		private var transitionClientTask: Task<*>? = null


		/**
		 * Contains instance of last known activity
		 * Initialization value is Unknown activity with 0 confidence
		 */
		var lastActivity: ActivityInfo = ActivityInfo(DetectedActivity.UNKNOWN, 0)
			private set

		var lastActivityElapsedTimeMillis: Long = 0L
			private set

		private val receiver: BroadcastReceiver by lazy { ActivityReceiver() }
		private var isSubscribed = false


		/**
		 * Start activity recognition
		 *
		 * @param context Context
		 * @param delayInS Delay between collections in seconds
		 * @param requestedTransitions Transitions to subscribe to
		 *
		 * @return true if successfully started
		 */
		@Synchronized
		fun startActivityRecognition(
				context: Context,
				delayInS: Int,
				requestedTransitions: Collection<ActivityTransitionData>
		): Boolean {
			return if (Assist.isPlayServicesAvailable(context)) {
				val client = ActivityRecognition.getClient(context)

				val intent = getActivityDetectionPendingIntent(context)

				logActivity(
						com.adsamcik.tracker.logger.LogData(
								message = "requested activity",
								data = "delay $delayInS s and transitions $requestedTransitions"
						)
				)

				context.registerReceiver(receiver, IntentFilter(ACTIVITY_INTENT))
				isSubscribed = true

				if (delayInS > 0) {
					requestActivityRecognition(client, intent, delayInS)
				} else {
					client.removeActivityUpdates(intent)
				}

				if (requestedTransitions.isNotEmpty()) {
					requestActivityTransition(client, intent, requestedTransitions)
				} else {
					client.removeActivityTransitionUpdates(intent)
				}

				true
			} else {
				com.adsamcik.tracker.logger.Reporter.report(Throwable("Unavailable play services"))
				false
			}
		}

		private fun requestActivityRecognition(
				client: ActivityRecognitionClient,
				intent: PendingIntent,
				delayInS: Int
		) {
			recognitionClientTask = client.requestActivityUpdates(
					delayInS * Time.SECOND_IN_MILLISECONDS,
					intent
			)
					.apply {
						addOnFailureListener { com.adsamcik.tracker.logger.Reporter.report(it) }
						addOnSuccessListener {
							logActivity(
									com.adsamcik.tracker.logger.LogData(
											message = "started activity updates",
											data = "delay $delayInS s"
									)
							)
						}
					}
		}

		private fun requestActivityTransition(
				client: ActivityRecognitionClient,
				intent: PendingIntent,
				requestedTransitions: Collection<ActivityTransitionData>
		) {
			val transitions = buildTransitions(requestedTransitions)
			val request = ActivityTransitionRequest(transitions)
			transitionClientTask = client.requestActivityTransitionUpdates(request, intent).apply {
				addOnFailureListener { com.adsamcik.tracker.logger.Reporter.report(it) }
				addOnSuccessListener {
					logActivity(
							com.adsamcik.tracker.logger.LogData(
									message = "started transition updates",
									data = requestedTransitions.toString()
							)
					)
				}
			}
		}

		private fun buildTransitions(requestedTransitions: Collection<ActivityTransitionData>): List<ActivityTransition> {
			return requestedTransitions.distinct().map { buildTransition(it) }
		}

		private fun buildTransition(transition: ActivityTransitionData): ActivityTransition {
			return ActivityTransition.Builder()
					.setActivityType(transition.activity.value)
					.setActivityTransition(transition.type.value)
					.build()
		}


		/**
		 * Stop activity recognition
		 */
		fun stopActivityRecognition(context: Context) {
			if (!isSubscribed) return
			isSubscribed = false

			context.unregisterReceiver(receiver)

			ActivityRecognition.getClient(context).run {
				val intent = getActivityDetectionPendingIntent(context)
				removeActivityUpdates(intent)
				removeActivityTransitionUpdates(intent)
			}
		}

		/**
		 * Gets a PendingIntent to be sent for each activity detection.
		 */
		private fun getActivityDetectionPendingIntent(context: Context): PendingIntent {
			val intent = Intent(ACTIVITY_INTENT)
			// We use FLAG_UPDATE_CURRENT so that we get the same pending intent back when calling
			// requestActivityUpdates() and removeActivityUpdates().
			return PendingIntent.getBroadcast(
					context,
					REQUEST_CODE_PENDING_INTENT,
					intent,
					PendingIntent.FLAG_UPDATE_CURRENT
			)
		}
	}
}

