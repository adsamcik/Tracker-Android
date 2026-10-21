package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReporter
import com.adsamcik.tracker.tracker.api.TrackingPurposeReconciliationRetryScheduler
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationDebt
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailure
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailureReason
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One Pressure listener shared by all-day recording and a session's temporary quality preference. */
@Singleton
@Suppress("TooManyFunctions") // Keeps the runtime interface and both purpose lifetimes in one owner.
class SharedPressureSourceController @Inject constructor(
	private val physicalRuntime: PressureSourceRuntime,
	private val broker: SourceBroker,
	sinkFactory: DurableSourceEventSinkFactory,
	private val reporter: TrackingPurposeAvailabilityReporter,
	private val retries: TrackingPurposeReconciliationRetryScheduler,
) : ClaimedSourceRuntime<PressurePlan> {
	override val source = SourceKind.PRESSURE
	override val capabilities: StateFlow<SourceCapabilities> = physicalRuntime.capabilities
	private val mutex = Mutex()
	private val sink = sinkFactory.unbound
	private var sessionPlan: PressurePlan? = null
	private var sessionClaim: SourceRuntimeClaim? = null
	private var ambientPlan: PressurePlan? = null

	/** A null plan releases only all-day recording; durable authority still decides every callback. */
	internal suspend fun reconcileAmbient(plan: PressurePlan?): SourceApplyResult = mutex.withLock {
		ambientPlan = plan?.takeIf(PressurePlan::enabled)
		applyEffective(plan?.revision ?: sessionPlan?.revision ?: 0L)
	}

	override suspend fun start(plan: PressurePlan, sink: SourceEventSink): SourceStartResult =
		mutex.withLock { applySession(null, plan).toStartResult() }

	override suspend fun start(claim: SourceRuntimeClaim, plan: PressurePlan, sink: SourceEventSink): SourceStartResult =
		mutex.withLock {
			require(claim.source == source)
			applySession(claim, plan).toStartResult()
		}

	override suspend fun reconfigure(plan: PressurePlan, sink: SourceEventSink): SourceApplyResult =
		mutex.withLock { applySession(null, plan) }

	override suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: PressurePlan,
		sink: SourceEventSink,
	): SourceApplyResult = mutex.withLock {
		require(claim.source == source)
		applySession(claim, plan)
	}

	private suspend fun applySession(claim: SourceRuntimeClaim?, plan: PressurePlan): SourceApplyResult {
		if (plan.enabled && !hasSessionDemand(claim)) {
			return SourceApplyResult.Failed(
				appliedState(source, plan.revision, null, SourceApplyStatus.BLOCKED, SystemClock.elapsedRealtimeNanos()),
				retryable = false,
			)
		}
		if (!plan.enabled && sessionClaim != null) {
			if (claim != null && claim != sessionClaim) {
				return SourceApplyResult.Failed(
					appliedState(source, plan.revision, null, SourceApplyStatus.BLOCKED, SystemClock.elapsedRealtimeNanos()),
					retryable = false,
				)
			}
			val acknowledgement = endSession(cutoff(requireNotNull(sessionClaim)))
			return if (acknowledgement.toOwnedShutdown() is OwnedSourceShutdown.Released) {
				SourceApplyResult.Applied(disabledState(plan.revision), acknowledgement)
			} else {
				SourceApplyResult.Failed(disabledState(plan.revision), retryable = true, stopAck = acknowledgement)
			}
		}
		val previousPlan = sessionPlan
		val previousClaim = sessionClaim
		sessionPlan = plan.takeIf(PressurePlan::enabled)
		sessionClaim = claim.takeIf { plan.enabled }
		var accepted = false
		return try {
			applyEffective(plan.revision).also { accepted = it.accepted() }
		} finally {
			if (!accepted) {
				sessionPlan = previousPlan
				sessionClaim = previousClaim
			}
		}
	}

	override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck = mutex.withLock { endSession(cutoff) }

	private suspend fun endSession(cutoff: SessionCutoff): SourceStopAck {
		val claim = sessionClaim
		require(claim == null || claim.logicalTrackingId == cutoff.logicalTrackingId)
		// Complete the existing flush and acknowledged removal before the lower-quality owner resumes.
		val acknowledgement = if (hasAmbientDemand()) {
			physicalRuntime.quiesceShared(cutoff)
		} else {
			physicalRuntime.quiesce(cutoff)
		}
		val sessionAcknowledgement = acknowledgement.withSessionMembership(claim)
		if (acknowledgement.toOwnedShutdown() is OwnedSourceShutdown.Released) {
			sessionPlan = null
			sessionClaim = null
			resumeAmbientAfterEnd()
		}
		return sessionAcknowledgement
	}

	override suspend fun shutdownIfOwned(claim: SourceRuntimeClaim, cutoff: SessionCutoff): OwnedSourceShutdown =
		mutex.withLock {
			require(claim.source == source)
			if (claim == sessionClaim) {
				endSession(cutoff).toOwnedShutdown()
			} else {
				physicalRuntime.shutdownIfOwned(claim, cutoff).also { shutdown ->
					if (shutdown is OwnedSourceShutdown.Released) {
						resumeAmbientAfterEnd()
					}
				}
			}
		}

	private suspend fun resumeAmbientAfterEnd() {
		val plan = ambientPlan ?: return
		val accepted = runCatchingNonCancellation {
			!hasAmbientDemand() || physicalRuntime.reconfigure(plan, sink).accepted()
		}.getOrDefault(false)
		if (!accepted) {
			reporter.invalidateAmbient(AmbientTrackingSource.PRESSURE)
			// End already completed its removal. Retry a failed background join independently.
			runCatchingNonCancellation {
				retries.schedule(TrackingPurposeSettingsReconciliationDebt(listOf(
					TrackingPurposeSettingsReconciliationFailure(AmbientTrackingSource.PRESSURE,
						TrackingPurposeSettingsReconciliationFailureReason.OWNER_RECONCILIATION_FAILED))))
			}
		}
	}

	override suspend fun close() = mutex.withLock {
		val claim = sessionClaim
		if (claim != null) {
			endSession(cutoff(claim))
		} else {
			sessionPlan = null
			applyEffective(ambientPlan?.revision ?: 0L)
		}
		Unit
	}

	private suspend fun applyEffective(revision: Long): SourceApplyResult {
		val effective = sessionPlan?.takeIf { hasSessionDemand(sessionClaim) }
			?: ambientPlan?.takeIf { hasAmbientDemand() }
			?: PressurePlan(revision, false, 1_000_000, 0, 60_000L)
		return sessionClaim?.takeIf { effective == sessionPlan }?.let { claim ->
			physicalRuntime.reconfigure(claim, effective, sink)
		} ?: physicalRuntime.reconfigure(effective, sink)
	}

	private suspend fun hasAmbientDemand(): Boolean = broker.authorizationDemands(source).any { demand ->
		demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && demand.persistenceEligible &&
			demand.logicalTrackingId == null && demand.serviceRunId == null
	}

	private suspend fun hasSessionDemand(claim: SourceRuntimeClaim?): Boolean =
		broker.authorizationDemands(source).any { demand ->
			demand.purpose == SourceBrokerPurpose.SESSION_CAPTURE && demand.persistenceEligible &&
				(claim == null || demand.matches(claim))
		}

	private fun SourceDemandEntity.matches(claim: SourceRuntimeClaim): Boolean =
		logicalTrackingId == claim.logicalTrackingId && serviceRunId == claim.serviceRunId &&
			lifecycleLeaseGeneration == claim.leaseGeneration

	private fun cutoff(claim: SourceRuntimeClaim): SessionCutoff {
		val now = SystemClock.elapsedRealtimeNanos()
		return SessionCutoff(claim.logicalTrackingId, now, System.currentTimeMillis(), now + STOP_TIMEOUT_NANOS)
	}

	private fun disabledState(revision: Long) =
		appliedState(source, revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos())

	private fun SourceApplyResult.accepted() = this is SourceApplyResult.Applied || this is SourceApplyResult.Degraded

	private fun SourceApplyResult.toStartResult(): SourceStartResult = when (this) {
		is SourceApplyResult.Applied -> SourceStartResult.Started(state)
		is SourceApplyResult.Degraded -> SourceStartResult.Degraded(state)
		is SourceApplyResult.Failed -> SourceStartResult.Failed(state, retryable)
		is SourceApplyResult.RolledBack -> SourceStartResult.Failed(state, retryable = true)
	}

	private companion object {
		const val STOP_TIMEOUT_NANOS = 3_000_000_000L
	}
}
