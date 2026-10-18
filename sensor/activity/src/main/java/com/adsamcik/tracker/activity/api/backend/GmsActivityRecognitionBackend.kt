package com.adsamcik.tracker.activity.api.backend

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticFailureCode
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticLog
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticWarningCode
import com.adsamcik.tracker.diagnostics.TrackingDiagnosticFailureReason
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.receiver.ActivityReceiver
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationCleanupKey
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationCleanupKind
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationCleanupScheduler
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationCleanupStore
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
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
	private val cleanupStore: ActivityRegistrationCleanupStore,
	private val cleanupScheduler: ActivityRegistrationCleanupScheduler,
	private val dispatchers: DispatchersProvider,
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
	// A timed-out request may still finish in GMS. Its existing journal key owns removal;
	// this process-local fence prevents acknowledging that removal before the request settles.
	private val pendingRegistrationLock = Any()
	private val pendingRegistrations = mutableMapOf<PendingIntent, MutableSet<Task<*>>>()

	private val activityUpdateQueue = Channel<ActivityUpdate>(
		capacity = LIVE_UPDATE_BUFFER_CAPACITY,
		onBufferOverflow = BufferOverflow.DROP_OLDEST,
	)
	private val transitionUpdateQueue = Channel<List<TransitionUpdate>>(
		capacity = LIVE_UPDATE_BUFFER_CAPACITY,
		onBufferOverflow = BufferOverflow.DROP_OLDEST,
	)
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
		applyRegistration(config, LEGACY_IDENTITY)

	internal suspend fun applyRegistration(
		config: RecognitionConfig,
		identity: ActivityRegistrationIdentity,
	): Boolean = withContext(dispatchers.io) {
		subscriptionMutex.withLock {
			if (!isAvailable) {
				TrackerDiagnosticLog.warn(
					TrackerDiagnosticWarningCode.ACTIVITY_RECOGNITION_UNAVAILABLE,
				)
				return@withLock false
			}

			val client = ActivityRecognition.getClient(context)
			val intent = getActivityDetectionPendingIntent(identity, config)
			if (hasPendingRegistration(intent)) {
				return@withLock registrationFailed()
			}
			val key = identity.cleanupKey()
			cleanupStore.addPending(listOf(key))
			try {
				val subscriptionTask = requestSubscriptions(client, intent, config)
				if (withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) { subscriptionTask.await(); true } == true) {
					cleanupStore.removePending(listOf(key))
					true
				} else {
					registrationFailed()
				}
			} catch (e: CancellationException) {
				scheduleCleanup()
				throw e
			} catch (_: Exception) {
				registrationFailed()
			}
		}
	}

	private fun requestSubscriptions(
		client: ActivityRecognitionClient,
		intent: PendingIntent,
		config: RecognitionConfig,
	): Task<Void> {
		val recognition = trackRegistration(intent, if (config.intervalSeconds > 0) {
			requestActivityRecognition(client, intent, config.intervalSeconds)
		} else {
			client.removeActivityUpdates(intent)
		})
		val transitions = trackRegistration(intent, if (config.requestedTransitions.isNotEmpty()) {
			requestActivityTransition(client, intent, config.requestedTransitions)
		} else {
			client.removeActivityTransitionUpdates(intent)
		})
		return Tasks.whenAll(recognition, transitions)
	}

	private fun trackRegistration(intent: PendingIntent, task: Task<Void>): Task<Void> {
		if (task.isComplete) {
			return task
		}
		synchronized(pendingRegistrationLock) {
			pendingRegistrations.getOrPut(intent) { mutableSetOf() }.add(task)
		}
		task.addOnCompleteListener(IMMEDIATE_EXECUTOR) {
			synchronized(pendingRegistrationLock) {
				pendingRegistrations[intent]?.let { pending ->
					pending.remove(task)
					if (pending.isEmpty()) {
						pendingRegistrations.remove(intent)
					}
				}
			}
		}
		return task
	}

	private fun hasPendingRegistration(intent: PendingIntent): Boolean = synchronized(pendingRegistrationLock) {
		pendingRegistrations[intent]?.isNotEmpty() == true
	}

	private fun registrationFailed(): Boolean {
		TrackerDiagnosticLog.failure(
			TrackerDiagnosticFailureCode.ACTIVITY_RECOGNITION_FAILED,
			TrackingDiagnosticFailureReason.PROVIDER_FAILURE,
		)
		scheduleCleanup()
		return false
	}

	private fun scheduleCleanup() {
		try {
			cleanupScheduler.ensureScheduled()
		} catch (_: IllegalStateException) {
			// The exact journal key survives process death and is retried by the existing arbiter.
		}
	}

	/**
	 * Updates the callback-purpose extras carried by an existing PendingIntent without replacing
	 * either GMS subscription. Physical acquisition is unchanged; the broker authorization remains
	 * the durable authority that ultimately admits or rejects the refreshed callback purpose.
	 */
	internal suspend fun refreshRegistrationMetadata(
		config: RecognitionConfig,
		identity: ActivityRegistrationIdentity,
	): Boolean = subscriptionMutex.withLock {
		try {
			getActivityDetectionPendingIntent(identity, config)
			true
		} catch (error: CancellationException) {
			throw error
		} catch (_: Exception) {
			TrackerDiagnosticLog.failure(
				TrackerDiagnosticFailureCode.ACTIVITY_CALLBACK_METADATA_UPDATE_FAILED,
				TrackingDiagnosticFailureReason.PROVIDER_FAILURE,
			)
			false
		}
	}

	override suspend fun stopUpdates() = subscriptionMutex.withLock {
		removeRegistrationLocked(LEGACY_IDENTITY)
	}

	internal suspend fun removeRegistration(identity: ActivityRegistrationIdentity): Unit = withContext(dispatchers.io) {
		subscriptionMutex.withLock {
			removeRegistrationLocked(identity)
			cleanupStore.removePending(listOf(identity.cleanupKey()))
			Unit
		}
	}

	/** Removes a persisted cleanup obligation without consulting collected-data storage. */
	internal suspend fun removePendingRegistration(
		key: ActivityRegistrationCleanupKey,
	): Unit = withContext(dispatchers.io) {
		subscriptionMutex.withLock {
			val cleanup = cleanupStore.read()
			if (key !in cleanup.pending &&
				(key.kind != ActivityRegistrationCleanupKind.RELEASED_V27_STATIC || cleanup.releasedV27Checked)
			) {
				return@withLock
			}
			findPendingIntentForCleanup(key)?.let { removeRegistrationLocked(it) }
			cleanupStore.removePending(listOf(key))
			Unit
		}
	}

	private suspend fun removeRegistrationLocked(identity: ActivityRegistrationIdentity) {
		removeRegistrationLocked(getActivityDetectionPendingIntent(identity))
	}

	private suspend fun removeRegistrationLocked(intent: PendingIntent) {
		check(!hasPendingRegistration(intent)) { "Activity provider registration has not settled" }
		val client = ActivityRecognition.getClient(context)
		try {
			removeAllSubscriptions(client, intent)
			intent.cancel()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			TrackerDiagnosticLog.failure(
				TrackerDiagnosticFailureCode.ACTIVITY_RECOGNITION_FAILED,
				TrackingDiagnosticFailureReason.PROVIDER_FAILURE,
			)
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
		check(withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) { removalTask.await(); true } == true) {
			"Activity provider removal timed out"
		}
	}

	private fun ActivityRegistrationIdentity.cleanupKey() = ActivityRegistrationCleanupKey(
		kind = ActivityRegistrationCleanupKind.BROKERED,
		sourceInstanceId = sourceInstanceId,
		registrationGeneration = registrationGeneration,
	)

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

	private fun getActivityDetectionPendingIntent(
		identity: ActivityRegistrationIdentity,
		config: RecognitionConfig = RecognitionConfig(intervalSeconds = 0),
	): PendingIntent {
		val intent = activityDetectionIntent(identity, config)
		return PendingIntent.getBroadcast(
			context,
			requestCode(identity),
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT.or(PendingIntent.FLAG_MUTABLE),
		)
	}

	internal fun activityDetectionIntent(
		identity: ActivityRegistrationIdentity,
		config: RecognitionConfig,
	): Intent {
		val automaticTransitions = config.automaticTransitions.sortedWith(
			compareBy<ActivityTransitionData> { it.activity.name }.thenBy { it.type.value },
		)
		val durableTransitions = config.durableTransitions?.sortedWith(
			compareBy<ActivityTransitionData> { it.activity.name }.thenBy { it.type.value },
		)
		return brokeredActivityIntent(identity.sourceInstanceId, identity.registrationGeneration)
			.putExtra(EXTRA_SOURCE_INSTANCE_ID, identity.sourceInstanceId)
			.putExtra(EXTRA_REGISTRATION_GENERATION, identity.registrationGeneration)
			.putExtra(EXTRA_COLLECTED_DATA_EPOCH, identity.collectedDataEpoch)
			.putExtra(EXTRA_CLOCK_DOMAIN_ID, identity.clockDomainId)
			.putExtra(EXTRA_PHYSICAL_CONFIGURATION_FINGERPRINT, identity.physicalConfigurationFingerprint)
			.putExtra(EXTRA_AUTOMATIC_RECOGNITION_ELIGIBLE, config.automaticRecognitionEligible)
			.putExtra(EXTRA_DURABLE_RECOGNITION_ELIGIBLE, config.durableRecognitionEligible)
			.putExtra(EXTRA_DURABLE_TRANSITION_FILTER, durableTransitions != null)
			.putExtra(EXTRA_DURABLE_TRANSITION_ACTIVITY_TYPES,
				durableTransitions?.map { ActivityTypeMapping.toPlayServicesCode(it.activity) }?.toIntArray())
			.putExtra(EXTRA_DURABLE_TRANSITION_TYPES, durableTransitions?.map { it.type.value }?.toIntArray())
			.putExtra(
				EXTRA_AUTOMATIC_TRANSITION_ACTIVITY_TYPES,
				automaticTransitions.map { transition ->
					ActivityTypeMapping.toPlayServicesCode(transition.activity)
				}.toIntArray(),
			)
			.putExtra(
				EXTRA_AUTOMATIC_TRANSITION_TYPES,
				automaticTransitions.map { it.type.value }.toIntArray(),
			)
	}

	internal fun findPendingIntentForCleanup(
		key: ActivityRegistrationCleanupKey,
	): PendingIntent? {
		val (intent, requestCode) = when (key.kind) {
			ActivityRegistrationCleanupKind.BROKERED ->
				brokeredActivityIntent(
					requireNotNull(key.sourceInstanceId),
					requireNotNull(key.registrationGeneration),
				) to requestCode(
					requireNotNull(key.sourceInstanceId),
					requireNotNull(key.registrationGeneration),
				)
			ActivityRegistrationCleanupKind.RELEASED_V27_STATIC ->
				Intent(context, ActivityReceiver::class.java) to RELEASED_V27_REQUEST_CODE
		}
		return PendingIntent.getBroadcast(
			context,
			requestCode,
			intent,
			PendingIntent.FLAG_NO_CREATE.or(PendingIntent.FLAG_MUTABLE),
		)
	}

	private fun brokeredActivityIntent(sourceInstanceId: String, registrationGeneration: Long) =
		Intent(context, ActivityReceiver::class.java)
			.setAction("${context.packageName}.ACTIVITY_RECOGNITION.$sourceInstanceId.$registrationGeneration")

	companion object {
		internal const val PROVIDER_OPERATION_TIMEOUT_MS = 10_000L
		private val IMMEDIATE_EXECUTOR = Executor { it.run() }
		internal const val EXTRA_DURABLE_RECOGNITION_ELIGIBLE = "activity_durable_recognition_eligible"
		internal const val EXTRA_DURABLE_TRANSITION_FILTER = "activity_durable_transition_filter"
		internal const val EXTRA_DURABLE_TRANSITION_ACTIVITY_TYPES = "activity_durable_transition_activity_types"
		internal const val EXTRA_DURABLE_TRANSITION_TYPES = "activity_durable_transition_types"
		internal const val LIVE_UPDATE_BUFFER_CAPACITY = 64
		internal const val EXTRA_SOURCE_INSTANCE_ID = "activity_registration_source_instance_id"
		internal const val EXTRA_REGISTRATION_GENERATION = "activity_registration_generation"
		internal const val EXTRA_COLLECTED_DATA_EPOCH = "activity_registration_collected_data_epoch"
		internal const val EXTRA_CLOCK_DOMAIN_ID = "activity_registration_clock_domain_id"
		internal const val EXTRA_PHYSICAL_CONFIGURATION_FINGERPRINT =
			"activity_registration_physical_configuration_fingerprint"
		internal const val EXTRA_AUTOMATIC_RECOGNITION_ELIGIBLE =
			"activity_registration_automatic_recognition_eligible"
		internal const val EXTRA_AUTOMATIC_TRANSITION_ACTIVITY_TYPES =
			"activity_registration_automatic_transition_activity_types"
		internal const val EXTRA_AUTOMATIC_TRANSITION_TYPES =
			"activity_registration_automatic_transition_types"
		private val LEGACY_IDENTITY = ActivityRegistrationIdentity(
			sourceInstanceId = "legacy",
			registrationGeneration = 1L,
			collectedDataEpoch = 0L,
			clockDomainId = "LEGACY_UNQUALIFIED",
			physicalConfigurationFingerprint = "legacy-unbrokered",
		)
		internal const val RELEASED_V27_REQUEST_CODE = 4_561_201

		private fun requestCode(identity: ActivityRegistrationIdentity): Int =
			requestCode(identity.sourceInstanceId, identity.registrationGeneration)

		private fun requestCode(sourceInstanceId: String, registrationGeneration: Long): Int =
			31 * sourceInstanceId.hashCode() + registrationGeneration.hashCode()
	}
}
