package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single logical join owner for session and Ambient Wi-Fi demand.
 *
 * Every callback uses the unbound durable sink. Room authorization, not a process-local session
 * sink, decides whether the same provider delivery may materialize capture, ambient, or both.
 */
@Singleton
class SharedWifiSourceController @Inject constructor(
	private val physicalRuntime: WifiSourceRuntime,
	private val sourceBroker: SourceBroker,
	sinkFactory: DurableSourceEventSinkFactory,
) : ClaimedSourceRuntime<WifiPlan> {
	override val source: SourceKind = SourceKind.WIFI
	override val capabilities: StateFlow<SourceCapabilities> = physicalRuntime.capabilities
	private val mutex = Mutex()
	private val unboundSink = sinkFactory.unbound
	private var sessionPlan: WifiPlan? = null
	private var sessionClaim: SourceRuntimeClaim? = null
	private var ambientAttached = false

	override suspend fun start(plan: WifiPlan, sink: SourceEventSink): SourceStartResult =
		mutex.withLock { startLocked(null, plan, sink) }

	override suspend fun start(
		claim: SourceRuntimeClaim,
		plan: WifiPlan,
		sink: SourceEventSink,
	): SourceStartResult = mutex.withLock {
		require(claim.source == source)
		startLocked(claim, plan, sink)
	}

	private suspend fun startLocked(
		claim: SourceRuntimeClaim?,
		plan: WifiPlan,
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

	override suspend fun reconfigure(plan: WifiPlan, sink: SourceEventSink): SourceApplyResult =
		mutex.withLock { reconfigureLocked(null, plan, sink) }

	override suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: WifiPlan,
		sink: SourceEventSink,
	): SourceApplyResult = mutex.withLock {
		require(claim.source == source)
		reconfigureLocked(claim, plan, sink)
	}

	private suspend fun reconfigureLocked(
		claim: SourceRuntimeClaim?,
		plan: WifiPlan,
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
		sessionPlan = null
		val demands = selectedDemands()
		val ambientPlan = effectivePlan(demands)
		if (ambientPlan == null) {
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

	/** Reconciles only the durable ambient join; no caller fabricates a session identity. */
	internal suspend fun reconcileAmbientJoin(): AmbientWifiRuntimeJoinResult = mutex.withLock {
		val demands = selectedDemands()
		val ambientDemand = demands.singleOrNull {
			it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT
		}
		if (ambientDemand == null && !ambientAttached && sessionPlan == null) {
			return@withLock AmbientWifiRuntimeJoinResult.Inactive(providerKey = null)
		}
		val effective = effectivePlan(demands)
		if (effective == null) {
			val acknowledgement = physicalRuntime.closeShared()
			return@withLock if (acknowledgement.toOwnedShutdown() is OwnedSourceShutdown.Released) {
				ambientAttached = false
				AmbientWifiRuntimeJoinResult.Inactive(acknowledgement.providerKeyOrNull())
			} else {
				AmbientWifiRuntimeJoinResult.Unavailable(
					acknowledgement.providerKeyOrNull(),
					emptySet(),
					retryable = true,
				)
			}
		}
		val result = applyEffective(effective, sessionClaim)
		when (result) {
			is SourceApplyResult.Applied -> {
				ambientAttached = ambientDemand != null
				if (ambientDemand == null) {
					AmbientWifiRuntimeJoinResult.Inactive(result.state.providerKeyOrNull())
				} else AmbientWifiRuntimeJoinResult.Active(
					result.state.sourceInstanceId,
					result.state.registrationGeneration,
				)
			}
			is SourceApplyResult.Degraded -> {
				ambientAttached = ambientDemand != null
				AmbientWifiRuntimeJoinResult.Degraded(
					result.state.sourceInstanceId,
					result.state.registrationGeneration,
					result.state.degradedReasons,
				)
			}
			is SourceApplyResult.Failed -> AmbientWifiRuntimeJoinResult.Unavailable(
				result.state.providerKeyOrNull(),
				result.state.degradedReasons,
				result.retryable,
			)
			is SourceApplyResult.RolledBack -> AmbientWifiRuntimeJoinResult.Unavailable(
				result.state.providerKeyOrNull(),
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

	private fun effectivePlan(demands: List<SourceDemandEntity>): WifiPlan? {
		if (demands.isEmpty()) return null
		val capturePlan = sessionPlan
		val ambient = demands.any { it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }
		val mode = when {
			capturePlan == null -> WifiMode.BROADCAST_DRIVEN
			ambient && capturePlan.mode == WifiMode.CACHED_ONLY -> WifiMode.BROADCAST_DRIVEN
			else -> capturePlan.mode
		}
		return WifiPlan(
			revision = capturePlan?.revision
				?: demands.maxOf(SourceDemandEntity::sourcePolicyRevision),
			mode = mode,
			minimumAttemptIntervalMs = capturePlan?.minimumAttemptIntervalMs ?: Long.MAX_VALUE,
			maximumAcceptableResultAgeMs =
				demands.minOf(SourceDemandEntity::maximumAgeMs),
			unchangedResultDedupeWindowMs = capturePlan?.unchangedResultDedupeWindowMs
				?: demands.minOf(SourceDemandEntity::maximumAgeMs),
			backoff = capturePlan?.backoff ?: RetryBackoff(0L, 0L),
		)
	}

	private suspend fun applyEffective(
		plan: WifiPlan,
		claim: SourceRuntimeClaim?,
	): SourceApplyResult = physicalRuntime.refreshShared(plan, unboundSink, claim)
		?: if (claim == null) {
			physicalRuntime.reconfigure(plan, unboundSink)
		} else {
			physicalRuntime.reconfigure(claim, plan, unboundSink)
		}

	private fun disabledState(plan: WifiPlan) = appliedState(
		source,
		plan.revision,
		null,
		SourceApplyStatus.APPLIED,
		SystemClock.elapsedRealtimeNanos(),
	)

	private fun blockedState(plan: WifiPlan) = appliedState(
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

internal sealed interface AmbientWifiRuntimeJoinResult {
	data class Inactive(
		val providerKey: SourceProviderKey?,
	) : AmbientWifiRuntimeJoinResult
	data class Active(
		val sourceInstanceId: com.adsamcik.tracker.tracker.source.model.SourceInstanceId?,
		val registrationGeneration: Long?,
	) : AmbientWifiRuntimeJoinResult
	data class Degraded(
		val sourceInstanceId: com.adsamcik.tracker.tracker.source.model.SourceInstanceId?,
		val registrationGeneration: Long?,
		val reasons: Set<com.adsamcik.tracker.tracker.source.model.SourceDegradedReason>,
	) : AmbientWifiRuntimeJoinResult
	data class Unavailable(
		val providerKey: SourceProviderKey?,
		val reasons: Set<com.adsamcik.tracker.tracker.source.model.SourceDegradedReason>,
		val retryable: Boolean,
	) : AmbientWifiRuntimeJoinResult
}

internal fun com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan.providerKeyOrNull():
	SourceProviderKey? = sourceInstanceId?.let { instance ->
	registrationGeneration?.let { generation -> SourceProviderKey(instance, generation) }
}
