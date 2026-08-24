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
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.stats.api.threshold.ActivityTypeMapping
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransitionResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Dependencies needed by the manifest BroadcastReceiver in a cold process. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ActivityReceiverEntryPoint {
	fun backend(): GmsActivityRecognitionBackend
	fun eventIngress(): ActivityRecognitionEventIngress

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
		val delivery = parseDelivery(
			registrationIdentity = intent.registrationIdentity(),
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
		val pendingResult = goAsync()
		entryPoint.applicationScope().launch {
			runBoundedActivityCallbackWork(
				receivedElapsedRealtimeMillis = receivedElapsedRealtimeMillis,
				currentElapsedRealtimeMillis = { Time.elapsedRealtimeMillis },
				work = {
					admitActivityCallbackWithRetry(
						hasActivityPermission = { context.hasActivityPermission },
						admit = { entryPoint.eventIngress().admit(delivery.batch) },
						onDurable = { admission ->
							delivery.publishTo(
								entryPoint.backend(),
								admission.durableSelection,
							)
						},
					)
				},
				// Android supplies a PendingResult for a dispatched broadcast. Robolectric's direct
				// receiver invocation may return null, so keep cleanup safe in that environment.
				finish = { pendingResult?.finish() },
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
	admit: suspend () -> ActivityIngressResult,
	onDurable: (ActivityIngressResult) -> Unit,
) {
	var retryDelayMs = ACTIVITY_CALLBACK_INITIAL_RETRY_DELAY_MS
	while (hasActivityPermission()) {
		val admission = admit()
		when (admission.status) {
			ActivityIngressStatus.DURABLE -> {
				onDurable(admission)
				return
			}

			ActivityIngressStatus.REJECTED -> return
			ActivityIngressStatus.RETRYABLE -> {
				delay(retryDelayMs)
				retryDelayMs = (retryDelayMs * 2L)
					.coerceAtMost(ACTIVITY_CALLBACK_MAX_RETRY_DELAY_MS)
			}
		}
	}
}

internal suspend fun runBoundedActivityCallbackWork(
	receivedElapsedRealtimeMillis: Long,
	currentElapsedRealtimeMillis: () -> Long,
	work: suspend () -> Unit,
	finish: () -> Unit,
) {
	try {
		val remainingWorkMs = remainingActivityCallbackWorkBudgetMillis(
			receivedElapsedRealtimeMillis,
			currentElapsedRealtimeMillis(),
		)
		if (remainingWorkMs > 0L) withTimeout(remainingWorkMs) { work() }
	} catch (_: TimeoutCancellationException) {
		// A failed handoff must not leak a non-durable in-process effect.
	} catch (cancellation: CancellationException) {
		throw cancellation
	} catch (_: Throwable) {
		// A failed handoff must not leak a non-durable in-process effect.
	} finally {
		finish()
	}
}

internal const val ACTIVITY_CALLBACK_WORK_BUDGET_MS = 7_500L
private const val ACTIVITY_CALLBACK_INITIAL_RETRY_DELAY_MS = 50L
private const val ACTIVITY_CALLBACK_MAX_RETRY_DELAY_MS = 1_000L
