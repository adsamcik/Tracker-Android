package com.adsamcik.tracker.activity.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_CLOCK_DOMAIN_ID
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_COLLECTED_DATA_EPOCH
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_PHYSICAL_CONFIGURATION_FINGERPRINT
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_REGISTRATION_GENERATION
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_SOURCE_INSTANCE_ID
import com.adsamcik.tracker.activity.api.backend.RecognizedActivity
import com.adsamcik.tracker.activity.api.backend.TransitionUpdate
import com.adsamcik.tracker.activity.api.ingress.ActivityDurableSelection
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEventIngress
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.stats.api.threshold.ActivityTypeMapping
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransitionResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
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

		val entryPoint = EntryPointAccessors.fromApplication(
			context.applicationContext,
			ActivityReceiverEntryPoint::class.java,
		)
		val receivedElapsedRealtimeMillis = Time.elapsedRealtimeMillis
		val delivery = parseDelivery(
			registrationIdentity = intent.registrationIdentity(),
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
			try {
				val admission = withTimeout(DURABLE_HANDOFF_TIMEOUT_MS) {
					entryPoint.eventIngress().admit(delivery.batch)
				}
				if (admission.isDurable) {
					delivery.publishTo(entryPoint.backend(), admission.durableSelection)
				}
			} catch (_: Throwable) {
				// A failed handoff must not leak a non-durable in-process effect.
			} finally {
				pendingResult.finish()
			}
		}
	}

	private fun parseDelivery(
		registrationIdentity: ActivityRegistrationIdentity?,
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
		private const val DURABLE_HANDOFF_TIMEOUT_MS = 8_000L

		@Volatile
		var lastActivity: RecognizedActivity = RecognizedActivity.UNKNOWN
			internal set
	}
}
