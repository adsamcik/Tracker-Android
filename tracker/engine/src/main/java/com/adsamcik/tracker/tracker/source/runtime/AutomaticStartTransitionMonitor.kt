package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationDemand
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationFailureCode
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationOwner
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationResult
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import android.os.SystemClock
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.projection.TrackingJoinSpecs
import javax.inject.Inject
import javax.inject.Singleton

/** Application-scoped automatic-start demand; it is independent of tracking-session lifetime. */
@Singleton
class AutomaticStartTransitionMonitor @Inject constructor(
	private val arbiter: ActivityRegistrationArbiter,
	private val sourceBroker: SourceBroker,
	private val clockDomainProvider: BootClockDomainProvider,
) {
	suspend fun reconcile(
		enabled: Boolean,
		useTransitionApi: Boolean,
		continuousIntervalSeconds: Int,
		transitions: Set<ActivityTransitionData>,
	): ActivityRegistrationResult {
		val elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
		val desiredLatencyMs = continuousIntervalSeconds.coerceAtLeast(1) * 1_000L
		// Only Activity Transition callbacks carry the live, non-replayable background-start
		// context used by automatic cold start. Continuous recognition remains a capture mechanism;
		// it must never be registered as a hidden fallback control.
		val transitionControlEnabled = enabled && useTransitionApi && transitions.isNotEmpty()
		val durableDemand = sourceBroker.replaceAutomaticControlDemand(
			consumerId = AUTOMATIC_CONTROL_CONSUMER,
			source = SourceKind.ACTIVITY,
			enabled = transitionControlEnabled,
			bootId = clockDomainProvider.current(),
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			wallTimeMs = System.currentTimeMillis(),
			maximumAgeMs = TrackingJoinSpecs.ACTIVITY_CONTEXT_MAX_AGE_MS,
			desiredLatencyMs = desiredLatencyMs,
		)
		return if (!transitionControlEnabled || durableDemand == null) {
			val cleared = arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
			if (enabled && cleared.status != ActivityRegistrationStatus.FAILED) {
				cleared.copy(
					status = ActivityRegistrationStatus.BLOCKED,
					failureCode = cleared.failureCode
						?: ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND,
				)
			} else {
				cleared
			}
		} else {
			arbiter.setDemand(
				ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
				ActivityRegistrationDemand(
					continuousRecognitionIntervalSeconds = null,
					transitions = transitions,
				),
			)
		}
	}

	private companion object {
		const val AUTOMATIC_CONTROL_CONSUMER = "app:automatic-start:activity"
	}
}
