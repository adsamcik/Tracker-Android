package com.adsamcik.tracker.activity.api.backend

import dev.tracebox.Tracebox
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.receiver.ActivityReceiver
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.stats.api.threshold.ActivityTypeMapping
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
	@ApplicationScope appScope: CoroutineScope,
) : ActivityRecognitionBackend {

	override val name: String = "Google Play Services"

	override val isAvailable: Boolean
		get() = Assist.isPlayServicesAvailable(context)

	private val activityStateLock = Any()

	@Volatile
	override var lastActivity: RecognizedActivity = RecognizedActivity.UNKNOWN
		private set

	@Volatile
	override var lastActivityElapsedTimeMillis: Long = 0L
		private set

	private val subscriptionMutex = Mutex()

	private val activityUpdateQueue = Channel<ActivityUpdate>(Channel.UNLIMITED)
	private val transitionUpdateQueue = Channel<List<TransitionUpdate>>(Channel.UNLIMITED)
	private val _activityUpdates = MutableSharedFlow<ActivityUpdate>()
	override val activityUpdates: Flow<ActivityUpdate> = _activityUpdates.asSharedFlow()

	private val _transitionUpdates = MutableSharedFlow<List<TransitionUpdate>>()
	override val transitionUpdates: Flow<List<TransitionUpdate>> = _transitionUpdates.asSharedFlow()

	init {
		appScope.launch {
			for (update in activityUpdateQueue) {
				_activityUpdates.emit(update)
			}
		}
		appScope.launch {
			for (updates in transitionUpdateQueue) {
				_transitionUpdates.emit(updates)
			}
		}
	}

	override suspend fun startUpdates(config: RecognitionConfig): Boolean =
		subscriptionMutex.withLock {
			if (!isAvailable) {
				Tracebox.log.warn("Activity recognition is unavailable")
				return@withLock false
			}

			val client = ActivityRecognition.getClient(context)
			val intent = getActivityDetectionPendingIntent()

			val recognitionTask = if (config.intervalSeconds > 0) {
				requestActivityRecognition(client, intent, config.intervalSeconds)
			} else {
				client.removeActivityUpdates(intent)
			}

			val transitionTask = if (config.requestedTransitions.isNotEmpty()) {
				requestActivityTransition(client, intent, config.requestedTransitions)
			} else {
				client.removeActivityTransitionUpdates(intent)
			}

			val subscriptionTask = Tasks.whenAll(recognitionTask, transitionTask)
			try {
				subscriptionTask.await()
				true
			} catch (e: CancellationException) {
				withContext(NonCancellable) {
					awaitTaskSettlement(subscriptionTask, e)
					rollbackSubscriptions(client, intent, e)
				}
				throw e
			} catch (e: Exception) {
				Tracebox.log.error(e, "Activity recognition failed")
				withContext(NonCancellable) {
					rollbackSubscriptions(client, intent, e)
				}
				false
			}
		}

	override suspend fun stopUpdates() = subscriptionMutex.withLock {
		val client = ActivityRecognition.getClient(context)
		val intent = getActivityDetectionPendingIntent()
		try {
			removeAllSubscriptions(client, intent)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Tracebox.log.error(e, "Activity recognition failed")
			throw e
		}
	}

	private suspend fun removeAllSubscriptions(
		client: ActivityRecognitionClient,
		intent: PendingIntent,
	) {
		val removalTask = Tasks.whenAll(
			client.removeActivityUpdates(intent),
			client.removeActivityTransitionUpdates(intent),
		)
		try {
			removalTask.await()
		} catch (e: CancellationException) {
			withContext(NonCancellable) {
				awaitTaskSettlement(removalTask, e)
			}
			throw e
		}
	}

	private suspend fun awaitTaskSettlement(task: Task<*>, originalFailure: Throwable) {
		try {
			task.await()
		} catch (settlementFailure: Exception) {
			if (settlementFailure !== originalFailure) {
				originalFailure.addSuppressed(settlementFailure)
			}
		}
	}

	private suspend fun rollbackSubscriptions(
		client: ActivityRecognitionClient,
		intent: PendingIntent,
		originalFailure: Throwable,
	) {
		try {
			removeAllSubscriptions(client, intent)
		} catch (rollbackFailure: Exception) {
			originalFailure.addSuppressed(rollbackFailure)
		}
	}

	// Called by ActivityReceiver when it receives an activity recognition result
	internal fun onActivityResult(activity: RecognizedActivity, elapsedTimeMillis: Long) {
		synchronized(activityStateLock) {
			lastActivity = activity
			lastActivityElapsedTimeMillis = elapsedTimeMillis
		}
		enqueue(
			activityUpdateQueue,
			ActivityUpdate(activity, elapsedTimeMillis, ActivityUpdateSource.RECOGNITION),
		)
	}

	// Called by ActivityReceiver when it receives a transition-only result (no activity result)
	internal fun onTransitionActivityResult(activity: RecognizedActivity, elapsedRealTimeNanos: Long) {
		val elapsedTimeMillis = elapsedRealTimeNanos / 1_000_000L
		synchronized(activityStateLock) {
			lastActivity = activity
			lastActivityElapsedTimeMillis = elapsedTimeMillis
		}
		enqueue(
			activityUpdateQueue,
			ActivityUpdate(activity, elapsedTimeMillis, ActivityUpdateSource.TRANSITION),
		)
	}

	// Called by ActivityReceiver for each transition event
	internal fun onTransitionResult(updates: List<TransitionUpdate>) {
		if (updates.isNotEmpty()) {
			enqueue(transitionUpdateQueue, updates.toList())
		}
	}

	private fun <T> enqueue(queue: Channel<T>, value: T) {
		queue.trySend(value)
	}

	private fun requestActivityRecognition(
		client: ActivityRecognitionClient,
		intent: PendingIntent,
		delayInS: Int,
	): Task<Void> {
		return client.requestActivityUpdates(
			delayInS * Time.SECOND_IN_MILLISECONDS,
			intent,
		)
	}

	private fun requestActivityTransition(
		client: ActivityRecognitionClient,
		intent: PendingIntent,
		requestedTransitions: Collection<ActivityTransitionData>,
	): Task<Void> {
		val transitions = buildTransitions(requestedTransitions)
		val request = ActivityTransitionRequest(transitions)
		return client.requestActivityTransitionUpdates(request, intent)
	}

	private fun buildTransitions(
		requestedTransitions: Collection<ActivityTransitionData>,
	): List<ActivityTransition> {
		return requestedTransitions.distinct().map { buildTransition(it) }
	}

	private fun buildTransition(transition: ActivityTransitionData): ActivityTransition {
		return ActivityTransition.Builder()
			.setActivityType(ActivityTypeMapping.toPlayServicesCode(transition.activity))
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
