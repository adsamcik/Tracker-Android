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
import kotlinx.coroutines.withTimeoutOrNull

/** Application-scoped automatic-start demand; it is independent of tracking-session lifetime. */
@Singleton
class AutomaticStartTransitionMonitor @Inject internal constructor(
	private val arbiter: ActivityRegistrationArbiter,
	private val sourceCallerDemandDispatcher: SourceCallerDemandDispatcher,
	private val clockDomainProvider: BootClockDomainProvider,
	private val activityProjectionLane: ActivityAutomationProjectionLane,
	private val mutationLeaseGuard: TrackingPurposeMutationLeaseGuard? = null,
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
		val guard = mutationLeaseGuard
			?: return reconcileEnabledUnderHeldLease(
				readyIdentity,
				boundaryBootId,
				elapsedRealtimeNanos,
				boundaryWallTimeMs,
				desiredLatencyMs,
				transitions,
			)
		val guardedMutation = guard.mutateAutomaticIfCurrent(readyIdentity) {
			reconcileEnabledUnderHeldLease(
				readyIdentity,
				boundaryBootId,
				elapsedRealtimeNanos,
				boundaryWallTimeMs,
				desiredLatencyMs,
				transitions,
			)
		}
		return when (guardedMutation) {
			is AmbientRadioLeaseMutation.Applied -> guardedMutation.value
			AmbientRadioLeaseMutation.Stale -> withContext(NonCancellable) {
				compensateRejectedActivation(
					boundaryBootId,
					elapsedRealtimeNanos,
					boundaryWallTimeMs,
					desiredLatencyMs,
				)
			}
		}
	}

	private suspend fun reconcileEnabledUnderHeldLease(
		readyIdentity: com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity,
		boundaryBootId: String,
		elapsedRealtimeNanos: Long,
		boundaryWallTimeMs: Long,
		desiredLatencyMs: Long,
		transitions: Set<ActivityTransitionData>,
	): ActivityRegistrationResult {
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
			compensateAfterFailure(
				cancelled,
				boundaryBootId,
				elapsedRealtimeNanos,
				boundaryWallTimeMs,
				desiredLatencyMs,
			)
			throw cancelled
		} catch (_: Exception) {
			return withContext(NonCancellable) {
				compensateRejectedActivation(
					boundaryBootId,
					elapsedRealtimeNanos,
					boundaryWallTimeMs,
					desiredLatencyMs,
				)
			}
		}
		return when (dispatched) {
			is GuardedPurposeDemandResult.Rejected,
			is GuardedPurposeDemandResult.RejectedAfterCleanup,
			GuardedPurposeDemandResult.Stale,
			-> {
				withContext(NonCancellable) {
					compensateRejectedActivation(
						boundaryBootId,
						elapsedRealtimeNanos,
						boundaryWallTimeMs,
						desiredLatencyMs,
					)
				}
			}
			is GuardedPurposeDemandResult.Applied -> try {
				activityProjectionLane.ensureRegisteredAtLiveTail()
				val registered = arbiter.setDemand(
					ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
					ActivityRegistrationDemand(
						continuousRecognitionIntervalSeconds = null,
						transitions = transitions,
					),
				)
				if (!registered.isAcceptedAutomaticControl()) {
					return withContext(NonCancellable) {
						compensateRejectedActivation(
							boundaryBootId,
							elapsedRealtimeNanos,
							boundaryWallTimeMs,
							desiredLatencyMs,
							registered,
						)
					}
				}
				registered
			} catch (cancelled: CancellationException) {
				compensateAfterFailure(
					cancelled,
					boundaryBootId,
					elapsedRealtimeNanos,
					boundaryWallTimeMs,
					desiredLatencyMs,
				)
				throw cancelled
			} catch (failure: RuntimeException) {
				compensateAfterFailure(
					failure,
					boundaryBootId,
					elapsedRealtimeNanos,
					boundaryWallTimeMs,
					desiredLatencyMs,
				)
				throw failure
			}
		}

	}

	private suspend fun compensateAfterFailure(
		failure: Throwable,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		desiredLatencyMs: Long,
	) {
		withContext(NonCancellable) {
			try {
			compensateRejectedActivation(
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
				desiredLatencyMs,
			)
			} catch (@Suppress("TooGenericExceptionCaught") compensationFailure: Throwable) {
			if (compensationFailure !== failure) failure.addSuppressed(compensationFailure)
			}
		}
	}

	private suspend fun compensateRejectedActivation(
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		desiredLatencyMs: Long,
		rejected: ActivityRegistrationResult? = null,
	): ActivityRegistrationResult = withContext(NonCancellable) {
		withTimeoutOrNull(AUTOMATIC_CONTROL_COMPENSATION_TIMEOUT_MS) {
			val retired = retireControl(
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
				desiredLatencyMs,
			)
			val cleared = arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
			when {
				cleared.status == ActivityRegistrationStatus.FAILED ||
					cleared.status == ActivityRegistrationStatus.DEGRADED -> cleared
				retired !is GuardedPurposeDemandResult.Applied -> cleared.blocked()
				else -> rejected?.let {
					if (it.isAcceptedAutomaticControl()) it else it.blocked()
				} ?: cleared.blocked()
			}
		} ?: ActivityRegistrationResult(
			status = ActivityRegistrationStatus.FAILED,
			snapshot = arbiter.snapshot(),
			failureCode = ActivityRegistrationFailureCode.STORAGE_UNAVAILABLE,
			retryable = true,
		)
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

	private fun ActivityRegistrationResult.blocked(): ActivityRegistrationResult =
		if (status == ActivityRegistrationStatus.FAILED) this else copy(
			status = ActivityRegistrationStatus.BLOCKED,
			failureCode = failureCode ?: ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND,
		)

	private fun ActivityRegistrationResult.isAcceptedAutomaticControl(): Boolean =
		status in setOf(ActivityRegistrationStatus.APPLIED, ActivityRegistrationStatus.DEGRADED) &&
			snapshot.active &&
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR in snapshot.owners

	private companion object {
		const val AUTOMATIC_CONTROL_CONSUMER = "app:automatic-start:activity"
		const val AUTOMATIC_CONTROL_COMPENSATION_TIMEOUT_MS = 2_000L
	}
}
