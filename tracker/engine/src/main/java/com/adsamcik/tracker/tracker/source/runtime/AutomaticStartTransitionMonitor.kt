package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationDemand
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationFailureCode
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationOwner
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationResult
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import android.os.SystemClock
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilitySnapshot
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjectionLane
import com.adsamcik.tracker.tracker.source.projection.TrackingJoinSpecs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Application-scoped automatic-start demand; it is independent of tracking-session lifetime. */
@Singleton
class AutomaticStartTransitionMonitor @Inject constructor(
	private val arbiter: ActivityRegistrationArbiter,
	private val sourceCallerDemandDispatcher: SourceCallerDemandDispatcher,
	private val clockDomainProvider: BootClockDomainProvider,
	private val activityProjectionLane: ActivityAutomationProjectionLane,
) {
	suspend fun reconcile(
		enabled: Boolean,
		useTransitionApi: Boolean,
		continuousIntervalSeconds: Int,
		transitions: Set<ActivityTransitionData>,
		controlAvailability: AutomaticTrackingOperationalAvailability =
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.automaticControl,
	): ActivityRegistrationResult {
		val elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
		val desiredLatencyMs = continuousIntervalSeconds.coerceAtLeast(1) * 1_000L
		// Only Activity Transition callbacks carry the live, non-replayable background-start
		// context used by automatic cold start. Continuous recognition remains a capture mechanism;
		// it must never be registered as a hidden fallback control.
		val publishedReady =
			controlAvailability as? AutomaticTrackingOperationalAvailability.Ready
		val transitionControlEnabled = enabled &&
			publishedReady != null &&
			useTransitionApi &&
			transitions.isNotEmpty()
		val boundaryBootId = clockDomainProvider.current()
		val boundaryWallTimeMs = System.currentTimeMillis()
		if (!transitionControlEnabled) {
			val retired = sourceCallerDemandDispatcher.retireAutomaticControl(
				consumerId = AUTOMATIC_CONTROL_CONSUMER,
				source = SourceKind.ACTIVITY,
				bootId = boundaryBootId,
				elapsedRealtimeNanos = elapsedRealtimeNanos,
				wallTimeMs = boundaryWallTimeMs,
				maximumAgeMs = TrackingJoinSpecs.ACTIVITY_CONTEXT_MAX_AGE_MS,
				desiredLatencyMs = desiredLatencyMs,
			)
			if (retired !is GuardedPurposeDemandResult.Applied) {
				return blockedWithoutMutation()
			}
			val cleared = arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
			return if (enabled && cleared.status != ActivityRegistrationStatus.FAILED) {
				cleared.copy(
					status = ActivityRegistrationStatus.BLOCKED,
					failureCode = cleared.failureCode
						?: ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND,
				)
			} else {
				cleared
			}
		}
		val readyIdentity = requireNotNull(publishedReady).identity
		return when (sourceCallerDemandDispatcher.dispatchAutomaticControl(
			AutomaticControlDemandDispatchRequest(
				identity = readyIdentity,
				consumerId = AUTOMATIC_CONTROL_CONSUMER,
				bootId = boundaryBootId,
				elapsedRealtimeNanos = elapsedRealtimeNanos,
				wallTimeMs = boundaryWallTimeMs,
				maximumAgeMs = TrackingJoinSpecs.ACTIVITY_CONTEXT_MAX_AGE_MS,
				desiredLatencyMs = desiredLatencyMs,
			),
		)) {
			is GuardedPurposeDemandResult.Rejected,
			GuardedPurposeDemandResult.Stale,
			-> {
				val retired = sourceCallerDemandDispatcher.retireAutomaticControl(
					AUTOMATIC_CONTROL_CONSUMER,
					SourceKind.ACTIVITY,
					boundaryBootId,
					elapsedRealtimeNanos,
					boundaryWallTimeMs,
					TrackingJoinSpecs.ACTIVITY_CONTEXT_MAX_AGE_MS,
					desiredLatencyMs,
				)
				if (retired is GuardedPurposeDemandResult.Applied) {
					arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR).blocked()
				} else {
					blockedWithoutMutation()
				}
			}
			is GuardedPurposeDemandResult.Applied -> try {
				if (!sourceCallerDemandDispatcher.isCurrent(readyIdentity)) {
					val retired = sourceCallerDemandDispatcher.retireAutomaticControl(
						AUTOMATIC_CONTROL_CONSUMER,
						SourceKind.ACTIVITY,
						boundaryBootId,
						elapsedRealtimeNanos,
						boundaryWallTimeMs,
						TrackingJoinSpecs.ACTIVITY_CONTEXT_MAX_AGE_MS,
						desiredLatencyMs,
					)
					return if (retired is GuardedPurposeDemandResult.Applied) {
						arbiter.clearDemand(
							ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
						).blocked()
					} else {
						blockedWithoutMutation()
					}
				}
				activityProjectionLane.ensureRegisteredAtLiveTail()
				if (!sourceCallerDemandDispatcher.isCurrent(readyIdentity)) {
					val retired = sourceCallerDemandDispatcher.retireAutomaticControl(
						AUTOMATIC_CONTROL_CONSUMER,
						SourceKind.ACTIVITY,
						boundaryBootId,
						elapsedRealtimeNanos,
						boundaryWallTimeMs,
						TrackingJoinSpecs.ACTIVITY_CONTEXT_MAX_AGE_MS,
						desiredLatencyMs,
					)
					return if (retired is GuardedPurposeDemandResult.Applied) {
						arbiter.clearDemand(
							ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
						).blocked()
					} else {
						blockedWithoutMutation()
					}
				}
				val registered = arbiter.setDemand(
				ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
				ActivityRegistrationDemand(
					continuousRecognitionIntervalSeconds = null,
					transitions = transitions,
				),
			)
				if (registered.status == ActivityRegistrationStatus.FAILED) {
					sourceCallerDemandDispatcher.retireAutomaticControl(
						AUTOMATIC_CONTROL_CONSUMER,
						SourceKind.ACTIVITY,
						boundaryBootId,
						elapsedRealtimeNanos,
						boundaryWallTimeMs,
						TrackingJoinSpecs.ACTIVITY_CONTEXT_MAX_AGE_MS,
						desiredLatencyMs,
					)
				}
				registered
			} catch (cancelled: CancellationException) {
				withContext(NonCancellable) {
					sourceCallerDemandDispatcher.retireAutomaticControl(
						AUTOMATIC_CONTROL_CONSUMER,
						SourceKind.ACTIVITY,
						boundaryBootId,
						elapsedRealtimeNanos,
						boundaryWallTimeMs,
						TrackingJoinSpecs.ACTIVITY_CONTEXT_MAX_AGE_MS,
						desiredLatencyMs,
					)
				}
				throw cancelled
			} catch (failure: RuntimeException) {
				withContext(NonCancellable) {
					sourceCallerDemandDispatcher.retireAutomaticControl(
						AUTOMATIC_CONTROL_CONSUMER,
						SourceKind.ACTIVITY,
						boundaryBootId,
						elapsedRealtimeNanos,
						boundaryWallTimeMs,
						TrackingJoinSpecs.ACTIVITY_CONTEXT_MAX_AGE_MS,
						desiredLatencyMs,
					)
				}
				throw failure
			}
		}
	}

	private fun ActivityRegistrationResult.blocked(): ActivityRegistrationResult =
		if (status == ActivityRegistrationStatus.FAILED) this else copy(
			status = ActivityRegistrationStatus.BLOCKED,
			failureCode = failureCode ?: ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND,
		)

	private fun blockedWithoutMutation(): ActivityRegistrationResult =
		ActivityRegistrationResult(
			status = ActivityRegistrationStatus.BLOCKED,
			snapshot = arbiter.snapshot(),
			failureCode = ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND,
		)

	private companion object {
		const val AUTOMATIC_CONTROL_CONSUMER = "app:automatic-start:activity"
	}
}
