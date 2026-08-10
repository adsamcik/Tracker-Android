package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.runtime.ProviderCoverage
import com.adsamcik.tracker.tracker.source.runtime.ProviderFlushOutcome
import com.adsamcik.tracker.tracker.source.runtime.RegistrationRemovalOutcome
import com.adsamcik.tracker.tracker.source.runtime.SessionCutoff
import com.adsamcik.tracker.tracker.source.runtime.SourceApplyResult
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeRegistry
import com.adsamcik.tracker.tracker.source.runtime.SourceStartResult
import com.adsamcik.tracker.tracker.source.runtime.SourceStopAck
import com.adsamcik.tracker.tracker.source.runtime.SourceStopStatus
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Room-first lifecycle actor for logical tracking sessions and their service runs. */
@Singleton
class AuthoritativeSessionCoordinator @Inject constructor(
	private val database: AppDatabase,
	private val planStore: RoomSourcePlanStore,
	private val runtimes: SourceRuntimeRegistry,
	private val sinkFactory: DurableSourceEventSinkFactory,
	private val eventCoordinator: TrackingCoordinator,
	private val rolloutStore: TrackingRolloutStateStore = RoomTrackingRolloutStateStore(database),
) {
	suspend fun start(request: SessionStartRequest): SessionStartResult {
		require(request.plan.plans.values.any(SourcePlan::enabled)) { "At least one source must be enabled" }
		val rollout = rolloutStore.load()
		val rolloutFailure = rollout.validateEventPlan(request.rolloutRevision, request.plan)
		if (rolloutFailure != null) return SessionStartResult.InvalidRollout(rolloutFailure)
		if (!acquireLease(request.ownerToken)) return SessionStartResult.Busy
		return try {
			val active = database.sourceSessionDao().activeSession()
			if (active != null) {
				return if (
					request.origin == SessionStartOrigin.RESTORE_AFTER_PROCESS_DEATH &&
					request.logicalTrackingId == active.logicalTrackingId
				) {
					resume(active, request)
				} else {
					SessionStartResult.AlreadyActive
				}
			}
			planStore.persistDesired(request.plan, DesiredPlanStatus.DESIRED)
			val logicalTrackingId = request.logicalTrackingId ?: UUID.randomUUID().toString()
			val serviceRunId = request.serviceRunId ?: UUID.randomUUID().toString()
			val created = database.withTransaction {
				if (database.sourceSessionDao().activeSession() != null) return@withTransaction false
				database.sourceSessionDao().insertSession(
					LogicalTrackingSessionEntity(
						logicalTrackingId = logicalTrackingId,
						state = SessionLifecycleState.STARTING.name,
						lifecycleRevision = 1,
						desiredPlanRevision = request.plan.revision,
						rolloutRevision = request.rolloutRevision,
						startOrigin = request.origin.name,
						clockDomainId = request.clockDomainId,
						startedAtMs = request.wallTimeMs,
						startedElapsedNanos = request.elapsedRealtimeNanos,
						cutoffAtMs = null,
						cutoffElapsedNanos = null,
						completedAtMs = null,
						finalAdmissionOrdinal = null,
						failureCode = null,
					),
				)
				database.sourceSessionDao().insertServiceRun(
					SourceServiceRunEntity(
						serviceRunId = serviceRunId,
						logicalTrackingId = logicalTrackingId,
						state = SessionLifecycleState.STARTING.name,
						desiredPlanRevision = request.plan.revision,
						rolloutRevision = request.rolloutRevision,
						foregroundCapabilityFlags = request.foregroundCapabilityFlags,
						startedAtMs = request.wallTimeMs,
						startedElapsedNanos = request.elapsedRealtimeNanos,
						completedAtMs = null,
						completionReason = null,
					),
				)
				true
			}
			if (!created) return SessionStartResult.AlreadyActive

			planStore.updateStatus(request.plan.revision, DesiredPlanStatus.APPLYING)
			val sink = sinkFactory.forSession(logicalTrackingId, serviceRunId)
			val applied = request.plan.plans.values.sortedBy { it.source.stableCode }.map { plan ->
				renewLease(request.ownerToken)
				startSource(plan, sink, request.elapsedRealtimeNanos).also { state ->
					planStore.saveApplied(state, request.wallTimeMs)
				}
			}
			val effectiveStatus = applied.desiredStatus()
			planStore.updateStatus(request.plan.revision, effectiveStatus)
			val hasRunningSource = applied.any { state ->
				request.plan.plans[state.source]?.enabled == true &&
					state.status in setOf(SourceApplyStatus.APPLIED, SourceApplyStatus.DEGRADED)
			}
			if (!hasRunningSource) {
				markStartFailed(logicalTrackingId, serviceRunId, request.wallTimeMs, "NO_SOURCE_STARTED")
				SessionStartResult.Failed(logicalTrackingId, serviceRunId, applied, "NO_SOURCE_STARTED")
			} else {
				markRunning(logicalTrackingId, serviceRunId)
				SessionStartResult.Started(logicalTrackingId, serviceRunId, applied, effectiveStatus)
			}
		} finally {
			releaseLease(request.ownerToken)
		}
	}

	private suspend fun resume(
		session: LogicalTrackingSessionEntity,
		request: SessionStartRequest,
	): SessionStartResult {
		if (session.rolloutRevision != request.rolloutRevision) {
			return SessionStartResult.InvalidRollout("SESSION_ROLLOUT_REVISION_MISMATCH")
		}
		planStore.persistDesired(request.plan, DesiredPlanStatus.APPLYING)
		val serviceRunId = request.serviceRunId ?: UUID.randomUUID().toString()
		database.withTransaction {
			val latestRun = database.sourceSessionDao().latestServiceRun(session.logicalTrackingId)
			if (latestRun != null && latestRun.completedAtMs == null) {
				database.sourceSessionDao().updateServiceRun(
					latestRun.copy(
						state = SessionLifecycleState.CLOSED.name,
						completedAtMs = request.wallTimeMs,
						completionReason = "PROCESS_DEATH_RECOVERY",
					),
				)
			}
			database.sourceSessionDao().updateSession(
				session.copy(
					state = SessionLifecycleState.STARTING.name,
					lifecycleRevision = session.lifecycleRevision + 1,
					desiredPlanRevision = request.plan.revision,
					cutoffAtMs = null,
					cutoffElapsedNanos = null,
					completedAtMs = null,
					finalAdmissionOrdinal = null,
					failureCode = null,
				),
			)
			database.sourceSessionDao().insertServiceRun(
				SourceServiceRunEntity(
					serviceRunId = serviceRunId,
					logicalTrackingId = session.logicalTrackingId,
					state = SessionLifecycleState.STARTING.name,
					desiredPlanRevision = request.plan.revision,
					rolloutRevision = request.rolloutRevision,
					foregroundCapabilityFlags = request.foregroundCapabilityFlags,
					startedAtMs = request.wallTimeMs,
					startedElapsedNanos = request.elapsedRealtimeNanos,
					completedAtMs = null,
					completionReason = null,
				),
			)
		}
		val sink = sinkFactory.forSession(session.logicalTrackingId, serviceRunId)
		val applied = request.plan.plans.values.sortedBy { it.source.stableCode }.map { plan ->
			renewLease(request.ownerToken)
			startSource(plan, sink, request.elapsedRealtimeNanos).also { state ->
				planStore.saveApplied(state, request.wallTimeMs)
			}
		}
		val effectiveStatus = applied.desiredStatus()
		planStore.updateStatus(request.plan.revision, effectiveStatus)
		val hasRunningSource = applied.any { state ->
			request.plan.plans[state.source]?.enabled == true &&
				state.status in setOf(SourceApplyStatus.APPLIED, SourceApplyStatus.DEGRADED)
		}
		return if (hasRunningSource) {
			markRunning(session.logicalTrackingId, serviceRunId)
			SessionStartResult.Started(session.logicalTrackingId, serviceRunId, applied, effectiveStatus)
		} else {
			markStartFailed(session.logicalTrackingId, serviceRunId, request.wallTimeMs, "NO_SOURCE_RESTORED")
			SessionStartResult.Failed(
				session.logicalTrackingId,
				serviceRunId,
				applied,
				"NO_SOURCE_RESTORED",
			)
		}
	}

	suspend fun reconfigure(request: SessionReconfigureRequest): SessionReconfigureResult {
		val rollout = rolloutStore.load()
		val sessionForRollout = database.sourceSessionDao().activeSession()
			?: return SessionReconfigureResult.NoActiveSession
		val rolloutFailure = rollout.validateEventPlan(sessionForRollout.rolloutRevision, request.plan)
		if (rolloutFailure != null) return SessionReconfigureResult.InvalidRollout(rolloutFailure)
		if (!acquireLease(request.ownerToken)) return SessionReconfigureResult.Busy
		return try {
			val session = database.sourceSessionDao().activeSession()
				?: return SessionReconfigureResult.NoActiveSession
			if (session.state != SessionLifecycleState.RUNNING.name) {
				return SessionReconfigureResult.InvalidState(session.state)
			}
			planStore.persistDesired(request.plan, DesiredPlanStatus.APPLYING)
			database.sourceSessionDao().updateSession(
				session.copy(
					state = SessionLifecycleState.RECONFIGURING.name,
					lifecycleRevision = session.lifecycleRevision + 1,
					desiredPlanRevision = request.plan.revision,
				),
			)
			val existing = database.sourcePlanStateDao().appliedStates().associateBy { it.sourceKind }
			val serviceRun = requireNotNull(database.sourceSessionDao().latestServiceRun(session.logicalTrackingId))
			val sink = sinkFactory.forSession(session.logicalTrackingId, serviceRun.serviceRunId)
			val applied = request.plan.plans.values.sortedBy { it.source.stableCode }.map { plan ->
				renewLease(request.ownerToken)
				val state = if (existing[plan.source.stableCode]?.sourceInstanceId == null) {
					startSource(plan, sink, request.elapsedRealtimeNanos)
				} else {
					reconfigureSource(plan, request.elapsedRealtimeNanos)
				}
				planStore.saveApplied(state, request.wallTimeMs)
				state
			}
			val status = applied.desiredStatus()
			planStore.updateStatus(request.plan.revision, status)
			val updated = requireNotNull(database.sourceSessionDao().session(session.logicalTrackingId))
			database.sourceSessionDao().updateSession(
				updated.copy(
					state = SessionLifecycleState.RUNNING.name,
					lifecycleRevision = updated.lifecycleRevision + 1,
				),
			)
			SessionReconfigureResult.Applied(request.plan.revision, applied, status)
		} finally {
			releaseLease(request.ownerToken)
		}
	}

	suspend fun stop(request: SessionStopRequest): SessionStopResult {
		if (!acquireLease(request.ownerToken)) return SessionStopResult.Busy
		return try {
			val session = database.sourceSessionDao().activeSession() ?: return SessionStopResult.NoActiveSession
			val cutoffSession = if (session.cutoffElapsedNanos == null) {
				val updated = session.copy(
					state = SessionLifecycleState.QUIESCING.name,
					lifecycleRevision = session.lifecycleRevision + 1,
					cutoffAtMs = request.wallTimeMs,
					cutoffElapsedNanos = request.elapsedRealtimeNanos,
				)
				database.sourceSessionDao().updateSession(updated)
				updated
			} else {
				session
			}
			val plan = requireNotNull(planStore.load(cutoffSession.desiredPlanRevision))
			val cutoff = SessionCutoff(
				logicalTrackingId = cutoffSession.logicalTrackingId,
				elapsedRealtimeNanos = requireNotNull(cutoffSession.cutoffElapsedNanos),
				wallTimeMs = requireNotNull(cutoffSession.cutoffAtMs),
				deadlineElapsedRealtimeNanos = request.elapsedRealtimeNanos + request.gracePeriodMs * NANOS_PER_MILLISECOND,
			)
			val acks = quiesceSources(plan, cutoff, request.perSourceTimeoutMs)
			acks.forEach { ack -> saveCompleteness(cutoffSession.logicalTrackingId, ack, request.wallTimeMs) }
			val draining = requireNotNull(database.sourceSessionDao().session(cutoffSession.logicalTrackingId)).copy(
				state = SessionLifecycleState.DRAINING.name,
				lifecycleRevision = cutoffSession.lifecycleRevision + 1,
			)
			database.sourceSessionDao().updateSession(draining)
			val finalOrdinal = database.sourceEventWalDao().maximumAdmissionOrdinal() ?: 0L
			when (val drain = eventCoordinator.drainAvailable("${request.ownerToken}:projection")) {
				is CoordinatorDrainResult.Complete -> {
					if (drain.lastCompletedOrdinal < finalOrdinal) {
						return SessionStopResult.DrainPending(cutoffSession.logicalTrackingId, finalOrdinal)
					}
				}
				else -> return SessionStopResult.DrainPending(cutoffSession.logicalTrackingId, finalOrdinal)
			}
			eventCoordinator.flushThrough(finalOrdinal)
			val finalizing = requireNotNull(database.sourceSessionDao().session(cutoffSession.logicalTrackingId)).copy(
				state = SessionLifecycleState.FINALIZING.name,
				lifecycleRevision = draining.lifecycleRevision + 1,
				finalAdmissionOrdinal = finalOrdinal,
			)
			database.sourceSessionDao().updateSession(finalizing)
			plan.plans.values.forEach { planItem ->
				if (planItem.source in runtimes.registeredSources()) runCatching { runtimes.close(planItem.source) }
			}
			val closed = finalizing.copy(
				state = SessionLifecycleState.CLOSED.name,
				lifecycleRevision = finalizing.lifecycleRevision + 1,
				completedAtMs = request.wallTimeMs,
			)
			database.sourceSessionDao().updateSession(closed)
			val serviceRun = database.sourceSessionDao().latestServiceRun(cutoffSession.logicalTrackingId)
			if (serviceRun != null) {
				database.sourceSessionDao().updateServiceRun(
					serviceRun.copy(
						state = SessionLifecycleState.CLOSED.name,
						completedAtMs = request.wallTimeMs,
						completionReason = request.reason,
					),
				)
			}
			SessionStopResult.Stopped(cutoffSession.logicalTrackingId, finalOrdinal, acks)
		} finally {
			releaseLease(request.ownerToken)
		}
	}

	/**
	 * Ends one Android service run while preserving the durable logical session for watchdog
	 * recovery. Sources are fenced and projections drained before the run is marked closed.
	 */
	suspend fun suspendForRestart(request: SessionSuspendRequest): SessionSuspendResult {
		if (!acquireLease(request.ownerToken)) return SessionSuspendResult.Busy
		return try {
			val session = database.sourceSessionDao().activeSession()
				?: return SessionSuspendResult.NoActiveSession
			val cutoffSession = if (session.cutoffElapsedNanos == null) {
				val updated = session.copy(
					state = SessionLifecycleState.QUIESCING.name,
					lifecycleRevision = session.lifecycleRevision + 1,
					cutoffAtMs = request.wallTimeMs,
					cutoffElapsedNanos = request.elapsedRealtimeNanos,
				)
				database.sourceSessionDao().updateSession(updated)
				updated
			} else {
				session
			}
			val plan = requireNotNull(planStore.load(cutoffSession.desiredPlanRevision))
			val cutoff = SessionCutoff(
				logicalTrackingId = cutoffSession.logicalTrackingId,
				elapsedRealtimeNanos = requireNotNull(cutoffSession.cutoffElapsedNanos),
				wallTimeMs = requireNotNull(cutoffSession.cutoffAtMs),
				deadlineElapsedRealtimeNanos = request.elapsedRealtimeNanos +
					request.gracePeriodMs * NANOS_PER_MILLISECOND,
			)
			val acks = quiesceSources(plan, cutoff, request.perSourceTimeoutMs)
			acks.forEach { ack -> saveCompleteness(cutoffSession.logicalTrackingId, ack, request.wallTimeMs) }
			val finalOrdinal = database.sourceEventWalDao().maximumAdmissionOrdinal() ?: 0L
			when (val drain = eventCoordinator.drainAvailable("${request.ownerToken}:projection")) {
				is CoordinatorDrainResult.Complete -> if (drain.lastCompletedOrdinal < finalOrdinal) {
					return SessionSuspendResult.DrainPending(cutoffSession.logicalTrackingId, finalOrdinal)
				}
				else -> return SessionSuspendResult.DrainPending(cutoffSession.logicalTrackingId, finalOrdinal)
			}
			eventCoordinator.flushThrough(finalOrdinal)
			plan.plans.values.forEach { planItem ->
				if (planItem.source in runtimes.registeredSources()) runCatching { runtimes.close(planItem.source) }
			}
			val latest = requireNotNull(database.sourceSessionDao().session(cutoffSession.logicalTrackingId))
			database.sourceSessionDao().updateSession(
				latest.copy(
					state = SessionLifecycleState.RUNNING.name,
					lifecycleRevision = latest.lifecycleRevision + 1,
					cutoffAtMs = null,
					cutoffElapsedNanos = null,
				),
			)
			val serviceRun = database.sourceSessionDao().latestServiceRun(cutoffSession.logicalTrackingId)
			if (serviceRun != null && serviceRun.completedAtMs == null) {
				database.sourceSessionDao().updateServiceRun(
					serviceRun.copy(
						state = SessionLifecycleState.CLOSED.name,
						completedAtMs = request.wallTimeMs,
						completionReason = request.reason,
					),
				)
			}
			SessionSuspendResult.Suspended(cutoffSession.logicalTrackingId, finalOrdinal, acks)
		} finally {
			releaseLease(request.ownerToken)
		}
	}

	private suspend fun startSource(
		plan: SourcePlan,
		sink: com.adsamcik.tracker.tracker.source.runtime.SourceEventSink,
		elapsedNanos: Long,
	): AppliedSourcePlan = if (!plan.enabled) {
		disabledApplied(plan, elapsedNanos)
	} else if (plan.source !in runtimes.registeredSources()) {
		failedApplied(plan, elapsedNanos, SourceApplyStatus.BLOCKED)
	} else {
		runCatching { runtimes.start(plan, sink).applied }.getOrElse {
			failedApplied(plan, elapsedNanos, SourceApplyStatus.FAILED)
		}
	}

	private suspend fun reconfigureSource(plan: SourcePlan, elapsedNanos: Long): AppliedSourcePlan =
		if (!plan.enabled && plan.source !in runtimes.registeredSources()) {
			disabledApplied(plan, elapsedNanos)
		} else if (plan.source !in runtimes.registeredSources()) {
			failedApplied(plan, elapsedNanos, SourceApplyStatus.BLOCKED)
		} else {
			runCatching { runtimes.reconfigure(plan).applied }.getOrElse {
				failedApplied(plan, elapsedNanos, SourceApplyStatus.FAILED)
			}
		}

	private suspend fun quiesceSources(
		plan: AcquisitionPlanRevision,
		cutoff: SessionCutoff,
		perSourceTimeoutMs: Long,
	): List<SourceStopAck> = coroutineScope {
		val applied = database.sourcePlanStateDao().appliedStates().associateBy { it.sourceKind }
		plan.plans.values.filter(SourcePlan::enabled).sortedBy { it.source.stableCode }.map { sourcePlan ->
			async {
				if (sourcePlan.source !in runtimes.registeredSources()) {
					timeoutAck(sourcePlan.source, applied[sourcePlan.source.stableCode], SourceStopStatus.PROVIDER_FAILED)
				} else {
					withTimeoutOrNull(perSourceTimeoutMs) { runtimes.quiesce(sourcePlan.source, cutoff) }
						?: timeoutAck(sourcePlan.source, applied[sourcePlan.source.stableCode], SourceStopStatus.TIMED_OUT)
				}
			}
		}.awaitAll()
	}

	private suspend fun saveCompleteness(logicalTrackingId: String, ack: SourceStopAck, nowMs: Long) {
		database.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = logicalTrackingId,
				sourceKind = ack.source.stableCode,
				sourceInstanceId = ack.sourceInstanceId.value,
				registrationGeneration = ack.registrationGeneration,
				lastAdmissionOrdinal = ack.lastAdmissionOrdinal,
				lastSourceSequence = ack.lastDurablyAdmittedSequence,
				appDrainComplete = ack.appDrainComplete,
				providerCoverage = ack.providerCoverage.name,
				stopStatus = ack.status.name,
				unresolvedSequenceStart = ack.unresolvedSequenceStart,
				unresolvedSequenceEnd = ack.unresolvedSequenceEndInclusive,
				updatedAtMs = nowMs,
			),
		)
	}

	private suspend fun markRunning(logicalTrackingId: String, serviceRunId: String) {
		database.withTransaction {
			val session = requireNotNull(database.sourceSessionDao().session(logicalTrackingId))
			database.sourceSessionDao().updateSession(
				session.copy(state = SessionLifecycleState.RUNNING.name, lifecycleRevision = session.lifecycleRevision + 1),
			)
			val run = requireNotNull(database.sourceSessionDao().serviceRun(serviceRunId))
			database.sourceSessionDao().updateServiceRun(run.copy(state = SessionLifecycleState.RUNNING.name))
		}
	}

	private suspend fun markStartFailed(
		logicalTrackingId: String,
		serviceRunId: String,
		completedAtMs: Long,
		failureCode: String,
	) {
		database.withTransaction {
			val session = requireNotNull(database.sourceSessionDao().session(logicalTrackingId))
			database.sourceSessionDao().updateSession(
				session.copy(
					state = SessionLifecycleState.FAILED.name,
					lifecycleRevision = session.lifecycleRevision + 1,
					completedAtMs = completedAtMs,
					failureCode = failureCode,
				),
			)
			val run = requireNotNull(database.sourceSessionDao().serviceRun(serviceRunId))
			database.sourceSessionDao().updateServiceRun(
				run.copy(
					state = SessionLifecycleState.FAILED.name,
					completedAtMs = completedAtMs,
					completionReason = failureCode,
				),
			)
		}
	}

	private suspend fun acquireLease(ownerToken: String): Boolean = database.withTransaction {
		val now = System.currentTimeMillis()
		val dao = database.sourceProjectionStateDao()
		val inserted = dao.insertLeaseIfAbsent(
			SourceCoordinatorLeaseEntity(SESSION_LEASE, ownerToken, now, now + LEASE_DURATION_MS),
		)
		inserted >= 0L || dao.acquireOrRenewLease(SESSION_LEASE, ownerToken, now, now + LEASE_DURATION_MS) == 1
	}

	private suspend fun renewLease(ownerToken: String) {
		val now = System.currentTimeMillis()
		check(
			database.sourceProjectionStateDao().acquireOrRenewLease(
				SESSION_LEASE,
				ownerToken,
				now,
				now + LEASE_DURATION_MS,
			) == 1,
		) { "Session coordinator lease lost" }
	}

	private suspend fun releaseLease(ownerToken: String) {
		database.sourceProjectionStateDao().releaseLease(SESSION_LEASE, ownerToken)
	}

	private companion object {
		const val SESSION_LEASE = "tracking-session-coordinator"
		const val LEASE_DURATION_MS = 30_000L
		const val NANOS_PER_MILLISECOND = 1_000_000L
	}
}

private val SourceStartResult.applied: AppliedSourcePlan
	get() = when (this) {
		is SourceStartResult.Started -> applied
		is SourceStartResult.Degraded -> applied
		is SourceStartResult.Blocked -> applied
		is SourceStartResult.Failed -> applied
	}

private val SourceApplyResult.applied: AppliedSourcePlan
	get() = when (this) {
		is SourceApplyResult.Applied -> state
		is SourceApplyResult.Degraded -> state
		is SourceApplyResult.RolledBack -> state
		is SourceApplyResult.Failed -> state
	}

private fun failedApplied(plan: SourcePlan, elapsedNanos: Long, status: SourceApplyStatus) = AppliedSourcePlan(
	desiredRevision = plan.revision,
	appliedRevision = null,
	source = plan.source,
	sourceInstanceId = null,
	registrationGeneration = null,
	appliedAtElapsedRealtimeNanos = elapsedNanos,
	status = status,
)

private fun disabledApplied(plan: SourcePlan, elapsedNanos: Long) = AppliedSourcePlan(
	desiredRevision = plan.revision,
	appliedRevision = plan.revision,
	source = plan.source,
	sourceInstanceId = null,
	registrationGeneration = null,
	appliedAtElapsedRealtimeNanos = elapsedNanos,
	status = SourceApplyStatus.APPLIED,
)

private fun List<AppliedSourcePlan>.desiredStatus(): DesiredPlanStatus = when {
	all { it.status == SourceApplyStatus.APPLIED } -> DesiredPlanStatus.EFFECTIVE
	any { it.status in setOf(SourceApplyStatus.FAILED, SourceApplyStatus.BLOCKED) } -> DesiredPlanStatus.DEGRADED
	else -> DesiredPlanStatus.DEGRADED
}

private fun timeoutAck(
	source: SourceKind,
	applied: com.adsamcik.tracker.shared.base.database.data.SourceAppliedPlanStateEntity?,
	status: SourceStopStatus,
) = SourceStopAck(
	source = source,
	sourceInstanceId = com.adsamcik.tracker.tracker.source.model.SourceInstanceId(
		applied?.sourceInstanceId ?: "unavailable-${source.name.lowercase()}",
	),
	registrationGeneration = applied?.registrationGeneration ?: 0L,
	appliedRevision = applied?.appliedRevision,
	callbackEntryBarrierSequence = 0L,
	lastDurablyAdmittedSequence = null,
	lastAdmissionOrdinal = null,
	failedAdmissionCount = 0,
	unresolvedSequenceStart = null,
	unresolvedSequenceEndInclusive = null,
	registrationRemovalOutcome = RegistrationRemovalOutcome.UNOBSERVABLE,
	providerFlushOutcome = ProviderFlushOutcome.TIMED_OUT,
	providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
	appDrainComplete = false,
	status = status,
)

enum class SessionLifecycleState { IDLE, STARTING, RUNNING, RECONFIGURING, QUIESCING, DRAINING, FINALIZING, CLOSED, FAILED }
enum class SessionStartOrigin { MANUAL_FOREGROUND, AUTOMATIC_ACTIVITY_TRANSITION, RESTORE_AFTER_PROCESS_DEATH }

data class SessionStartRequest(
	val ownerToken: String,
	val origin: SessionStartOrigin,
	val plan: AcquisitionPlanRevision,
	val rolloutRevision: Long,
	val clockDomainId: String,
	val foregroundCapabilityFlags: Long,
	val wallTimeMs: Long,
	val elapsedRealtimeNanos: Long,
	val logicalTrackingId: String? = null,
	val serviceRunId: String? = null,
)

sealed interface SessionStartResult {
	data class Started(
		val logicalTrackingId: String,
		val serviceRunId: String,
		val applied: List<AppliedSourcePlan>,
		val planStatus: DesiredPlanStatus,
	) : SessionStartResult
	data class Failed(
		val logicalTrackingId: String,
		val serviceRunId: String,
		val applied: List<AppliedSourcePlan>,
		val code: String,
	) : SessionStartResult
	data object AlreadyActive : SessionStartResult
	data object Busy : SessionStartResult
	data class InvalidRollout(val code: String) : SessionStartResult
}

data class SessionReconfigureRequest(
	val ownerToken: String,
	val plan: AcquisitionPlanRevision,
	val wallTimeMs: Long,
	val elapsedRealtimeNanos: Long,
)

sealed interface SessionReconfigureResult {
	data class Applied(
		val revision: Long,
		val applied: List<AppliedSourcePlan>,
		val status: DesiredPlanStatus,
	) : SessionReconfigureResult
	data class InvalidState(val state: String) : SessionReconfigureResult
	data object NoActiveSession : SessionReconfigureResult
	data object Busy : SessionReconfigureResult
	data class InvalidRollout(val code: String) : SessionReconfigureResult
}

private fun TrackingRolloutState.validateEventPlan(
	expectedRevision: Long,
	plan: AcquisitionPlanRevision,
): String? = when {
	revision != expectedRevision -> "ROLLOUT_REVISION_MISMATCH"
	coordinatorMode != CoordinatorMode.EVENT -> "EVENT_COORDINATOR_DISABLED"
	plan.plans.values.any { sourcePlan ->
		sourcePlan.enabled && sourceOwners[sourcePlan.source] != SourceOwner.EVENT
	} -> "EVENT_SOURCE_NOT_OWNED"
	else -> null
}

data class SessionStopRequest(
	val ownerToken: String,
	val reason: String,
	val wallTimeMs: Long,
	val elapsedRealtimeNanos: Long,
	val gracePeriodMs: Long = 10_000L,
	val perSourceTimeoutMs: Long = 2_000L,
)

sealed interface SessionStopResult {
	data class Stopped(
		val logicalTrackingId: String,
		val finalAdmissionOrdinal: Long,
		val acknowledgements: List<SourceStopAck>,
	) : SessionStopResult
	data class DrainPending(val logicalTrackingId: String, val requiredOrdinal: Long) : SessionStopResult
	data object NoActiveSession : SessionStopResult
	data object Busy : SessionStopResult
}

data class SessionSuspendRequest(
	val ownerToken: String,
	val reason: String,
	val wallTimeMs: Long,
	val elapsedRealtimeNanos: Long,
	val gracePeriodMs: Long = 10_000L,
	val perSourceTimeoutMs: Long = 2_000L,
)

sealed interface SessionSuspendResult {
	data class Suspended(
		val logicalTrackingId: String,
		val finalAdmissionOrdinal: Long,
		val acknowledgements: List<SourceStopAck>,
	) : SessionSuspendResult
	data class DrainPending(val logicalTrackingId: String, val requiredOrdinal: Long) : SessionSuspendResult
	data object NoActiveSession : SessionSuspendResult
	data object Busy : SessionSuspendResult
}
