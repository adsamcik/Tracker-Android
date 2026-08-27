package com.adsamcik.tracker.activity.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_AUTOMATIC_RECOGNITION_ELIGIBLE
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_AUTOMATIC_TRANSITION_ACTIVITY_TYPES
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_AUTOMATIC_TRANSITION_TYPES
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_CLOCK_DOMAIN_ID
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_COLLECTED_DATA_EPOCH
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_PHYSICAL_CONFIGURATION_FINGERPRINT
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_REGISTRATION_GENERATION
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_SOURCE_INSTANCE_ID
import com.adsamcik.tracker.activity.api.backend.RecognizedActivity
import com.adsamcik.tracker.activity.api.backend.TransitionUpdate
import com.adsamcik.tracker.activity.api.ingress.ActivityDurableSelection
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressResult
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressStatus
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEventIngress
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.activity.api.registration.ActivityCallbackAdmissionBarrier
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.startup.TrackingAdmissionStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.stats.api.threshold.ActivityTypeMapping
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransitionResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.tracebox.Tracebox
import dev.tracebox.api.public
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Dependencies needed by the manifest BroadcastReceiver in a cold process. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ActivityReceiverEntryPoint {
	fun backend(): GmsActivityRecognitionBackend
	fun eventIngress(): ActivityRecognitionEventIngress
	fun trackingStartupGate(): TrackingStartupGate
	fun callbackAdmissionBarrier(): ActivityCallbackAdmissionBarrier
	fun callbackRetryOwner(): ActivityCallbackRetryOwner

	@ApplicationScope
	fun applicationScope(): CoroutineScope
}

/**
 * Receives Google Play Services activity callbacks.
 *
 * A callback is first admitted to durable source ingress. The legacy process-local flows and
 * last-activity cache are updated only after every supported event in the delivery is durable.
 */
internal class ActivityReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val hasActivityResult = ActivityRecognitionResult.hasResult(intent)
		val hasTransitionResult = ActivityTransitionResult.hasResult(intent)
		if (!hasActivityResult && !hasTransitionResult) return
		// There is no public app-usable permission-change callback on all supported Android
		// versions. Check at the external callback boundary so a revocation cannot be followed by a
		// durable Activity/control write while the service's periodic reconciliation catches up.
		if (!context.hasActivityPermission) return

		val receivedElapsedRealtimeMillis = Time.elapsedRealtimeMillis
		val entryPoint = EntryPointAccessors.fromApplication(
			context.applicationContext,
			ActivityReceiverEntryPoint::class.java,
		)
		val registrationIdentity = intent.registrationIdentity()
		val callbackPermit = registrationIdentity?.let {
			entryPoint.callbackAdmissionBarrier().tryEnter(it)
		}
		if (registrationIdentity != null && callbackPermit == null) return
		val delivery = runCatching {
			parseDelivery(
				registrationIdentity = registrationIdentity,
				automaticRecognitionEligible = intent.getBooleanExtra(
					EXTRA_AUTOMATIC_RECOGNITION_ELIGIBLE,
					false,
				),
				automaticTransitions = intent.automaticTransitions(),
				activityResult = if (hasActivityResult) {
					requireNotNull(ActivityRecognitionResult.extractResult(intent))
				} else {
					null
				},
				transitionResult = if (hasTransitionResult) {
					requireNotNull(ActivityTransitionResult.extractResult(intent))
				} else {
					null
				},
				receivedElapsedRealtimeMillis = receivedElapsedRealtimeMillis,
				receivedWallTimeMs = System.currentTimeMillis(),
			)
		}.getOrElse {
			// An invalid provider payload is a permanent callback-boundary rejection.
			callbackPermit?.complete()
			return
		}
		val pendingResult = goAsync()
		val retryOwnership = ActivityCallbackRetryOwnership(
			batch = delivery.batch,
			owner = entryPoint.callbackRetryOwner(),
		)
		var terminallyOwnedOrRejected = false
		entryPoint.applicationScope().launch {
			runBoundedActivityCallbackWork(
				receivedElapsedRealtimeMillis = receivedElapsedRealtimeMillis,
				currentElapsedRealtimeMillis = { Time.elapsedRealtimeMillis },
				work = {
					terminallyOwnedOrRejected = processActivityCallbackWithDurableFallback(
						hasActivityPermission = { context.hasActivityPermission },
						admit = {
							admitActivityCallbackAfterStartup(
								reconcileStartup = {
									entryPoint.trackingStartupGate().reconcileAdmission()
								},
								// Resolve Room-backed ingress only after the pre-Room gate is Ready.
								admit = { entryPoint.eventIngress().admit(delivery.batch) },
							)
						},
						onDurable = { admission ->
							delivery.publishTo(
								entryPoint.backend(),
								admission.durableSelection,
							)
						},
						retryOwnership = retryOwnership,
					)
				},
				onWorkBudgetExhausted = {
					runCatching { retryOwnership.retain() }
				},
				// Android supplies a PendingResult for a dispatched broadcast. Robolectric's direct
				// receiver invocation may return null, so keep cleanup safe in that environment.
				finish = {
					try {
						finalizeActivityCallbackAuthority(
							terminallyOwnedOrRejected = terminallyOwnedOrRejected,
							durablyRetryOwned = retryOwnership.isDurablyRetained,
							gapCode = retryOwnership.lastFailureCode,
							recordGap = retryOwnership::recordGap,
							completePermit = { callbackPermit?.complete() },
						)
					} finally {
						pendingResult?.finish()
					}
				},
			)
		}
	}

	private fun parseDelivery(
		registrationIdentity: ActivityRegistrationIdentity?,
		automaticRecognitionEligible: Boolean,
		automaticTransitions: Set<ActivityTransitionData>,
		activityResult: ActivityRecognitionResult?,
		transitionResult: ActivityTransitionResult?,
		receivedElapsedRealtimeMillis: Long,
		receivedWallTimeMs: Long,
	): ParsedActivityDelivery {
		val recognizedActivity = activityResult?.mostProbableActivity?.let { detected ->
			RecognizedActivity(
				type = ActivityTypeMapping.fromPlayServicesCode(detected.type),
				confidence = detected.confidence.coerceIn(0, 100),
			)
		}
		val recognitionElapsedNanos = activityResult?.elapsedRealtimeMillis
			?.coerceAtLeast(0L)
			?.times(NANOS_PER_MILLISECOND)
			?: receivedElapsedRealtimeMillis * NANOS_PER_MILLISECOND
		val transitionEvents = transitionResult?.transitionEvents.orEmpty()
		val transitionUpdates = transitionEvents.mapNotNull { event ->
			val transitionType = ActivityTransitionType.entries
				.firstOrNull { it.value == event.transitionType }
				?: return@mapNotNull null
			TransitionUpdate(
				activityType = ActivityTypeMapping.fromPlayServicesCode(event.activityType),
				transitionType = transitionType,
				elapsedRealTimeNanos = event.elapsedRealTimeNanos,
			)
		}
		return ParsedActivityDelivery(
			batch = ActivityRecognitionEvidenceBatch(
				receivedElapsedRealtimeNanos = receivedElapsedRealtimeMillis * NANOS_PER_MILLISECOND,
				receivedWallTimeMs = receivedWallTimeMs,
				registrationIdentity = registrationIdentity,
				automaticRecognitionEligible = automaticRecognitionEligible,
				automaticTransitions = automaticTransitions,
				recognitions = recognizedActivity?.let { activity ->
					listOf(
						ActivityRecognitionEvidence(
							activityType = activity.type,
							confidencePercent = activity.confidence,
							providerElapsedRealtimeNanos = recognitionElapsedNanos,
						),
					)
				}.orEmpty(),
				transitions = transitionUpdates.map { update ->
					ActivityTransitionEvidence(
						activityType = update.activityType,
						transitionType = update.transitionType,
						providerElapsedRealtimeNanos = update.elapsedRealTimeNanos,
					)
				},
			),
			recognizedActivity = recognizedActivity,
			// Preserve the legacy backend contract while the durable event keeps provider time.
			recognitionElapsedRealtimeMillis = receivedElapsedRealtimeMillis,
			transitionUpdates = transitionUpdates,
			publishTransitionAsActivity = activityResult == null,
		)
	}

	private fun Intent.registrationIdentity(): ActivityRegistrationIdentity? {
		val sourceInstanceId = getStringExtra(EXTRA_SOURCE_INSTANCE_ID) ?: return null
		if (!hasExtra(EXTRA_REGISTRATION_GENERATION) ||
			!hasExtra(EXTRA_COLLECTED_DATA_EPOCH) ||
			getStringExtra(EXTRA_CLOCK_DOMAIN_ID) == null ||
			getStringExtra(EXTRA_PHYSICAL_CONFIGURATION_FINGERPRINT) == null
		) return null
		return runCatching {
			ActivityRegistrationIdentity(
				sourceInstanceId = sourceInstanceId,
				registrationGeneration = getLongExtra(EXTRA_REGISTRATION_GENERATION, -1L),
				collectedDataEpoch = getLongExtra(EXTRA_COLLECTED_DATA_EPOCH, -1L),
				clockDomainId = requireNotNull(getStringExtra(EXTRA_CLOCK_DOMAIN_ID)),
				physicalConfigurationFingerprint = requireNotNull(
					getStringExtra(EXTRA_PHYSICAL_CONFIGURATION_FINGERPRINT),
				),
			)
		}.getOrNull()
	}

	private fun Intent.automaticTransitions(): Set<ActivityTransitionData> {
		val activityTypes = getIntArrayExtra(EXTRA_AUTOMATIC_TRANSITION_ACTIVITY_TYPES)
			?: return emptySet()
		val transitionTypes = getIntArrayExtra(EXTRA_AUTOMATIC_TRANSITION_TYPES)
			?: return emptySet()
		if (activityTypes.size != transitionTypes.size) return emptySet()
		return activityTypes.indices.mapNotNull { index ->
			val transitionType = ActivityTransitionType.entries.firstOrNull { type ->
				type.value == transitionTypes[index]
			} ?: return@mapNotNull null
			ActivityTransitionData(
				activity = ActivityTypeMapping.fromPlayServicesCode(activityTypes[index]),
				type = transitionType,
			)
		}.toSet()
	}

	private data class ParsedActivityDelivery(
		val batch: ActivityRecognitionEvidenceBatch,
		val recognizedActivity: RecognizedActivity?,
		val recognitionElapsedRealtimeMillis: Long,
		val transitionUpdates: List<TransitionUpdate>,
		val publishTransitionAsActivity: Boolean,
	) {
		fun publishTo(
			backend: GmsActivityRecognitionBackend,
			selection: ActivityDurableSelection,
		) {
			recognizedActivity?.takeIf { 0 in selection.recognitionIndexes }?.let { activity ->
				lastActivity = activity
				backend.onActivityResult(activity, recognitionElapsedRealtimeMillis)
			}
			val selectedTransitions = transitionUpdates.filterIndexed { index, _ ->
				index in selection.transitionIndexes
			}
			if (selectedTransitions.isNotEmpty()) backend.onTransitionResult(selectedTransitions)
			selectedTransitions.lastOrNull()?.takeIf { publishTransitionAsActivity }?.let { transition ->
				val activity = RecognizedActivity(
					type = transition.activityType,
					confidence = TRANSITION_ACTIVITY_CONFIDENCE,
				)
				lastActivity = activity
				backend.onTransitionActivityResult(activity, transition.elapsedRealTimeNanos)
			}
		}
	}

	companion object {
		private const val TRANSITION_ACTIVITY_CONFIDENCE = 100
		private const val NANOS_PER_MILLISECOND = 1_000_000L

		@Volatile
		var lastActivity: RecognizedActivity = RecognizedActivity.UNKNOWN
			internal set
	}
}

/** Keeps construction of the Room-backed ingress behind the cold-process startup boundary. */
internal suspend fun admitActivityCallbackAfterStartup(
	reconcileStartup: suspend () -> TrackingAdmissionStartupResult,
	admit: suspend () -> ActivityIngressResult,
): ActivityIngressResult = if (reconcileStartup() is TrackingAdmissionStartupResult.Ready) {
	admit()
} else {
	ActivityIngressResult.retryable(0, 0, STARTUP_RECOVERY_NOT_READY)
}

/**
 * Leaves 500 ms inside the receiver's declared eight-second callback budget. The start path
 * may use at most another 250 ms of that reserve for cancellation compensation.
 */
internal fun remainingActivityCallbackWorkBudgetMillis(
	receivedElapsedRealtimeMillis: Long,
	currentElapsedRealtimeMillis: Long,
): Long {
	if (currentElapsedRealtimeMillis <= receivedElapsedRealtimeMillis) {
		return ACTIVITY_CALLBACK_WORK_BUDGET_MS
	}
	val elapsedMs = currentElapsedRealtimeMillis - receivedElapsedRealtimeMillis
	return (ACTIVITY_CALLBACK_WORK_BUDGET_MS - elapsedMs).coerceAtLeast(0L)
}

/** Retries only this callback's exact delivery; its stable identity makes re-admission safe. */
internal suspend fun admitActivityCallbackWithRetry(
	hasActivityPermission: () -> Boolean,
	eventCount: Int,
	admit: suspend () -> ActivityIngressResult,
	onDurable: (ActivityIngressResult) -> Unit,
	onRetryableWithoutDurableOwnership: suspend () -> Unit = {},
): ActivityCallbackTerminalDisposition {
	var retryDelayMs = ACTIVITY_CALLBACK_INITIAL_RETRY_DELAY_MS
	while (hasActivityPermission()) {
		val admission = admit()
		when (admission.status) {
			ActivityIngressStatus.DURABLE -> {
				onDurable(admission)
				return ActivityCallbackTerminalDisposition.DURABLE
			}

			ActivityIngressStatus.REJECTED ->
				return ActivityCallbackTerminalDisposition.PERMANENTLY_REJECTED
			ActivityIngressStatus.RETRYABLE -> {
				if (admission.admittedCount + admission.duplicateCount == eventCount) {
					// WAL/receipts own every member even though downstream recovery still needs work.
					return ActivityCallbackTerminalDisposition.DURABLY_RETRY_OWNED
				}
				onRetryableWithoutDurableOwnership()
				delay(retryDelayMs)
				retryDelayMs = (retryDelayMs * 2L)
					.coerceAtMost(ACTIVITY_CALLBACK_MAX_RETRY_DELAY_MS)
			}
		}
	}
	// Permission revocation is an authoritative permanent rejection at the callback boundary.
	return ActivityCallbackTerminalDisposition.PERMANENTLY_REJECTED
}

internal enum class ActivityCallbackTerminalDisposition {
	DURABLE,
	DURABLY_RETRY_OWNED,
	PERMANENTLY_REJECTED,
}

/**
 * Races normal Room admission only against a delayed source-local durability fallback.
 *
 * Fast callbacks do no extra filesystem work. A retryable or hung Room path is read-verifiably
 * retained before the process-local callback permit can drain. Retained callbacks are removed when
 * this same receiver later reaches a terminal result; a crash between Room commit and removal is a
 * harmless duplicate because ingress identity is stable.
 */
internal suspend fun processActivityCallbackWithDurableFallback(
	hasActivityPermission: () -> Boolean,
	admit: suspend () -> ActivityIngressResult,
	onDurable: (ActivityIngressResult) -> Unit,
	retryOwnership: ActivityCallbackRetryOwnership,
): Boolean = coroutineScope {
	val watchdog = launch {
		delay(ACTIVITY_CALLBACK_RETRY_WATCHDOG_MS)
		retryOwnership.retain()
	}
	var terminal = false
	try {
		admitActivityCallbackWithRetry(
			hasActivityPermission = hasActivityPermission,
			eventCount = retryOwnership.eventCount,
			admit = admit,
			onDurable = onDurable,
			onRetryableWithoutDurableOwnership = { retryOwnership.retain() },
		)
		terminal = true
		true
	} finally {
		withContext(NonCancellable) {
			watchdog.cancelAndJoin()
			if (terminal) {
				retryOwnership.discardIfRetained()
			} else {
				// Timeout, throw, or application-scope cancellation cannot release the callback
				// authority permit unless this exact envelope has become durable retry work.
				runCatching { retryOwnership.retain() }
			}
		}
	}
}

internal class ActivityCallbackRetryOwnership(
	private val batch: ActivityRecognitionEvidenceBatch,
	private val owner: ActivityCallbackRetryOwner,
) {
	private val mutex = Mutex()
	@Volatile private var retainedId: String? = null
	@Volatile private var retainFailureCode: ActivityCallbackGapCode? = null

	val eventCount: Int get() = batch.eventCount
	val isDurablyRetained: Boolean get() = retainedId != null
	val lastFailureCode: ActivityCallbackGapCode
		get() = retainFailureCode ?: ActivityCallbackGapCode.HANDOFF_BUDGET_EXHAUSTED

	suspend fun retain() {
		mutex.withLock {
			if (retainedId != null) return
			try {
				retainedId = owner.retain(batch)
			} catch (error: ActivityCallbackRetryStoreException) {
				retainFailureCode = error.code
				throw error
			} catch (error: Throwable) {
				retainFailureCode = ActivityCallbackGapCode.RETRY_STORAGE_UNAVAILABLE
				throw error
			}
		}
	}

	suspend fun recordGap(): Boolean = owner.recordGap(batch, lastFailureCode)

	suspend fun discardIfRetained() {
		mutex.withLock {
			val id = retainedId ?: return
			runCatching { owner.resolve(id) }
			retainedId = null
		}
	}
}

internal enum class ActivityCallbackFinalizationDisposition {
	ROOM_TERMINAL,
	DURABLE_RETRY,
	TERMINAL_GAP_RECORDED,
	TERMINAL_TELEMETRY_ONLY,
}

/**
 * Ends process-local callback authority even when both Room and the bounded retry spool fail.
 *
 * A small gap receipt is best effort because ENOSPC may also prevent it. The stable telemetry code
 * is always emitted, and privacy closure/provider retirement are never wedged by unreachable
 * process-local authority.
 */
internal suspend fun finalizeActivityCallbackAuthority(
	terminallyOwnedOrRejected: Boolean,
	durablyRetryOwned: Boolean,
	gapCode: ActivityCallbackGapCode,
	recordGap: suspend () -> Boolean,
	completePermit: () -> Unit,
): ActivityCallbackFinalizationDisposition = try {
	when {
		terminallyOwnedOrRejected -> ActivityCallbackFinalizationDisposition.ROOM_TERMINAL
		durablyRetryOwned -> ActivityCallbackFinalizationDisposition.DURABLE_RETRY
		else -> {
			val gapRecorded = runCatching { recordGap() }.getOrDefault(false)
			Tracebox.log.warn(
				TrackerTraceboxTemplates.ACTIVITY_CALLBACK_TERMINAL_GAP,
				public(gapCode.telemetryCode),
			)
			if (gapRecorded) {
				ActivityCallbackFinalizationDisposition.TERMINAL_GAP_RECORDED
			} else {
				ActivityCallbackFinalizationDisposition.TERMINAL_TELEMETRY_ONLY
			}
		}
	}
} finally {
	completePermit()
}

internal suspend fun runBoundedActivityCallbackWork(
	receivedElapsedRealtimeMillis: Long,
	currentElapsedRealtimeMillis: () -> Long,
	work: suspend () -> Unit,
	onWorkBudgetExhausted: suspend () -> Unit = {},
	finish: suspend () -> Unit,
) {
	try {
		val remainingWorkMs = remainingActivityCallbackWorkBudgetMillis(
			receivedElapsedRealtimeMillis,
			currentElapsedRealtimeMillis(),
		)
		if (remainingWorkMs > 0L) {
			withTimeout(remainingWorkMs) { work() }
		} else {
			withContext(NonCancellable) { onWorkBudgetExhausted() }
		}
	} catch (_: TimeoutCancellationException) {
		// A failed handoff must not leak a non-durable in-process effect.
	} catch (cancellation: CancellationException) {
		throw cancellation
	} catch (_: Throwable) {
		// A failed handoff must not leak a non-durable in-process effect.
	} finally {
		withContext(NonCancellable) { finish() }
	}
}

internal const val ACTIVITY_CALLBACK_WORK_BUDGET_MS = 7_500L
private const val STARTUP_RECOVERY_NOT_READY = "STARTUP_RECOVERY_NOT_READY"
private const val ACTIVITY_CALLBACK_INITIAL_RETRY_DELAY_MS = 50L
private const val ACTIVITY_CALLBACK_MAX_RETRY_DELAY_MS = 1_000L
private const val ACTIVITY_CALLBACK_RETRY_WATCHDOG_MS = 3_000L
