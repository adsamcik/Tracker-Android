package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationDemand
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationOwner
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationResult
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Session-scoped activity demand. Callback admission remains process-independent in the receiver. */
@Singleton
class ActivitySourceRuntime @Inject constructor(
	private val arbiter: ActivityRegistrationArbiter,
	private val database: AppDatabase,
) : SourceRuntime<ActivityPlan> {
	override val source: SourceKind = SourceKind.ACTIVITY
	private val mutex = Mutex()
	private val _capabilities = MutableStateFlow(
		SourceCapabilities(true, batchingSupported = true, flushSupported = false, null, 1_000L),
	)
	override val capabilities: StateFlow<SourceCapabilities> = _capabilities
	private var currentPlan: ActivityPlan? = null
	private var sessionIdentity: ActivityRegistrationIdentity? = null

	override suspend fun start(plan: ActivityPlan, sink: SourceEventSink): SourceStartResult = mutex.withLock {
		require(currentPlan == null) { "Activity source is already started" }
		val result = applyPlan(plan)
		currentPlan = plan.takeIf(ActivityPlan::enabled)
		result.toStart(plan)
	}

	override suspend fun reconfigure(plan: ActivityPlan): SourceApplyResult = mutex.withLock {
		val result = applyPlan(plan)
		currentPlan = plan.takeIf(ActivityPlan::enabled)
		result.toApply(plan)
	}

	override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck = mutex.withLock {
		val identity = sessionIdentity ?: arbiter.snapshot().identity
		val appliedRevision = currentPlan?.revision
		val barrier = identity?.let { registration ->
			database.sourceRegistrationStateDao()
				.get(SourceKind.ACTIVITY.stableCode, ARBITER_OWNER_SCOPE)
				?.takeIf { it.sourceInstanceId == registration.sourceInstanceId }
				?.nextSequence
				?.minus(1L)
				?.coerceAtLeast(0L)
		} ?: 0L
		val result = arbiter.clearDemand(ActivityRegistrationOwner.ACTIVE_SESSION)
		currentPlan = null
		sessionIdentity = null
		SourceStopAck(
			source = source,
			sourceInstanceId = SourceInstanceId(identity?.sourceInstanceId ?: "activity-unregistered"),
			registrationGeneration = identity?.registrationGeneration ?: 0L,
			appliedRevision = appliedRevision,
			callbackEntryBarrierSequence = barrier,
			lastDurablyAdmittedSequence = barrier.takeIf { it > 0L },
			lastAdmissionOrdinal = null,
			failedAdmissionCount = 0L,
			unresolvedSequenceStart = null,
			unresolvedSequenceEndInclusive = null,
			registrationRemovalOutcome = if (result.status == ActivityRegistrationStatus.FAILED) {
				RegistrationRemovalOutcome.FAILED
			} else {
				RegistrationRemovalOutcome.REMOVED
			},
			providerFlushOutcome = ProviderFlushOutcome.NOT_SUPPORTED,
			providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
			appDrainComplete = true,
			status = if (result.status == ActivityRegistrationStatus.FAILED) {
				SourceStopStatus.PROVIDER_FAILED
			} else {
				SourceStopStatus.COMPLETE
			},
		)
	}

	override suspend fun close() = mutex.withLock {
		arbiter.clearDemand(ActivityRegistrationOwner.ACTIVE_SESSION)
		currentPlan = null
		sessionIdentity = null
	}

	private suspend fun applyPlan(plan: ActivityPlan): ActivityRegistrationResult {
		if (!plan.enabled) return arbiter.clearDemand(ActivityRegistrationOwner.ACTIVE_SESSION)
		val transitions = if (plan.mode == ActivityMode.TRANSITIONS_ONLY) {
			val requestedTypes = plan.transitionTypes.mapNotNull { code ->
				ActivityTransitionType.entries.firstOrNull { it.value == code }
			}
			SESSION_ACTIVITY_TYPES.flatMap { activity ->
				requestedTypes.map { type -> ActivityTransitionData(activity, type) }
			}.toSet()
		} else emptySet()
		val result = arbiter.setDemand(
			ActivityRegistrationOwner.ACTIVE_SESSION,
			ActivityRegistrationDemand(
				continuousRecognitionIntervalSeconds = if (plan.mode == ActivityMode.CONTINUOUS_RECOGNITION) {
					(plan.desiredDetectionLatencyMs.coerceAtLeast(1_000L) / 1_000L)
						.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
				} else null,
				transitions = transitions,
				planRevision = plan.revision,
			),
		)
		if (result.status == ActivityRegistrationStatus.APPLIED || result.status == ActivityRegistrationStatus.DEGRADED) {
			sessionIdentity = result.snapshot.identity
		}
		return result
	}

	private fun ActivityRegistrationResult.toStart(plan: ActivityPlan): SourceStartResult {
		val state = toApplied(plan)
		return when (status) {
			ActivityRegistrationStatus.APPLIED -> SourceStartResult.Started(state)
			ActivityRegistrationStatus.DEGRADED -> SourceStartResult.Degraded(state)
			ActivityRegistrationStatus.BLOCKED -> SourceStartResult.Blocked(state)
			ActivityRegistrationStatus.FAILED -> SourceStartResult.Failed(state, retryable)
		}
	}

	private fun ActivityRegistrationResult.toApply(plan: ActivityPlan): SourceApplyResult {
		val state = toApplied(plan)
		return when (status) {
			ActivityRegistrationStatus.APPLIED -> SourceApplyResult.Applied(state)
			ActivityRegistrationStatus.DEGRADED -> SourceApplyResult.Degraded(state)
			ActivityRegistrationStatus.BLOCKED -> SourceApplyResult.Failed(state, retryable = false)
			ActivityRegistrationStatus.FAILED -> SourceApplyResult.Failed(state, retryable)
		}
	}

	private fun ActivityRegistrationResult.toApplied(plan: ActivityPlan): AppliedSourcePlan {
		val identity = snapshot.identity
		val applyStatus = when (status) {
			ActivityRegistrationStatus.APPLIED -> SourceApplyStatus.APPLIED
			ActivityRegistrationStatus.DEGRADED -> SourceApplyStatus.DEGRADED
			ActivityRegistrationStatus.BLOCKED -> SourceApplyStatus.BLOCKED
			ActivityRegistrationStatus.FAILED -> SourceApplyStatus.FAILED
		}
		val degraded = when (failureCode?.name) {
			"PERMISSION_MISSING" -> setOf(SourceDegradedReason.PERMISSION_MISSING)
			"PROVIDER_UNAVAILABLE" -> setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE)
			else -> emptySet()
		}
		return AppliedSourcePlan(
			desiredRevision = plan.revision,
			appliedRevision = plan.revision.takeIf {
				applyStatus == SourceApplyStatus.APPLIED || applyStatus == SourceApplyStatus.DEGRADED
			},
			source = source,
			sourceInstanceId = identity?.sourceInstanceId?.let(::SourceInstanceId),
			registrationGeneration = identity?.registrationGeneration,
			appliedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
			status = applyStatus,
			degradedReasons = degraded,
		)
	}

	private companion object {
		const val ARBITER_OWNER_SCOPE = "activity-registration-arbiter"
		val SESSION_ACTIVITY_TYPES = setOf(
			DetectedActivityType.STILL,
			DetectedActivityType.ON_FOOT,
			DetectedActivityType.WALKING,
			DetectedActivityType.RUNNING,
			DetectedActivityType.ON_BICYCLE,
			DetectedActivityType.IN_VEHICLE,
		)
	}
}
