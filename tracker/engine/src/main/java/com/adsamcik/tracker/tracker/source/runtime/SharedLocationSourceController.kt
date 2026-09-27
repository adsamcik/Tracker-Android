package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single logical join owner for session and Ambient Location demand.
 *
 * Every callback uses the unbound durable sink. Room authorization, not a process-local session
 * sink, decides whether the same provider delivery may materialize capture, ambient, or both.
 */
@Singleton
class SharedLocationSourceController @Inject constructor(
	private val physicalRuntime: LocationSourceRuntime,
	private val sourceBroker: SourceBroker,
	sinkFactory: DurableSourceEventSinkFactory,
) : ClaimedSourceRuntime<LocationPlan> {
	override val source: SourceKind = SourceKind.LOCATION
	override val capabilities: StateFlow<SourceCapabilities> = physicalRuntime.capabilities
	private val mutex = Mutex()
	private val unboundSink = sinkFactory.unbound
	private var sessionPlan: LocationPlan? = null
	private var sessionClaim: SourceRuntimeClaim? = null
	private var ambientAttached = false
	private var baselinePlan: LocationPlan? = null
	private var baselinePolicyRevision: Long? = null

	override suspend fun start(plan: LocationPlan, sink: SourceEventSink): SourceStartResult =
		mutex.withLock { startLocked(null, plan, sink) }

	override suspend fun start(
		claim: SourceRuntimeClaim,
		plan: LocationPlan,
		sink: SourceEventSink,
	): SourceStartResult = mutex.withLock {
		require(claim.source == source)
		startLocked(claim, plan, sink)
	}

	private suspend fun startLocked(
		claim: SourceRuntimeClaim?,
		plan: LocationPlan,
		sink: SourceEventSink,
	): SourceStartResult {
		@Suppress("UNUSED_VARIABLE") val sessionSinkCannotRelabelSharedEvidence = sink
		if (!plan.enabled) return SourceStartResult.Started(disabledState(plan))
		val previousPlan = sessionPlan
		val previousClaim = sessionClaim
		sessionPlan = plan
		sessionClaim = claim
		val demands = try {
			selectedDemands()
		} catch (failure: Throwable) {
			sessionPlan = previousPlan
			sessionClaim = previousClaim
			throw failure
		}
		if (demands.none { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }) {
			sessionPlan = previousPlan
			sessionClaim = previousClaim
			return SourceStartResult.Blocked(blockedState(plan))
		}
		return try {
			val result = applyEffective(requireNotNull(effectivePlan(demands)), claim, demands)
			if (result is SourceApplyResult.Applied || result is SourceApplyResult.Degraded) {
				sessionClaim = claim
				ambientAttached = demands.any { it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }
			} else {
				sessionPlan = previousPlan
				sessionClaim = previousClaim
			}
			result.toStartResult()
		} catch (failure: Throwable) {
			sessionPlan = previousPlan
			sessionClaim = previousClaim
			throw failure
		}
	}

	override suspend fun reconfigure(plan: LocationPlan, sink: SourceEventSink): SourceApplyResult =
		mutex.withLock { reconfigureLocked(null, plan, sink) }

	override suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: LocationPlan,
		sink: SourceEventSink,
	): SourceApplyResult = mutex.withLock {
		require(claim.source == source)
		reconfigureLocked(claim, plan, sink)
	}

	private suspend fun reconfigureLocked(
		claim: SourceRuntimeClaim?,
		plan: LocationPlan,
		sink: SourceEventSink,
	): SourceApplyResult {
		@Suppress("UNUSED_VARIABLE") val sessionSinkCannotRelabelSharedEvidence = sink
		if (!plan.enabled) {
			if (claim != null && sessionClaim != null && claim != sessionClaim) {
				return SourceApplyResult.Failed(blockedState(plan), retryable = false)
			}
			val claim = sessionClaim
			if (claim != null) {
				val cutoff = SessionCutoff(
					logicalTrackingId = claim.logicalTrackingId,
					elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
					wallTimeMs = System.currentTimeMillis(),
					deadlineElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() + 3_000_000_000L,
				)
				val acknowledgement = quiesceLocked(cutoff)
				return if (acknowledgement.toOwnedShutdown() is OwnedSourceShutdown.Released) {
					if (sessionClaim == claim) sessionClaim = null
					SourceApplyResult.Applied(disabledState(plan), stopAck = acknowledgement)
				} else {
					SourceApplyResult.Failed(
						disabledState(plan),
						retryable = true,
						stopAck = acknowledgement,
					)
				}
			}
			sessionPlan = null
			val demands = selectedDemands()
			val effective = effectivePlan(demands)
			return if (effective == null) {
				physicalRuntime.reconfigure(plan, unboundSink)
			} else {
				applyEffective(effective, claim = null, demands = demands)
			}
		}
		val previousPlan = sessionPlan
		val previousClaim = sessionClaim
		sessionPlan = plan
		sessionClaim = claim
		val demands = try {
			selectedDemands()
		} catch (failure: Throwable) {
			sessionPlan = previousPlan
			sessionClaim = previousClaim
			throw failure
		}
		if (demands.none { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }) {
			sessionPlan = previousPlan
			sessionClaim = previousClaim
			return SourceApplyResult.Failed(blockedState(plan), retryable = false)
		}
		return try {
			applyEffective(requireNotNull(effectivePlan(demands)), claim, demands).also { result ->
				if (result is SourceApplyResult.Applied || result is SourceApplyResult.Degraded) {
					sessionClaim = claim
					ambientAttached = demands.any {
						it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT
					}
				} else {
					sessionPlan = previousPlan
					sessionClaim = previousClaim
				}
			}
		} catch (failure: Throwable) {
			sessionPlan = previousPlan
			sessionClaim = previousClaim
			throw failure
		}
	}

	override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck = mutex.withLock {
		quiesceLocked(cutoff).also { acknowledgement ->
			if (acknowledgement.toOwnedShutdown() is OwnedSourceShutdown.Released) {
				sessionClaim = null
			}
		}
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
		val priorPlan = sessionPlan
		val retiringClaim = sessionClaim
		sessionPlan = null
		val demands = selectedDemands()
		val ambientPlan = effectivePlan(demands)
		if (ambientPlan == null) {
			return physicalRuntime.quiesce(cutoff).withSessionMembership(retiringClaim).also {
				if (it.toOwnedShutdown() is OwnedSourceShutdown.Incomplete) sessionPlan = priorPlan
			}
		}
		val maximumAgeMs = demands.maxOf(SourceDemandEntity::maximumAgeMs)
		val refreshed = physicalRuntime.refreshShared(ambientPlan, unboundSink, null, maximumAgeMs)
		val acknowledgement = if (
			refreshed is SourceApplyResult.Applied || refreshed is SourceApplyResult.Degraded
		) {
			ambientAttached = true
			physicalRuntime.sharedSessionCutoff(cutoff)
		} else {
			val stopped = physicalRuntime.quiesce(cutoff)
			if (stopped.toOwnedShutdown() is OwnedSourceShutdown.Released) {
				val restart = physicalRuntime.reconfigureShared(ambientPlan, unboundSink, null, maximumAgeMs)
				ambientAttached =
					restart is SourceApplyResult.Applied || restart is SourceApplyResult.Degraded
			}
			stopped
		}
		return acknowledgement.withSessionMembership(retiringClaim).also {
			if (it.toOwnedShutdown() is OwnedSourceShutdown.Incomplete) sessionPlan = priorPlan
		}
	}

	override suspend fun shutdownIfOwned(
		claim: SourceRuntimeClaim,
		cutoff: SessionCutoff,
	): OwnedSourceShutdown = mutex.withLock {
		require(claim.source == source)
		if (sessionClaim != claim) return@withLock physicalRuntime.shutdownIfOwned(claim, cutoff)
		quiesceLocked(cutoff).toOwnedShutdown().also { shutdown ->
			if (shutdown is OwnedSourceShutdown.Released) sessionClaim = null
		}
	}

	/** Reconciles only the durable ambient join; no caller fabricates a session identity. */
	internal suspend fun reconcileAmbientJoin(
		plan: LocationPlan?,
		expectedPolicyRevision: Long,
	): AmbientLocationRuntimeJoinResult = mutex.withLock {
		require(expectedPolicyRevision > 0L)
		val currentDemands = sourceBroker.authorizationDemands(source)
		val ambientDemands = currentDemands.filter {
			it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && it.consumerId == "ambient:location"
		}
		// A disabled exact plan suspends provider work without revoking the durable purpose.
		// Null exclusively means that the caller has retired its durable purpose authority.
		val requested = plan
		val stale = baselinePolicyRevision?.let { expectedPolicyRevision < it } == true
		val exactDemand = ambientDemands.singleOrNull()?.takeIf {
			it.sourcePolicyRevision == expectedPolicyRevision &&
				it.logicalTrackingId == null && it.serviceRunId == null
		}
		if (stale || (requested != null && exactDemand == null) ||
			(requested == null && ambientDemands.isNotEmpty())
		) {
			return@withLock if (!ambientAttached && sessionPlan == null) {
				AmbientLocationRuntimeJoinResult.Inactive(null)
			} else {
				AmbientLocationRuntimeJoinResult.Unavailable(null, emptySet(), retryable = false)
			}
		}
		baselinePlan = requested
		baselinePolicyRevision = expectedPolicyRevision
		val demands = selectedDemands()
		val ambientDemand = demands.singleOrNull {
			it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT
		}
		val ambientEnabled = ambientDemand != null && baselinePlan?.enabled == true
		if (ambientDemand == null && !ambientAttached && sessionPlan == null) {
			return@withLock AmbientLocationRuntimeJoinResult.Inactive(providerKey = null)
		}
		val effective = effectivePlan(demands)
		if (effective == null) {
			val acknowledgement = physicalRuntime.closeShared()
			return@withLock if (acknowledgement.toOwnedShutdown() is OwnedSourceShutdown.Released) {
				ambientAttached = false
				AmbientLocationRuntimeJoinResult.Inactive(acknowledgement.providerKeyOrNull())
			} else {
				AmbientLocationRuntimeJoinResult.Unavailable(
					acknowledgement.providerKeyOrNull(),
					emptySet(),
					retryable = true,
				)
			}
		}
		val result = applyEffective(effective, sessionClaim, demands)
		when (result) {
			is SourceApplyResult.Applied -> {
				ambientAttached = ambientEnabled
				if (!ambientEnabled) {
					AmbientLocationRuntimeJoinResult.Inactive(result.state.providerKeyOrNull())
				} else AmbientLocationRuntimeJoinResult.Active(
					result.state.sourceInstanceId,
					result.state.registrationGeneration,
				)
			}
			is SourceApplyResult.Degraded -> {
				ambientAttached = ambientEnabled
				if (!ambientEnabled) AmbientLocationRuntimeJoinResult.Inactive(result.state.providerKeyOrNull())
				else AmbientLocationRuntimeJoinResult.Degraded(
					result.state.sourceInstanceId,
					result.state.registrationGeneration,
					result.state.degradedReasons,
				)
			}
			is SourceApplyResult.Failed -> AmbientLocationRuntimeJoinResult.Unavailable(
				result.state.providerKeyOrNull(),
				result.state.degradedReasons,
				result.retryable,
			)
			is SourceApplyResult.RolledBack -> AmbientLocationRuntimeJoinResult.Unavailable(
				result.state.providerKeyOrNull(),
				result.state.degradedReasons,
				retryable = true,
			)
		}
	}

	internal suspend fun closeAmbientForCollectedDataDeletion(): Boolean = mutex.withLock {
		val ambientDemand = selectedDemands().any {
			it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT
		}
		if (ambientDemand) return@withLock false
		ambientAttached = false
		if (sessionPlan != null) return@withLock false
		physicalRuntime.closeShared().toOwnedShutdown() is OwnedSourceShutdown.Released
	}

	override suspend fun close() = mutex.withLock {
		val retiringClaim = sessionClaim
		if (retiringClaim != null) {
			val now = SystemClock.elapsedRealtimeNanos()
			val acknowledgement = quiesceLocked(
				SessionCutoff(retiringClaim.logicalTrackingId, now, System.currentTimeMillis(),
					now + 3_000_000_000L),
			)
			if (acknowledgement.toOwnedShutdown() is OwnedSourceShutdown.Released) sessionClaim = null
			return@withLock Unit
		}
		sessionPlan = null
		sessionClaim = null
		val demands = selectedDemands()
		val effective = effectivePlan(demands)
		if (effective == null) {
			physicalRuntime.close()
			ambientAttached = false
		} else {
			val result = applyEffective(effective, claim = null, demands = demands)
			ambientAttached =
				(result is SourceApplyResult.Applied || result is SourceApplyResult.Degraded) &&
					demands.any { it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }
		}
		Unit
	}

	private suspend fun selectedDemands(): List<SourceDemandEntity> =
		sourceBroker.authorizationDemands(source).filter { demand ->
			when (demand.purpose) {
				SourceBrokerPurpose.AMBIENT_PRODUCT -> baselinePlan != null &&
					demand.consumerId == "ambient:location" &&
					demand.sourcePolicyRevision == baselinePolicyRevision &&
					demand.logicalTrackingId == null && demand.serviceRunId == null
				SourceBrokerPurpose.SESSION_CAPTURE -> sessionPlan != null &&
					(sessionClaim == null || (demand.logicalTrackingId == sessionClaim?.logicalTrackingId &&
						demand.serviceRunId == sessionClaim?.serviceRunId &&
						demand.lifecycleLeaseGeneration == sessionClaim?.leaseGeneration))
				else -> false
			}
		}

	private fun effectivePlan(demands: List<SourceDemandEntity>): LocationPlan? {
		val capture = sessionPlan.takeIf { demands.any { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE } }
		val background = baselinePlan?.takeIf { it.enabled &&
			demands.any { demand -> demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }
		}
		return joinLocationPlans(capture, background)
	}

	private suspend fun applyEffective(
		plan: LocationPlan,
		claim: SourceRuntimeClaim?,
		demands: List<SourceDemandEntity>,
	): SourceApplyResult {
		val maximumAgeMs = demands.maxOf(SourceDemandEntity::maximumAgeMs)
		return physicalRuntime.refreshShared(plan, unboundSink, claim, maximumAgeMs)
			?: physicalRuntime.reconfigureShared(plan, unboundSink, claim, maximumAgeMs)
	}

	private fun disabledState(plan: LocationPlan) = appliedState(
		source,
		plan.revision,
		null,
		SourceApplyStatus.APPLIED,
		SystemClock.elapsedRealtimeNanos(),
	)

	private fun blockedState(plan: LocationPlan) = appliedState(
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
}

/** One physical request covers both independently authorized purposes; passive adds no acquisition. */
internal fun joinLocationPlans(capture: LocationPlan?, background: LocationPlan?): LocationPlan? {
	if (capture?.enabled == false) return background?.takeIf { it.enabled }
	if (background?.enabled == false) return capture?.takeIf { it.enabled }
	if (capture == null) return background
	if (background == null) return capture
	if (background.mode == LocationMode.PASSIVE) return capture
	if (capture.mode == LocationMode.PASSIVE) return background.copy(revision = capture.revision)
	val mode = if (capture.mode.ordinal >= background.mode.ordinal) capture.mode else background.mode
	// A continuing active background purpose must not inherit a finite session probe lifetime.
	return capture.copy(
		mode = if (mode == LocationMode.PROBE) LocationMode.HIGH_ACCURACY else mode,
		requestedIntervalMs = minOf(capture.requestedIntervalMs, background.requestedIntervalMs),
		minimumUpdateIntervalMs = minOf(capture.minimumUpdateIntervalMs, background.minimumUpdateIntervalMs),
		minimumDisplacementMeters = minOf(capture.minimumDisplacementMeters, background.minimumDisplacementMeters),
		maximumBatchDelayMs = minOf(capture.maximumBatchDelayMs, background.maximumBatchDelayMs),
		probeDurationMs = null,
		preciseLocationAvailable = capture.preciseLocationAvailable && background.preciseLocationAvailable,
		adaptiveProfileVersion = capture.adaptiveProfileVersion,
	)
}

internal sealed interface AmbientLocationRuntimeJoinResult {
	data class Inactive(
		val providerKey: SourceProviderKey?,
	) : AmbientLocationRuntimeJoinResult
	data class Active(
		val sourceInstanceId: com.adsamcik.tracker.tracker.source.model.SourceInstanceId?,
		val registrationGeneration: Long?,
	) : AmbientLocationRuntimeJoinResult
	data class Degraded(
		val sourceInstanceId: com.adsamcik.tracker.tracker.source.model.SourceInstanceId?,
		val registrationGeneration: Long?,
		val reasons: Set<com.adsamcik.tracker.tracker.source.model.SourceDegradedReason>,
	) : AmbientLocationRuntimeJoinResult
	data class Unavailable(
		val providerKey: SourceProviderKey?,
		val reasons: Set<com.adsamcik.tracker.tracker.source.model.SourceDegradedReason>,
		val retryable: Boolean,
	) : AmbientLocationRuntimeJoinResult
}

