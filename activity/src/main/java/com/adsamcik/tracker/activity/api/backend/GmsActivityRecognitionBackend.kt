package com.adsamcik.tracker.activity.api.backend

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.activity.ACTIVITY_LOG_SOURCE
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.logActivity
import com.adsamcik.tracker.activity.receiver.ActivityReceiver
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Play Services implementation of [ActivityRecognitionBackend].
 *
 * Wraps the GMS [ActivityRecognition] client, sending updates via PendingIntent
 * to [ActivityReceiver]. The receiver forwards results back into the SharedFlows
 * exposed by this backend.
 */
@Singleton
@SuppressLint("MissingPermission")
class GmsActivityRecognitionBackend @Inject constructor(
	@ApplicationContext private val context: Context,
) : ActivityRecognitionBackend {

	override val name: String = "Google Play Services"

	override val isAvailable: Boolean
		get() = Assist.isPlayServicesAvailable(context)

	private val activityStateLock = Any()

	@Volatile
	override var lastActivity: ActivityInfo = ActivityInfo(DetectedActivity.UNKNOWN, 0)
		private set

	@Volatile
	override var lastActivityElapsedTimeMillis: Long = 0L
		private set

	@Volatile
	private var isSubscribed = false

	@Volatile
	private var recognitionClientTask: Task<*>? = null

	@Volatile
	private var transitionClientTask: Task<*>? = null

	private val _activityUpdates = MutableSharedFlow<ActivityUpdate>(extraBufferCapacity = 16)
	override val activityUpdates: Flow<ActivityUpdate> = _activityUpdates.asSharedFlow()

	private val _transitionUpdates = MutableSharedFlow<TransitionUpdate>(extraBufferCapacity = 16)
	override val transitionUpdates: Flow<TransitionUpdate> = _transitionUpdates.asSharedFlow()

	@Synchronized
	override fun startUpdates(config: RecognitionConfig): Boolean {
		return if (isAvailable) {
			val client = ActivityRecognition.getClient(context)
			val intent = getActivityDetectionPendingIntent()

			logActivity(
				LogData(
					message = "requested activity",
					data = "delay ${config.intervalSeconds} s and transitions ${config.requestedTransitions}",
					source = ACTIVITY_LOG_SOURCE,
				),
			)

			isSubscribed = true

			if (config.intervalSeconds > 0) {
				requestActivityRecognition(client, intent, config.intervalSeconds)
			} else {
				client.removeActivityUpdates(intent)
			}

			if (config.requestedTransitions.isNotEmpty()) {
				requestActivityTransition(client, intent, config.requestedTransitions)
			} else {
				client.removeActivityTransitionUpdates(intent)
			}

			true
		} else {
			val message = "activity recognition unavailable: Google Play Services unavailable"
			logActivity(LogData(message = message, source = ACTIVITY_LOG_SOURCE))
			com.adsamcik.tracker.logger.Reporter.report(Throwable(message))
			false
		}
	}

	@Synchronized
	override fun stopUpdates() {
		if (!isSubscribed) return
		isSubscribed = false

		ActivityRecognition.getClient(context).run {
			val intent = getActivityDetectionPendingIntent()
			removeActivityUpdates(intent)
			removeActivityTransitionUpdates(intent)
		}
	}

	// Called by ActivityReceiver when it receives an activity recognition result
	internal fun onActivityResult(activity: ActivityInfo, elapsedTimeMillis: Long) {
		synchronized(activityStateLock) {
			lastActivity = activity
			lastActivityElapsedTimeMillis = elapsedTimeMillis
		}
		_activityUpdates.tryEmit(ActivityUpdate(activity, elapsedTimeMillis))
	}

	// Called by ActivityReceiver when it receives a transition-only result (no activity result)
	internal fun onTransitionActivityResult(activity: ActivityInfo, elapsedRealTimeNanos: Long) {
		synchronized(activityStateLock) {
			lastActivity = activity
			lastActivityElapsedTimeMillis = elapsedRealTimeNanos
		}
	}

	// Called by ActivityReceiver for each transition event
	internal fun onTransitionResult(updates: List<TransitionUpdate>) {
		updates.forEach { _transitionUpdates.tryEmit(it) }
	}

	private fun requestActivityRecognition(
		client: ActivityRecognitionClient,
		intent: PendingIntent,
		delayInS: Int,
	) {
		recognitionClientTask = client.requestActivityUpdates(
			delayInS * Time.SECOND_IN_MILLISECONDS,
			intent,
		).apply {
			addOnFailureListener { com.adsamcik.tracker.logger.Reporter.report(it) }
			addOnSuccessListener {
				logActivity(
					LogData(
						message = "started activity updates",
						data = "delay $delayInS s",
						source = ACTIVITY_LOG_SOURCE,
					),
				)
			}
		}
	}

	private fun requestActivityTransition(
		client: ActivityRecognitionClient,
		intent: PendingIntent,
		requestedTransitions: Collection<ActivityTransitionData>,
	) {
		val transitions = buildTransitions(requestedTransitions)
		val request = ActivityTransitionRequest(transitions)
		transitionClientTask = client.requestActivityTransitionUpdates(request, intent).apply {
			addOnFailureListener { com.adsamcik.tracker.logger.Reporter.report(it) }
			addOnSuccessListener {
				logActivity(
					LogData(
						message = "started transition updates",
						data = requestedTransitions.toString(),
						source = ACTIVITY_LOG_SOURCE,
					),
				)
			}
		}
	}

	private fun buildTransitions(
		requestedTransitions: Collection<ActivityTransitionData>,
	): List<ActivityTransition> {
		return requestedTransitions.distinct().map { buildTransition(it) }
	}

	private fun buildTransition(transition: ActivityTransitionData): ActivityTransition {
		return ActivityTransition.Builder()
			.setActivityType(transition.activity.value)
			.setActivityTransition(transition.type.value)
			.build()
	}

	private fun getActivityDetectionPendingIntent(): PendingIntent {
		val intent = Intent(context, ActivityReceiver::class.java)
		return PendingIntent.getBroadcast(
			context,
			REQUEST_CODE_PENDING_INTENT,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT.or(PendingIntent.FLAG_MUTABLE),
		)
	}

	companion object {
		private const val REQUEST_CODE_PENDING_INTENT = 4561201
	}
}
