package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One physical Cell owner shared by session capture and independently consented ambient demand. */
@Singleton
class SharedCellSourceController @Inject constructor(
	private val physicalRuntime: CellSourceRuntime,
	private val sourceBroker: SourceBroker,
	sinkFactory: DurableSourceEventSinkFactory,
) : ClaimedSourceRuntime<CellPlan> {
	override val source: SourceKind = SourceKind.CELL
	override val capabilities: StateFlow<SourceCapabilities> = physicalRuntime.capabilities
	private val mutex = Mutex()
	private val unboundSink = sinkFactory.unbound
	private var sessionPlan: CellPlan? = null
	private var sessionClaim: SourceRuntimeClaim? = null
	private var ambientAttached = false
	private var ambientSubscriptionIds: Set<Int> = emptySet()

	override suspend fun start(plan: CellPlan, sink: SourceEventSink): SourceStartResult =
		mutex.withLock { startLocked(null, plan, sink) }

	override suspend fun start(
		claim: SourceRuntimeClaim,
		plan: CellPlan,
		sink: SourceEventSink,
	): SourceStartResult = mutex.withLock {
		require(claim.source == source)
		startLocked(claim, plan, sink)
	}

	private suspend fun startLocked(
		claim: SourceRuntimeClaim?,
		plan: CellPlan,
		sink: SourceEventSink,
	): SourceStartResult {
		@Suppress("UNUSED_VARIABLE") val sessionSinkCannotRelabelSharedEvidence = sink
		if (!plan.enabled) return SourceStartResult.Started(disabledState(plan))
		val previousPlan = sessionPlan
		val previousClaim = sessionClaim
		sessionPlan = plan
		val demands = selectedDemands()
		if (demands.none { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }) {
			sessionPlan = previousPlan
			return SourceStartResult.Blocked(blockedState(plan))
		}
		return try {
			val result = applyEffective(requireNotNull(effectivePlan(demands)), claim)
			if (result is SourceApplyResult.Applied || result is SourceApplyResult.Degraded) {
				sessionClaim = claim
				ambientSubscriptionIds = plan.subscriptionIds
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

	override suspend fun reconfigure(plan: CellPlan, sink: SourceEventSink): SourceApplyResult =
		mutex.withLock { reconfigureLocked(null, plan, sink) }

	override suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: CellPlan,
		sink: SourceEventSink,
	): SourceApplyResult = mutex.withLock {
		require(claim.source == source)
		reconfigureLocked(claim, plan, sink)
	}

	private suspend fun reconfigureLocked(
		claim: SourceRuntimeClaim?,
		plan: CellPlan,
		sink: SourceEventSink,
	): SourceApplyResult {
		@Suppress("UNUSED_VARIABLE") val sessionSinkCannotRelabelSharedEvidence = sink
		if (!plan.enabled) {
			val claim = sessionClaim
			if (claim != null) {
				val cutoff = SessionCutoff(
					logicalTrackingId = claim.logicalTrackingId,
					elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
					wallTimeMs = System.currentTimeMillis(),
					deadlineElapsedRealtimeNanos = Long.MAX_VALUE,
				)
				val acknowledgement = quiesceLocked(cutoff)
				return if (acknowledgement.toOwnedShutdown() is OwnedSourceShutdown.Released) {
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
			val effective = effectivePlan(selectedDemands())
			return if (effective == null) {
				physicalRuntime.reconfigure(plan, unboundSink)
			} else {
				applyEffective(effective, claim = null)
			}
		}
		val previousPlan = sessionPlan
		val previousClaim = sessionClaim
		sessionPlan = plan
		val demands = selectedDemands()
		if (demands.none { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }) {
			sessionPlan = previousPlan
			return SourceApplyResult.Failed(blockedState(plan), retryable = false)
		}
		return try {
			applyEffective(requireNotNull(effectivePlan(demands)), claim).also { result ->
				if (result is SourceApplyResult.Applied || result is SourceApplyResult.Degraded) {
					sessionClaim = claim
					ambientSubscriptionIds = plan.subscriptionIds
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
		val priorPlan = sessionPlan
		val retiringClaim = sessionClaim
		priorPlan?.let { ambientSubscriptionIds = it.subscriptionIds }
		sessionPlan = null
		val demands = selectedDemands()
		val ambientPlan = effectivePlan(demands)
		if (ambientPlan == null) {
			ambientSubscriptionIds = emptySet()
			return physicalRuntime.quiesce(cutoff).withSessionMembership(retiringClaim).also {
				if (it.toOwnedShutdown() is OwnedSourceShutdown.Incomplete) sessionPlan = priorPlan
			}
		}
		val refreshed = physicalRuntime.refreshShared(ambientPlan, unboundSink, claim = null)
		val acknowledgement = if (
			refreshed is SourceApplyResult.Applied || refreshed is SourceApplyResult.Degraded
		) {
			ambientAttached = true
			physicalRuntime.sharedSessionCutoff(cutoff)
		} else {
			val stopped = physicalRuntime.quiesce(cutoff)
			if (stopped.toOwnedShutdown() is OwnedSourceShutdown.Released) {
				val restart = physicalRuntime.reconfigure(ambientPlan, unboundSink)
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

	internal suspend fun reconcileAmbientJoin(): AmbientCellRuntimeJoinResult = mutex.withLock {
		val demands = selectedDemands()
		val ambientDemand = demands.singleOrNull {
			it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT
		}
		if (ambientDemand == null && !ambientAttached && sessionPlan == null) {
			return@withLock AmbientCellRuntimeJoinResult.Inactive
		}
		val effective = effectivePlan(demands)
		if (effective == null) {
			val acknowledgement = physicalRuntime.closeShared()
			return@withLock if (acknowledgement.toOwnedShutdown() is OwnedSourceShutdown.Released) {
				ambientAttached = false
				ambientSubscriptionIds = emptySet()
				AmbientCellRuntimeJoinResult.Inactive
			} else {
				AmbientCellRuntimeJoinResult.Unavailable(emptySet(), retryable = true)
			}
		}
		when (val result = applyEffective(effective, sessionClaim)) {
			is SourceApplyResult.Applied -> {
				ambientAttached = ambientDemand != null
				if (ambientDemand == null) {
					AmbientCellRuntimeJoinResult.Inactive
				} else AmbientCellRuntimeJoinResult.Active(
					result.state.sourceInstanceId,
					result.state.registrationGeneration,
				)
			}
			is SourceApplyResult.Degraded -> {
				ambientAttached = ambientDemand != null
				AmbientCellRuntimeJoinResult.Degraded(
					result.state.sourceInstanceId,
					result.state.registrationGeneration,
					result.state.degradedReasons,
				)
			}
			is SourceApplyResult.Failed -> AmbientCellRuntimeJoinResult.Unavailable(
				result.state.degradedReasons,
				result.retryable,
			)
			is SourceApplyResult.RolledBack -> AmbientCellRuntimeJoinResult.Unavailable(
				result.state.degradedReasons,
				retryable = true,
			)
		}
	}

	override suspend fun close() = mutex.withLock {
		sessionPlan = null
		val demands = selectedDemands()
		val effective = effectivePlan(demands)
		if (effective == null) {
			physicalRuntime.close()
			ambientAttached = false
			ambientSubscriptionIds = emptySet()
		} else {
			val result = applyEffective(effective, claim = null)
			ambientAttached =
				(result is SourceApplyResult.Applied || result is SourceApplyResult.Degraded) &&
					demands.any { it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }
		}
		Unit
	}

	private suspend fun selectedDemands(): List<SourceDemandEntity> =
		sourceBroker.authorizationDemands(source).filter { demand ->
			demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT ||
				(sessionPlan != null && demand.purpose == SourceBrokerPurpose.SESSION_CAPTURE)
		}

	private fun effectivePlan(demands: List<SourceDemandEntity>): CellPlan? {
		if (demands.isEmpty()) return null
		val capturePlan = sessionPlan
		return CellPlan(
			revision = capturePlan?.revision
				?: demands.maxOf(SourceDemandEntity::sourcePolicyRevision),
			mode = capturePlan?.mode ?: CellMode.OBSERVE_CHANGES,
			minimumRefreshAttemptIntervalMs =
				capturePlan?.minimumRefreshAttemptIntervalMs ?: Long.MAX_VALUE,
			maximumAcceptableCachedAgeMs = demands.minOf(SourceDemandEntity::maximumAgeMs),
			subscriptionIds = capturePlan?.subscriptionIds ?: ambientSubscriptionIds,
			backoff = capturePlan?.backoff ?: RetryBackoff(0L, 0L),
		)
	}

	private suspend fun applyEffective(
		plan: CellPlan,
		claim: SourceRuntimeClaim?,
	): SourceApplyResult = physicalRuntime.refreshShared(plan, unboundSink, claim)
		?: if (claim == null) {
			physicalRuntime.reconfigure(plan, unboundSink)
		} else {
			physicalRuntime.reconfigure(claim, plan, unboundSink)
		}

	private fun disabledState(plan: CellPlan) = appliedState(
		source,
		plan.revision,
		null,
		SourceApplyStatus.APPLIED,
		SystemClock.elapsedRealtimeNanos(),
	)

	private fun blockedState(plan: CellPlan) = appliedState(
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

internal sealed interface AmbientCellRuntimeJoinResult {
	data object Inactive : AmbientCellRuntimeJoinResult
	data class Active(
		val sourceInstanceId: com.adsamcik.tracker.tracker.source.model.SourceInstanceId?,
		val registrationGeneration: Long?,
	) : AmbientCellRuntimeJoinResult
	data class Degraded(
		val sourceInstanceId: com.adsamcik.tracker.tracker.source.model.SourceInstanceId?,
		val registrationGeneration: Long?,
		val reasons: Set<com.adsamcik.tracker.tracker.source.model.SourceDegradedReason>,
	) : AmbientCellRuntimeJoinResult
	data class Unavailable(
		val reasons: Set<com.adsamcik.tracker.tracker.source.model.SourceDegradedReason>,
		val retryable: Boolean,
	) : AmbientCellRuntimeJoinResult
}
