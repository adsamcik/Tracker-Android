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
) : ClaimedSourceRuntime<ActivityPlan> {
	override val source: SourceKind = SourceKind.ACTIVITY
	private val mutex = Mutex()
	private val _capabilities = MutableStateFlow(
		SourceCapabilities(true, batchingSupported = true, flushSupported = false, null, 1_000L),
	)
	override val capabilities: StateFlow<SourceCapabilities> = _capabilities
	private var currentPlan: ActivityPlan? = null
	private var sessionIdentity: ActivityRegistrationIdentity? = null
	private var runtimeClaim: SourceRuntimeClaim? = null

	override suspend fun start(plan: ActivityPlan, sink: SourceEventSink): SourceStartResult = mutex.withLock {
		startLocked(claim = null, plan = plan)
	}

	override suspend fun start(
		claim: SourceRuntimeClaim,
		plan: ActivityPlan,
		sink: SourceEventSink,
	): SourceStartResult {
		require(claim.source == source)
		return mutex.withLock { startLocked(claim, plan) }
	}

	override suspend fun reconfigure(plan: ActivityPlan, sink: SourceEventSink): SourceApplyResult = mutex.withLock {
		reconfigureLocked(claim = null, plan = plan)
	}

	override suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: ActivityPlan,
		sink: SourceEventSink,
	): SourceApplyResult {
		require(claim.source == source)
		return mutex.withLock { reconfigureLocked(claim, plan) }
	}

	override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck = mutex.withLock {
		val acknowledgement = clearSessionDemandLocked()
		if (acknowledgement.toOwnedShutdown() is OwnedSourceShutdown.Released) clearLocalJoinLocked()
		acknowledgement
	}

	override suspend fun shutdownIfOwned(
		claim: SourceRuntimeClaim,
		cutoff: SessionCutoff,
	): OwnedSourceShutdown {
		require(claim.source == source)
		return mutex.withLock {
			if (runtimeClaim != claim) return@withLock OwnedSourceShutdown.NotOwned
			val shutdown = clearSessionDemandLocked().toOwnedShutdown()
			if (shutdown is OwnedSourceShutdown.Released) clearLocalJoinLocked()
			shutdown
		}
	}

	override suspend fun close() = mutex.withLock {
		val result = arbiter.clearDemand(ActivityRegistrationOwner.ACTIVE_SESSION)
		if (result.activeSessionJoinReleased) clearLocalJoinLocked()
	}

	private suspend fun startLocked(
		claim: SourceRuntimeClaim?,
		plan: ActivityPlan,
	): SourceStartResult {
		require(currentPlan == null && runtimeClaim == null) {
			"Activity source is already started or awaiting owned cleanup"
		}
		preparePlanMutationLocked(claim, plan)
		val result = applyPlan(plan)
		settlePlanMutationLocked(plan, result)
		return result.toStart(plan)
	}

	private suspend fun reconfigureLocked(
		claim: SourceRuntimeClaim?,
		plan: ActivityPlan,
	): SourceApplyResult {
		// The successor owns every possible outcome before the arbiter can mutate its demand map.
		// This is also an atomic claim transfer when reconciliation reuses the same provider identity.
		preparePlanMutationLocked(claim, plan)
		val result = applyPlan(plan)
		settlePlanMutationLocked(plan, result)
		return result.toApply(plan)
	}

	private fun preparePlanMutationLocked(claim: SourceRuntimeClaim?, plan: ActivityPlan) {
		runtimeClaim = claim
		if (plan.enabled) currentPlan = plan
	}

	private fun settlePlanMutationLocked(plan: ActivityPlan, result: ActivityRegistrationResult) {
		if (plan.enabled) {
			currentPlan = plan
			sessionIdentity = result.snapshot.identity ?: sessionIdentity
		} else if (result.activeSessionJoinReleased) {
			clearLocalJoinLocked()
		}
	}

	private fun clearLocalJoinLocked() {
		currentPlan = null
		sessionIdentity = null
		runtimeClaim = null
	}

	private suspend fun clearSessionDemandLocked(): SourceStopAck {
		val beforeClear = arbiter.snapshot()
		val identity = beforeClear.identity
			.takeIf { ActivityRegistrationOwner.ACTIVE_SESSION in beforeClear.owners }
			?: sessionIdentity
			?: beforeClear.identity
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
		return SourceStopAck(
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
			registrationRemovalOutcome = when {
				!result.activeSessionJoinReleased -> RegistrationRemovalOutcome.FAILED
				result.snapshot.active || identity == null -> RegistrationRemovalOutcome.NOT_REGISTERED
				else -> RegistrationRemovalOutcome.REMOVED
			},
			providerFlushOutcome = ProviderFlushOutcome.NOT_SUPPORTED,
			providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
			appDrainComplete = true,
			status = if (result.activeSessionJoinReleased) {
				SourceStopStatus.COMPLETE
			} else {
				SourceStopStatus.PROVIDER_FAILED
			},
		).withSessionMembership(runtimeClaim)
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
		return result
	}

	private val ActivityRegistrationResult.activeSessionJoinReleased: Boolean
		get() = status == ActivityRegistrationStatus.APPLIED &&
			ActivityRegistrationOwner.ACTIVE_SESSION !in snapshot.owners

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
		const val ARBITER_OWNER_SCOPE = "source-broker:2"
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
