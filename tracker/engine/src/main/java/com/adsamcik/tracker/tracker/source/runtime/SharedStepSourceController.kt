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
 * Session lifecycle calls attach/detach one consumer. CONTROL_AUTOSTART is currently fenced off:
 * without an Activity Transition callback it has no legal cold-start trigger, so keeping the step
 * counter registered would spend power without being able to start tracking.
 */
@Singleton
class SharedStepSourceController @Inject constructor(
	private val physicalRuntime: StepSourceRuntime,
	private val sourceBroker: SourceBroker,
	sinkFactory: DurableSourceEventSinkFactory,
	private val clockDomainProvider: BootClockDomainProvider,
) : SourceRuntime<StepsPlan> {
	override val source: SourceKind = SourceKind.STEPS
	override val capabilities: StateFlow<SourceCapabilities> = physicalRuntime.capabilities

	private val mutex = Mutex()
	private val unboundSink = sinkFactory.unbound
	private var sessionPlan: StepsPlan? = null

	override suspend fun start(plan: StepsPlan, sink: SourceEventSink): SourceStartResult = mutex.withLock {
		@Suppress("UNUSED_VARIABLE") val sessionBoundSinkMustNotRelabelSharedEvidence = sink
		if (!plan.enabled) return@withLock SourceStartResult.Started(disabledState(plan))
		sessionPlan = plan
		val demands = selectedDemands()
		if (demands.none { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }) {
			sessionPlan = null
			reconcileRemainingControl()
			return@withLock SourceStartResult.Blocked(blockedState(plan))
		}
		applyEffective(requireNotNull(effectivePlan(demands))).toStartResult()
	}

	override suspend fun reconfigure(plan: StepsPlan, sink: SourceEventSink): SourceApplyResult = mutex.withLock {
		@Suppress("UNUSED_VARIABLE") val sessionBoundSinkMustNotRelabelSharedEvidence = sink
		sessionPlan = plan.takeIf(StepsPlan::enabled)
		val demands = selectedDemands()
		if (plan.enabled && demands.none { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }) {
			sessionPlan = null
			reconcileRemainingControl()
			return@withLock SourceApplyResult.Failed(blockedState(plan), retryable = false)
		}
		val effective = effectivePlan(demands)
		if (effective == null) {
			physicalRuntime.close()
			SourceApplyResult.Applied(disabledState(plan))
		} else {
			applyEffective(effective)
		}
	}

	override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck = mutex.withLock {
		sessionPlan = null
		val controlPlan = effectivePlan(selectedDemands())
		if (controlPlan == null) return@withLock physicalRuntime.quiesce(cutoff)

		// Authorization-only leave: callbacks already in the queue retain the old immutable
		// authorization, while callbacks entering after refresh carry the control-only vector.
		when (physicalRuntime.refreshCompatible(controlPlan, unboundSink)) {
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
	}

	/** Session owners release their join; no orphan automatic-control listener may remain. */
	override suspend fun close() = mutex.withLock {
		sessionPlan = null
		reconcileRemainingControl()
		Unit
	}

	/** Retires any legacy Steps CONTROL_AUTOSTART demand until a legal trigger contract exists. */
	@Suppress("UNUSED_PARAMETER")
	suspend fun reconcileAutomaticControl(enabled: Boolean): Boolean = mutex.withLock {
		val elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
		val demand = sourceBroker.replaceAutomaticControlDemand(
			consumerId = AUTOMATIC_CONTROL_CONSUMER,
			source = source,
			enabled = false,
			bootId = clockDomainProvider.current(),
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			wallTimeMs = System.currentTimeMillis(),
			maximumAgeMs = RECENT_CONTROL_STEP_WINDOW_MS,
			desiredLatencyMs = CONTROL_STEP_LATENCY_MS,
		)
		reconcileSelectedDemands()
		demand != null
	}

	/** Unknown after process death and false unless evidence matches the exact current CONTROL join. */
	fun hasRecentControlSteps(): Boolean = physicalRuntime.hasRecentControlSteps()

	private suspend fun reconcileSelectedDemands(): SourceApplyResult? {
		val effective = effectivePlan(selectedDemands())
		return if (effective == null) {
			physicalRuntime.close()
			null
		} else {
			applyEffective(effective)
		}
	}

	private suspend fun applyEffective(plan: StepsPlan): SourceApplyResult =
		physicalRuntime.refreshCompatible(plan, unboundSink)
			?: physicalRuntime.reconfigure(plan, unboundSink)

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
		const val RECENT_CONTROL_STEP_WINDOW_MS = 30_000L
		const val CONTROL_STEP_LATENCY_MS = 5_000L
	}
}
