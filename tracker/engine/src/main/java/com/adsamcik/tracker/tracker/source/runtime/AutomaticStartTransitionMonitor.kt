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
			val retired = retireControl(
				boundaryBootId,
				elapsedRealtimeNanos,
				boundaryWallTimeMs,
				desiredLatencyMs,
			)
			val cleared = arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
			return when {
				cleared.status == ActivityRegistrationStatus.FAILED -> cleared
				enabled || retired !is GuardedPurposeDemandResult.Applied -> cleared.blocked()
				else -> cleared
			}
		}
		val readyIdentity = requireNotNull(publishedReady).identity
		val dispatched = try {
			sourceCallerDemandDispatcher.dispatchAutomaticControl(
				AutomaticControlDemandDispatchRequest(
					identity = readyIdentity,
					consumerId = AUTOMATIC_CONTROL_CONSUMER,
					bootId = boundaryBootId,
					elapsedRealtimeNanos = elapsedRealtimeNanos,
					wallTimeMs = boundaryWallTimeMs,
					maximumAgeMs = TrackingJoinSpecs.ACTIVITY_CONTEXT_MAX_AGE_MS,
					desiredLatencyMs = desiredLatencyMs,
				),
			)
		} catch (cancelled: CancellationException) {
			withContext(NonCancellable) {
				retireControl(
					boundaryBootId,
					elapsedRealtimeNanos,
					boundaryWallTimeMs,
					desiredLatencyMs,
				)
				arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
			}
			throw cancelled
		} catch (_: Exception) {
			retireControl(
				boundaryBootId,
				elapsedRealtimeNanos,
				boundaryWallTimeMs,
				desiredLatencyMs,
			)
			return arbiter.clearDemand(
				ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			).blocked()
		}
		return when (dispatched) {
			is GuardedPurposeDemandResult.Rejected,
			GuardedPurposeDemandResult.Stale,
			-> {
				retireControl(
					boundaryBootId,
					elapsedRealtimeNanos,
					boundaryWallTimeMs,
					desiredLatencyMs,
				)
				arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR).blocked()
			}
			is GuardedPurposeDemandResult.Applied -> try {
				if (!sourceCallerDemandDispatcher.isCurrent(readyIdentity)) {
					retireControl(
						boundaryBootId,
						elapsedRealtimeNanos,
						boundaryWallTimeMs,
						desiredLatencyMs,
					)
					return arbiter.clearDemand(
						ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
					).blocked()
				}
				activityProjectionLane.ensureRegisteredAtLiveTail()
				if (!sourceCallerDemandDispatcher.isCurrent(readyIdentity)) {
					retireControl(
						boundaryBootId,
						elapsedRealtimeNanos,
						boundaryWallTimeMs,
						desiredLatencyMs,
					)
					return arbiter.clearDemand(
						ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
					).blocked()
				}
				val registered = arbiter.setDemand(
				ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
				ActivityRegistrationDemand(
					continuousRecognitionIntervalSeconds = null,
					transitions = transitions,
				),
			)
				if (registered.status == ActivityRegistrationStatus.FAILED) {
					retireControl(
						boundaryBootId,
						elapsedRealtimeNanos,
						boundaryWallTimeMs,
						desiredLatencyMs,
					)
				}
				registered
			} catch (cancelled: CancellationException) {
				withContext(NonCancellable) {
					retireControl(
						boundaryBootId,
						elapsedRealtimeNanos,
						boundaryWallTimeMs,
						desiredLatencyMs,
					)
				}
				throw cancelled
			} catch (failure: RuntimeException) {
				withContext(NonCancellable) {
					retireControl(
						boundaryBootId,
						elapsedRealtimeNanos,
						boundaryWallTimeMs,
						desiredLatencyMs,
					)
				}
				throw failure
			}
		}

		private suspend fun retireControl(
			bootId: String,
			elapsedRealtimeNanos: Long,
			wallTimeMs: Long,
			desiredLatencyMs: Long,
		): GuardedPurposeDemandResult<Unit>? = try {
			sourceCallerDemandDispatcher.retireAutomaticControl(
				AUTOMATIC_CONTROL_CONSUMER,
				SourceKind.ACTIVITY,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
				TrackingJoinSpecs.ACTIVITY_CONTEXT_MAX_AGE_MS,
				desiredLatencyMs,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			null
		}
	}

	private fun ActivityRegistrationResult.blocked(): ActivityRegistrationResult =
		if (status == ActivityRegistrationStatus.FAILED) this else copy(
			status = ActivityRegistrationStatus.BLOCKED,
			failureCode = failureCode ?: ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND,
		)

	private companion object {
		const val AUTOMATIC_CONTROL_CONSUMER = "app:automatic-start:activity"
	}
}
