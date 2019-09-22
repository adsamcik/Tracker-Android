package com.adsamcik.tracker.activity.service

import android.app.IntentService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.activity.logActivity
import com.adsamcik.tracker.common.Assist
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.data.ActivityInfo
import com.adsamcik.tracker.common.data.DetectedActivity
import com.adsamcik.tracker.common.debug.LogData
import com.adsamcik.tracker.common.debug.Reporter
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.tasks.Task

/**
 * Intent service that receives all activity updates.
 * Handles logging if it is enabled.
 */
internal class ActivityService : IntentService(this::class.java.simpleName) {
	override fun onHandleIntent(intent: Intent?) {

		if (ActivityRecognitionResult.hasResult(intent)) {
			val result = requireNotNull(ActivityRecognitionResult.extractResult(intent))
			onActivityResult(result)
		}

		if (ActivityTransitionResult.hasResult(intent)) {
			val result = requireNotNull(ActivityTransitionResult.extractResult(intent))
			onActivityTransitionResult(result)
		}
	}

	private fun onActivityResult(result: ActivityRecognitionResult) {
		val detectedActivity = ActivityInfo(result.mostProbableActivity)
		val elapsedTimeMillis = Time.elapsedRealtimeMillis

		lastActivity = detectedActivity
		lastActivityElapsedTimeMillis = elapsedTimeMillis

		logActivity(LogData(message = "new activity", data = detectedActivity))

		ActivityRequestManager.onActivityUpdate(this, detectedActivity, elapsedTimeMillis)
	}

	private fun onActivityTransitionResult(result: ActivityTransitionResult) {
		result.transitionEvents.forEach {
			logActivity(LogData(message = "new transition", data = it))
		}

		ActivityRequestManager.onActivityTransition(this, result)
	}


	/**
	 * Singleton part of the service that holds information about active requests and last known activity.
	 */
	companion object {
		private const val REQUEST_CODE_PENDING_INTENT = 4561201

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
						LogData(
								message = "requested activity",
								data = "delay $delayInS s and transitions $requestedTransitions"
						)
				)

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
				Reporter.report(Throwable("Unavailable play services"))
				false
			}
		}

		private fun requestActivityRecognition(
				client: ActivityRecognitionClient,
				intent: PendingIntent,
				delayInS: Int
		) {
			recognitionClientTask = client.requestActivityUpdates(
					delayInS * Time.SECOND_IN_MILLISECONDS, intent
			)
					.apply {
						addOnFailureListener { Reporter.report(it) }
						addOnSuccessListener {
							logActivity(
									LogData(
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
				addOnFailureListener { Reporter.report(it) }
				addOnSuccessListener {
					logActivity(
							LogData(
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


		fun stopActivityRecognition(context: Context) {
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
			val intent = Intent(context.applicationContext, ActivityService::class.java)
			// We use FLAG_UPDATE_CURRENT so that we get the same pending intent back when calling
			// requestActivityUpdates() and removeActivityUpdates().
			return PendingIntent.getService(
					context, REQUEST_CODE_PENDING_INTENT, intent,
					PendingIntent.FLAG_UPDATE_CURRENT
			)
		}
	}
}

