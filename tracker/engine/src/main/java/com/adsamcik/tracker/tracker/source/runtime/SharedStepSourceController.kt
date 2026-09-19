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

	private suspend fun startForClaimLocked(
		claim: SourceRuntimeClaim?,
		plan: StepsPlan,
		sink: SourceEventSink,
	): SourceStartResult {
		@Suppress("UNUSED_VARIABLE") val sessionBoundSinkMustNotRelabelSharedEvidence = sink
		if (!plan.enabled) {
			sessionClaim = null
			return SourceStartResult.Started(disabledState(plan))
		}
		val predecessorPlan = sessionPlan
		val predecessorClaim = sessionClaim
		sessionPlan = plan
		val demands = selectedDemands()
		if (demands.none { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }) {
			sessionPlan = null
			sessionClaim = null
			reconcileRemainingControl()
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

	private suspend fun reconfigureForClaimLocked(
		claim: SourceRuntimeClaim?,
		plan: StepsPlan,
		sink: SourceEventSink,
	): SourceApplyResult {
		@Suppress("UNUSED_VARIABLE") val sessionBoundSinkMustNotRelabelSharedEvidence = sink
		val predecessorPlan = sessionPlan
		val predecessorClaim = sessionClaim
		sessionPlan = plan.takeIf(StepsPlan::enabled)
		val demands = selectedDemands()
		if (plan.enabled && demands.none { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }) {
			sessionPlan = null
			sessionClaim = null
			reconcileRemainingControl()
			return SourceApplyResult.Failed(blockedState(plan), retryable = false)
		}
		val effective = effectivePlan(demands)
		return try {
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
	}

	override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck = mutex.withLock {
		quiesceLocked(cutoff).also(::clearClaimIfReleased)
	}

	private suspend fun quiesceLocked(cutoff: SessionCutoff): SourceStopAck {
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
	): SourceApplyResult = if (claim == null) {
		physicalRuntime.refreshCompatible(plan, unboundSink)
			?: physicalRuntime.reconfigure(plan, unboundSink)
	} else {
		physicalRuntime.reconfigure(claim, plan, unboundSink)
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

	private suspend fun selectedDemands(): List<SourceDemandEntity> =
		sourceBroker.authorizationDemands(source).filter { demand ->
			sessionPlan != null && demand.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		}

	private fun effectivePlan(demands: List<SourceDemandEntity>): StepsPlan? {
		if (demands.isEmpty()) return null
		val attachedSessionPlan = sessionPlan
		return StepsPlan(
			revision = attachedSessionPlan?.revision ?: demands.maxOf(SourceDemandEntity::sourcePolicyRevision),
			enabled = true,
			maximumReportLatencyMs = demands.minOf(SourceDemandEntity::desiredLatencyMs),
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
