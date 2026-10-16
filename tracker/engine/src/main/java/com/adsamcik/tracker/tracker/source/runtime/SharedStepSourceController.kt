package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one broker-driven owner of the physical step-counter registration.
 *
 * Session lifecycle calls attach/detach one consumer. Steps has no automatic-control role: Activity
 * Transition owns the legal cold-start trigger, and the step counter is never retained solely for
 * cross-source corroboration.
 */
@Singleton
class SharedStepSourceController @Inject internal constructor(
	private val physicalRuntime: StepSourceRuntime,
	private val sourceBroker: SourceBroker,
	private val sourceCallerDemandDispatcher: SourceCallerDemandDispatcher,
	sinkFactory: DurableSourceEventSinkFactory,
	private val clockDomainProvider: BootClockDomainProvider,
) : ClaimedSourceRuntime<StepsPlan> {
	override val source: SourceKind = SourceKind.STEPS
	override val capabilities: StateFlow<SourceCapabilities> = physicalRuntime.capabilities

	private val mutex = Mutex()
	private val unboundSink = sinkFactory.unbound
	private var sessionPlan: StepsPlan? = null
	private var sessionClaim: SourceRuntimeClaim? = null

	override suspend fun start(plan: StepsPlan, sink: SourceEventSink): SourceStartResult = mutex.withLock {
		startForClaimLocked(claim = null, plan, sink)
	}

	override suspend fun start(
		claim: SourceRuntimeClaim,
		plan: StepsPlan,
		sink: SourceEventSink,
	): SourceStartResult = mutex.withLock {
		require(claim.source == source)
		startForClaimLocked(claim, plan, sink)
	}

	internal suspend fun continuingRecording(
		logicalTrackingId: String,
		serviceRunId: String,
		plan: StepsPlan,
	): StepsRecordingContinuation? = mutex.withLock {
		val claim = sessionClaim ?: return@withLock null
		if (sessionPlan?.enabled != true || claim.logicalTrackingId != logicalTrackingId ||
			claim.serviceRunId != serviceRunId) return@withLock null
		physicalRuntime.continuingRecording(claim, plan)
	}

	internal suspend fun reconfigureWithContinuity(
		claim: SourceRuntimeClaim,
		plan: StepsPlan,
		sink: SourceEventSink,
		continuation: StepsRecordingContinuation?,
	): SourceApplyResult = mutex.withLock {
		if (!sourceBroker.requiresContinuingStepsRecording(claim)) {
			return@withLock reconfigureForClaimLocked(claim, plan, sink)
		}
		if (continuation == null || sessionClaim != continuation.claim) {
			return@withLock SourceApplyResult.Failed(blockedState(plan), retryable = true)
		}
		val result = physicalRuntime.reuseContinuingRecording(continuation, claim, plan) {
			sourceBroker.continuingStepsDemand(claim.logicalTrackingId, claim.serviceRunId,
				claim.leaseGeneration, continuation) != null
		}
		if (result is SourceApplyResult.Applied) {
			sessionPlan = plan
			sessionClaim = claim
		}
		result
	}

	private suspend fun startForClaimLocked(
		claim: SourceRuntimeClaim?,
		plan: StepsPlan,
		sink: SourceEventSink,
	): SourceStartResult {
		@Suppress("UNUSED_VARIABLE") val sessionBoundSinkMustNotRelabelSharedEvidence = sink
		if (!plan.enabled) return SourceStartResult.Started(disabledState(plan))
		if (requiresContinuingRecording(claim)) {
			return SourceStartResult.Blocked(blockedState(plan))
		}
		val predecessorPlan = sessionPlan
		val predecessorClaim = sessionClaim
		sessionPlan = plan
		sessionClaim = claim
		val demands = try {
			selectedDemands()
		} catch (failure: Throwable) {
			sessionPlan = predecessorPlan
			sessionClaim = predecessorClaim
			throw failure
		}
		if (demands.none { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }) {
			sessionPlan = predecessorPlan
			sessionClaim = predecessorClaim
			return SourceStartResult.Blocked(blockedState(plan))
		}
		return try {
			applyEffective(requireNotNull(effectivePlan(demands)), claim).also { result ->
				if (result is SourceApplyResult.Applied || result is SourceApplyResult.Degraded) {
					sessionClaim = claim
				} else {
					sessionPlan = predecessorPlan
					sessionClaim = predecessorClaim
				}
			}.toStartResult()
		} catch (failure: Throwable) {
			sessionPlan = predecessorPlan
			sessionClaim = predecessorClaim
			throw failure
		}
	}

	override suspend fun reconfigure(plan: StepsPlan, sink: SourceEventSink): SourceApplyResult = mutex.withLock {
		reconfigureForClaimLocked(claim = null, plan, sink)
	}

	override suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: StepsPlan,
		sink: SourceEventSink,
	): SourceApplyResult = mutex.withLock {
		require(claim.source == source)
		reconfigureForClaimLocked(claim, plan, sink)
	}

	private suspend fun requiresContinuingRecording(claim: SourceRuntimeClaim?): Boolean =
		(claim != null && sourceBroker.requiresContinuingStepsRecording(claim)) ||
			(sessionClaim?.let { sourceBroker.requiresContinuingStepsRecording(it) } == true)

	private suspend fun reconfigureForClaimLocked(
		claim: SourceRuntimeClaim?,
		plan: StepsPlan,
		sink: SourceEventSink,
	): SourceApplyResult {
		@Suppress("UNUSED_VARIABLE") val sessionBoundSinkMustNotRelabelSharedEvidence = sink
		if (plan.enabled && requiresContinuingRecording(claim)) {
			// Only the coordinator's fresh physical preflight can adopt a continuing recording.
			return SourceApplyResult.Failed(blockedState(plan), retryable = false)
		}
		val predecessorPlan = sessionPlan
		val predecessorClaim = sessionClaim
		if (!plan.enabled && claim != null && sessionClaim != null && claim != sessionClaim) {
			return SourceApplyResult.Failed(blockedState(plan), retryable = false)
		}
		sessionPlan = plan.takeIf(StepsPlan::enabled)
		sessionClaim = claim.takeIf { plan.enabled }
		val demands = try {
			selectedDemands()
		} catch (failure: Throwable) {
			sessionPlan = predecessorPlan
			sessionClaim = predecessorClaim
			throw failure
		}
		if (plan.enabled && demands.none { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }) {
			sessionPlan = predecessorPlan
			sessionClaim = predecessorClaim
			return SourceApplyResult.Failed(blockedState(plan), retryable = false)
		}
		val effective = effectivePlan(demands)
		return applySessionPlanAndRestoreOnFailureLocked(
			effective, claim, plan, predecessorPlan, predecessorClaim,
		)
	}

	private suspend fun applySessionPlanAndRestoreOnFailureLocked(
		effective: StepsPlan?,
		claim: SourceRuntimeClaim?,
		plan: StepsPlan,
		predecessorPlan: StepsPlan?,
		predecessorClaim: SourceRuntimeClaim?,
	): SourceApplyResult = try {
		val result = if (effective == null) {
			applyEffective(plan.copy(enabled = false), claim)
		} else {
			applyEffective(effective, claim)
		}
		if (result is SourceApplyResult.Applied || result is SourceApplyResult.Degraded) {
			sessionClaim = claim.takeIf { plan.enabled }
		} else {
			sessionPlan = predecessorPlan
			sessionClaim = predecessorClaim
		}
		result
	} catch (failure: Throwable) {
		sessionPlan = predecessorPlan
		sessionClaim = predecessorClaim
		throw failure
	}

	override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck = mutex.withLock {
		quiesceLocked(cutoff).also(::clearClaimIfReleased)
	}

	private suspend fun quiesceLocked(cutoff: SessionCutoff): SourceStopAck {
		val previousPlan = sessionPlan
		val retiringClaim = sessionClaim
		require(retiringClaim == null || cutoff.logicalTrackingId == retiringClaim.logicalTrackingId) {
			"A session cutoff cannot release another logical source owner"
		}
		return try {
			settleSessionCutoffLocked(cutoff)
		} catch (failure: Throwable) {
			sessionPlan = previousPlan
			throw failure
		}
	}

	private suspend fun settleSessionCutoffLocked(cutoff: SessionCutoff): SourceStopAck {
		val predecessorPlan = sessionPlan
		val retiringClaim = sessionClaim
		sessionPlan = null
		val controlPlan = effectivePlan(selectedDemands())
		if (controlPlan == null) {
			return physicalRuntime.quiesce(cutoff).withSessionMembership(retiringClaim).also { acknowledgement ->
				if (acknowledgement.toStepsOwnedShutdown() is OwnedSourceShutdown.Incomplete) {
					sessionPlan = predecessorPlan
				}
			}
		}

		// Authorization-only leave: callbacks already in the queue retain the old immutable
		// authorization, while callbacks entering after refresh carry the control-only vector.
		val acknowledgement = when (physicalRuntime.reconfigure(controlPlan, unboundSink)) {
			is SourceApplyResult.Applied,
			is SourceApplyResult.Degraded -> physicalRuntime.sharedCutoff(cutoff)
			else -> {
				val acknowledgement = physicalRuntime.quiesce(cutoff)
				if (acknowledgement.appDrainComplete &&
					acknowledgement.registrationRemovalOutcome == RegistrationRemovalOutcome.REMOVED
				) {
					physicalRuntime.reconfigure(controlPlan, unboundSink)
				}
				acknowledgement
			}
		}
		val attributedAcknowledgement = acknowledgement.withSessionMembership(retiringClaim)
		if (attributedAcknowledgement.toStepsOwnedShutdown() is OwnedSourceShutdown.Incomplete) {
			sessionPlan = predecessorPlan
		}
		return attributedAcknowledgement
	}

	override suspend fun shutdownIfOwned(
		claim: SourceRuntimeClaim,
		cutoff: SessionCutoff,
	): OwnedSourceShutdown = mutex.withLock {
		require(claim.source == source)
		if (sessionClaim != claim) {
			// A failed successor may have published a physical provider without replacing the logical
			// session join. Its exact claim remains cleanable without touching the predecessor join.
			return@withLock physicalRuntime.shutdownIfOwned(claim, cutoff)
		}
		quiesceLocked(cutoff).toStepsOwnedShutdown().also { shutdown ->
			if (shutdown is OwnedSourceShutdown.Released) sessionClaim = null
		}
	}

	/** Session owners release their join; no orphan automatic-control listener may remain. */
	override suspend fun close() = mutex.withLock {
		val retiringClaim = sessionClaim
		if (retiringClaim != null) {
			val now = SystemClock.elapsedRealtimeNanos()
			val acknowledgement = quiesceLocked(
				SessionCutoff(retiringClaim.logicalTrackingId, now, System.currentTimeMillis(),
					now + 3_000_000_000L),
			)
			clearClaimIfReleased(acknowledgement)
			return@withLock Unit
		}
		sessionPlan = null
		reconcileRemainingControl()
		Unit
	}

	private fun clearClaimIfReleased(acknowledgement: SourceStopAck) {
		if (acknowledgement.toStepsOwnedShutdown() is OwnedSourceShutdown.Released) sessionClaim = null
	}

	/** Retires any Steps CONTROL_AUTOSTART demand written by an older build. */
	suspend fun retireLegacyAutomaticControl(): Unit = mutex.withLock {
		val elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
		sourceCallerDemandDispatcher.retireAutomaticControl(
			consumerId = AUTOMATIC_CONTROL_CONSUMER,
			source = source,
			bootId = clockDomainProvider.current(),
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			wallTimeMs = System.currentTimeMillis(),
			maximumAgeMs = LEGACY_CONTROL_MAXIMUM_AGE_MS,
			desiredLatencyMs = LEGACY_CONTROL_LATENCY_MS,
		)
		reconcileSelectedDemands()
		Unit
	}

	private suspend fun reconcileSelectedDemands(): SourceApplyResult? {
		val effective = effectivePlan(selectedDemands())
		return if (effective == null) {
			physicalRuntime.close()
			null
		} else {
			applyEffective(effective)
		}
	}

	private suspend fun applyEffective(
		plan: StepsPlan,
		claim: SourceRuntimeClaim? = null,
	): SourceApplyResult {
		val attachedClaim = sessionClaim
		if (claim == null && plan.enabled && attachedClaim != null &&
			sourceBroker.requiresContinuingStepsRecording(attachedClaim)) {
			// Legacy CONTROL cleanup may inspect a live session, but cannot replace or revive it.
			val live = physicalRuntime.continuingRecording(attachedClaim, plan)
			return if (live != null && sourceBroker.continuingStepsDemand(attachedClaim.logicalTrackingId,
				attachedClaim.serviceRunId, attachedClaim.leaseGeneration, live) != null) {
				SourceApplyResult.Applied(appliedState(source, plan.revision, live.registration,
					SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()))
			} else SourceApplyResult.Failed(blockedState(plan), retryable = true)
		}
		return if (claim == null) {
			physicalRuntime.refreshCompatible(plan, unboundSink)
				?: physicalRuntime.reconfigure(plan, unboundSink)
		} else physicalRuntime.reconfigure(claim, plan, unboundSink)
	}

	private suspend fun reconcileRemainingControl(): SourceApplyResult? {
		val previous = sessionPlan
		sessionPlan = null
		return try {
			reconcileSelectedDemands()
		} finally {
			sessionPlan = previous
		}
	}

	private suspend fun selectedDemands(): List<SourceDemandEntity> {
		if (sessionPlan == null) return emptyList()
		val claim = sessionClaim
		val direct = sourceBroker.authorizationDemands(source).filter { demand ->
			demand.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
				(claim == null || (demand.logicalTrackingId == claim.logicalTrackingId &&
					demand.serviceRunId == claim.serviceRunId && demand.lifecycleLeaseGeneration == claim.leaseGeneration))
		}
		if (direct.isNotEmpty() || claim == null) return direct
		return listOfNotNull(sourceBroker.continuingStepsDemand(claim.logicalTrackingId,
			claim.serviceRunId, claim.leaseGeneration))
	}

	private fun effectivePlan(demands: List<SourceDemandEntity>): StepsPlan? {
		if (demands.isEmpty()) return null
		val attachedSessionPlan = sessionPlan
		return StepsPlan(
			revision = attachedSessionPlan?.revision ?: demands.maxOf(SourceDemandEntity::sourcePolicyRevision),
			enabled = true,
			maximumReportLatencyMs = minOf(
				attachedSessionPlan?.maximumReportLatencyMs ?: Long.MAX_VALUE,
				demands.minOf(SourceDemandEntity::desiredLatencyMs),
			),
			projectionCheckpointIntervalMs = attachedSessionPlan?.projectionCheckpointIntervalMs
				?: demands.minOf(SourceDemandEntity::maximumAgeMs),
			movementPolicyNeedsLowLatency = attachedSessionPlan?.movementPolicyNeedsLowLatency ?: false,
		)
	}

	private fun disabledState(plan: StepsPlan) = appliedState(
		source,
		plan.revision,
		null,
		SourceApplyStatus.APPLIED,
		SystemClock.elapsedRealtimeNanos(),
	)

	private fun blockedState(plan: StepsPlan) = appliedState(
		source,
		plan.revision,
		null,
		SourceApplyStatus.BLOCKED,
		SystemClock.elapsedRealtimeNanos(),
	)

	private fun SourceApplyResult.toStartResult(): SourceStartResult = when (this) {
		is SourceApplyResult.Applied -> SourceStartResult.Started(state)
		is SourceApplyResult.Degraded -> SourceStartResult.Degraded(state)
		is SourceApplyResult.Failed -> SourceStartResult.Failed(state, retryable)
		is SourceApplyResult.RolledBack -> SourceStartResult.Failed(state, retryable = true)
	}

	private companion object {
		const val AUTOMATIC_CONTROL_CONSUMER = "app:automatic-start:steps"
		const val LEGACY_CONTROL_MAXIMUM_AGE_MS = 30_000L
		const val LEGACY_CONTROL_LATENCY_MS = 5_000L
	}
}
