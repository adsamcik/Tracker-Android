package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.DemandReason
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.EvidenceQuality
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Immutable source ownership selected once for an Android service run. */
data class TrackingSessionOwnership(
	val rollout: TrackingRolloutState,
	val enabledEventSources: Set<SourceKind>,
) {
	val eventCoordinatorRequired: Boolean get() = enabledEventSources.isNotEmpty()

	companion object {
		fun resolve(rollout: TrackingRolloutState, settings: TrackingParamsState): TrackingSessionOwnership {
			val enabled = settings.enabledSemanticSources()
			require(rollout.coordinatorMode == CoordinatorMode.EVENT) {
				"Source-native acquisition requires the event coordinator"
			}
			require(enabled.all { source -> rollout.sourceOwners[source] == SourceOwner.EVENT }) {
				"Every enabled source must have source-native acquisition ownership"
			}
			return TrackingSessionOwnership(
				rollout = rollout,
				enabledEventSources = enabled,
			)
		}
	}
}

data class SourceSessionPlanInputs(
	val settings: TrackingParamsState,
	val environment: SourcePlanEnvironment,
	val resolutionContext: PlanResolutionContext,
	val demands: List<SourceDemand>,
	val clockDomainId: String,
	val zoneId: String = ZoneId.systemDefault().id,
)

data class SourceSessionStartRequest(
	val rollout: TrackingRolloutState,
	val ownership: TrackingSessionOwnership,
	val logicalTrackingId: String,
	val serviceRunId: String,
	val origin: SessionStartOrigin,
	val continuationAuthority: ServiceRunContinuationAuthority? = null,
	val automaticTrigger: AutomaticTrackingStartTrigger? = null,
	val foregroundCapabilityFlags: Long,
	val planInputs: SourceSessionPlanInputs,
	val ownerToken: String,
)

sealed interface SourceSessionStartOutcome {
	data class Started(val result: SessionStartResult.Started) : SourceSessionStartOutcome
	data object NotRequired : SourceSessionStartOutcome
	data class Rejected(val result: SessionStartResult) : SourceSessionStartOutcome
}

sealed interface SourceSessionReconfigureOutcome {
	data class Applied(val result: SessionReconfigureResult.Applied) : SourceSessionReconfigureOutcome
	data class Started(val result: SessionStartResult.Started) : SourceSessionReconfigureOutcome
	data object NotActive : SourceSessionReconfigureOutcome
	data object Unchanged : SourceSessionReconfigureOutcome
	data class Rejected(val result: SessionReconfigureResult) : SourceSessionReconfigureOutcome
}

/** Factual end of session attribution, which may precede physical source cleanup. */
internal data class SourceSessionStopCutoff(
	val wallTimeMs: Long,
	val elapsedRealtimeNanos: Long,
	val clockDomainId: String,
) {
	init {
		require(wallTimeMs >= 0L)
		require(elapsedRealtimeNanos >= 0L)
		require(clockDomainId.isNotBlank())
	}
}

/**
 * TrackerService-facing owner of the event-source session. It snapshots rollout ownership,
 * serializes settings/policy revisions, and delegates durable lifecycle fencing to the
 * authoritative coordinator.
 */
class TrackerServiceSourceSession @Inject constructor(
	private val database: AppDatabase,
	private val coordinator: AuthoritativeSessionCoordinator,
	private val planFactory: SemanticAcquisitionPlanFactory,
	private val planResolver: SourcePlanResolver,
	private val telemetry: TrackingCoordinatorTelemetry,
	private val settingsStatusProvider: TrackingSettingsStatusProvider,
	private val trackingStartupGateProvider: Provider<TrackingStartupGate>,
) {
	private val mutex = Mutex()
	private var active: ActiveSession? = null
	private var pendingInputs: SourceSessionPlanInputs? = null

	/** Builds and durably persists the exact plan without touching a provider runtime. */
	suspend fun prepareAndroidStartUnderReadyGeneration(
		request: SourceSessionStartRequest,
		delivery: AndroidStartDeliveryMetadata,
		startupGeneration: Long,
	): SessionStartPreparationResult = mutex.withLock {
		require(request.rollout == request.ownership.rollout) {
			"Ownership must use the supplied rollout snapshot"
		}
		require(request.logicalTrackingId.isNotBlank())
		require(request.serviceRunId.isNotBlank())
		val planInputs = pendingInputs
			?.takeIf { pending -> pending.isAtLeastAsCurrentAs(request.planInputs) }
			?: request.planInputs
		val ownership = TrackingSessionOwnership.resolve(request.rollout, planInputs.settings)
		if (ownership.eventCoordinatorRequired &&
			!trackingStartupGateProvider.get().isReadyGeneration(startupGeneration)
		) {
			return@withLock SessionStartPreparationResult.Rejected(STARTUP_RECOVERY_NOT_READY)
		}
		val plan = buildPlan(request.rollout, planInputs, requireEnabled = true)
		coordinator.prepareAndroidStart(
			SessionStartRequest(
				ownerToken = request.ownerToken,
				origin = request.origin,
				plan = plan,
				rolloutRevision = request.rollout.revision,
				clockDomainId = planInputs.clockDomainId,
				foregroundCapabilityFlags = request.foregroundCapabilityFlags,
				wallTimeMs = Time.nowMillis,
				elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
				zoneId = planInputs.zoneId,
				controlDependencies = controlDependencies(request.origin),
				automaticTrigger = request.automaticTrigger,
				logicalTrackingId = request.logicalTrackingId,
				serviceRunId = request.serviceRunId,
				continuationAuthority = request.continuationAuthority,
			),
			delivery,
		)
	}

	/** Applies an already foreground-accepted Room plan and attaches it for later reconfigure/stop. */
	suspend fun applyPreparedAndroidStart(
		claim: ClaimedPreparedSessionStart,
		commandGeneration: Long,
		planInputs: SourceSessionPlanInputs,
	): SessionStartResult = mutex.withLock {
		val rollout = RoomTrackingRolloutStateStore(database).load()
		if (rollout.revision != database.sourceSessionDao().serviceRun(claim.serviceRunId)?.rolloutRevision) {
			return@withLock SessionStartResult.InvalidRollout("PREPARED_START_ROLLOUT_STALE")
		}
		val applyingSession = ActiveSession(
			rollout = rollout,
			ownerToken = "prepared-start:${claim.token.value}",
			logicalTrackingId = claim.logicalTrackingId,
			serviceRunId = claim.serviceRunId,
			origin = claim.startOrigin,
			automaticTrigger = null,
			foregroundCapabilityFlags = claim.desiredForegroundCapabilityFlags,
			lastInputs = planInputs,
			coordinatorStarted = true,
		)
		// Attach cleanup ownership before the first provider side effect. If the Android service is
		// stopped and cancels this coroutine mid-apply, stop() must still fence a partially-started
		// runtime and terminalize the durable STARTING run.
		active = applyingSession
		val result = coordinator.applyPreparedAndroidStart(
			token = claim.token,
			commandGeneration = commandGeneration,
			currentBootId = planInputs.clockDomainId,
			elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
			wallTimeMs = Time.nowMillis,
		)
		if (result is SessionStartResult.Started) {
			pendingInputs = null
			settingsStatusProvider.publishApplied(result.applied)
		} else {
			active = null
			settingsStatusProvider.publishFailure("SESSION_START_REJECTED")
		}
		result
	}

	suspend fun start(request: SourceSessionStartRequest): SourceSessionStartOutcome {
		val startupGate = trackingStartupGateProvider.get()
		if (startupGate.reconcile() !is TrackingStartupResult.Ready) {
			return startupRejectedStart()
		}
		val startupGeneration = startupGate.currentGeneration
		return startupGate.withReadyGenerationOperation(startupGeneration) {
			startUnderReadyGeneration(request)
		} ?: startupRejectedStart()
	}

	private suspend fun startUnderReadyGeneration(
		request: SourceSessionStartRequest,
	): SourceSessionStartOutcome = mutex.withLock {
		require(request.rollout == request.ownership.rollout) { "Ownership must use the supplied rollout snapshot" }
		require(request.logicalTrackingId.isNotBlank())
		require(request.serviceRunId.isNotBlank())
		val planInputs = pendingInputs
			?.takeIf { pending -> pending.isAtLeastAsCurrentAs(request.planInputs) }
			?: request.planInputs
		val ownership = TrackingSessionOwnership.resolve(request.rollout, planInputs.settings)
		pendingInputs = null
		val session = ActiveSession(
			rollout = request.rollout,
			ownerToken = request.ownerToken,
			logicalTrackingId = request.logicalTrackingId,
			serviceRunId = request.serviceRunId,
			origin = request.origin,
			automaticTrigger = request.automaticTrigger,
			foregroundCapabilityFlags = request.foregroundCapabilityFlags,
			lastInputs = planInputs,
			coordinatorStarted = false,
		)
		active = session
		if (!ownership.eventCoordinatorRequired) {
			settingsStatusProvider.publishActivePreview(
				planInputs.settings,
				request.rollout,
				planInputs,
			)
			return@withLock SourceSessionStartOutcome.NotRequired
		}
		check(request.rollout.coordinatorMode == CoordinatorMode.EVENT) {
			"Event-owned sources require event coordinator mode"
		}
		val result = startCoordinator(session, planInputs)
		if (result is SessionStartResult.Started) {
			session.coordinatorStarted = true
			settingsStatusProvider.publishApplied(result.applied)
			SourceSessionStartOutcome.Started(result)
		} else {
			settingsStatusProvider.publishFailure("SESSION_START_REJECTED")
			active = null
			settingsStatusProvider.publishInactive()
			SourceSessionStartOutcome.Rejected(result)
		}
	}

	private fun startupRejectedStart(): SourceSessionStartOutcome {
		settingsStatusProvider.publishFailure(STARTUP_RECOVERY_NOT_READY)
		return SourceSessionStartOutcome.Rejected(
			SessionStartResult.InvalidIntent(STARTUP_RECOVERY_NOT_READY),
		)
	}

	suspend fun reconfigure(inputs: SourceSessionPlanInputs): SourceSessionReconfigureOutcome {
		val inactive = mutex.withLock {
			if (active == null) {
				pendingInputs = pendingInputs
					?.takeIf { current -> current.isNewerThan(inputs) }
					?: inputs
				SourceSessionReconfigureOutcome.NotActive
			} else {
				null
			}
		}
		if (inactive != null) return inactive

		val startupGate = trackingStartupGateProvider.get()
		if (startupGate.reconcile() !is TrackingStartupResult.Ready) {
			return startupRejectedReconfigure()
		}
		val startupGeneration = startupGate.currentGeneration
		return startupGate.withReadyGenerationOperation(startupGeneration) {
			reconfigureUnderReadyGeneration(inputs)
		} ?: startupRejectedReconfigure()
	}

	private suspend fun reconfigureUnderReadyGeneration(
		inputs: SourceSessionPlanInputs,
	): SourceSessionReconfigureOutcome = mutex.withLock {
		val session = active ?: run {
			pendingInputs = pendingInputs
				?.takeIf { current -> current.isNewerThan(inputs) }
				?: inputs
			return@withLock SourceSessionReconfigureOutcome.NotActive
		}
		if (session.lastInputs == inputs) return@withLock SourceSessionReconfigureOutcome.Unchanged
		if (!session.coordinatorStarted) {
			val ownership = TrackingSessionOwnership.resolve(session.rollout, inputs.settings)
			if (!ownership.eventCoordinatorRequired) {
				session.lastInputs = inputs
				settingsStatusProvider.publishActivePreview(inputs.settings, session.rollout, inputs)
				return@withLock SourceSessionReconfigureOutcome.Unchanged
			}
			session.lastInputs = inputs
			val started = startCoordinator(session, inputs)
			return@withLock if (started is SessionStartResult.Started) {
				session.coordinatorStarted = true
				settingsStatusProvider.publishApplied(started.applied)
				SourceSessionReconfigureOutcome.Started(started)
			} else {
				settingsStatusProvider.publishFailure("SESSION_START_REJECTED")
				SourceSessionReconfigureOutcome.Rejected(
					SessionReconfigureResult.InvalidState("START_REJECTED:$started"),
				)
			}
		}
		val plan = buildPlan(session.rollout, inputs, requireEnabled = false)
		val result = coordinator.reconfigure(
			SessionReconfigureRequest(
				ownerToken = session.ownerToken,
				plan = plan,
				wallTimeMs = Time.nowMillis,
				elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
				clockDomainId = inputs.clockDomainId,
				zoneId = inputs.zoneId,
				foregroundCapabilityFlags = session.foregroundCapabilityFlags,
				controlDependencies = controlDependencies(session.origin),
			),
		)
		if (result is SessionReconfigureResult.Applied) {
			session.lastInputs = inputs
			settingsStatusProvider.publishApplied(result.applied)
			SourceSessionReconfigureOutcome.Applied(result)
		} else {
			settingsStatusProvider.publishFailure("PLAN_RECONFIGURE_REJECTED")
			SourceSessionReconfigureOutcome.Rejected(result)
		}
	}

	private fun startupRejectedReconfigure(): SourceSessionReconfigureOutcome {
		settingsStatusProvider.publishFailure(STARTUP_RECOVERY_NOT_READY)
		return SourceSessionReconfigureOutcome.Rejected(
			SessionReconfigureResult.InvalidState(STARTUP_RECOVERY_NOT_READY),
		)
	}

	internal suspend fun stop(
		reason: String,
		preserveLogicalSession: Boolean,
		factualCutoff: SourceSessionStopCutoff? = null,
	) = mutex.withLock {
		val session = active ?: run {
			pendingInputs = null
			settingsStatusProvider.publishInactive()
			return@withLock
		}
		if (!session.coordinatorStarted) {
			active = null
			settingsStatusProvider.publishInactive()
			return@withLock
		}
		if (preserveLogicalSession) {
			val currentCutoff = currentStopCutoff(session.lastInputs.clockDomainId)
			when (val result = coordinator.suspendForRestart(
				SessionSuspendRequest(
					ownerToken = session.ownerToken,
					reason = reason,
					wallTimeMs = currentCutoff.wallTimeMs,
					elapsedRealtimeNanos = currentCutoff.elapsedRealtimeNanos,
					clockDomainId = currentCutoff.clockDomainId,
				),
			)) {
				is SessionSuspendResult.Suspended,
				SessionSuspendResult.NoActiveSession,
				-> active = null
				is SessionSuspendResult.DrainPending -> error(
					"Event-source suspension drain pending through ${result.requiredOrdinal}",
				)
				SessionSuspendResult.Busy -> error("Event-source session coordinator is busy")
			}
		} else {
			val cutoff = factualCutoff
				?.takeIf { it.clockDomainId == session.lastInputs.clockDomainId }
				?: currentStopCutoff(session.lastInputs.clockDomainId)
			when (val result = coordinator.stop(
				SessionStopRequest(
					ownerToken = session.ownerToken,
					reason = reason,
					wallTimeMs = cutoff.wallTimeMs,
					elapsedRealtimeNanos = cutoff.elapsedRealtimeNanos,
					clockDomainId = cutoff.clockDomainId,
				),
			)) {
				is SessionStopResult.Stopped,
				SessionStopResult.NoActiveSession,
				-> active = null
				is SessionStopResult.DrainPending -> error(
					"Event-source shutdown drain pending through ${result.requiredOrdinal}",
				)
				SessionStopResult.Busy -> error("Event-source session coordinator is busy")
			}
		}
		settingsStatusProvider.publishInactive()
	}

	private fun currentStopCutoff(clockDomainId: String) = SourceSessionStopCutoff(
		wallTimeMs = Time.nowMillis,
		elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
		clockDomainId = clockDomainId,
	)

	private suspend fun startCoordinator(
		session: ActiveSession,
		inputs: SourceSessionPlanInputs,
	): SessionStartResult {
		val plan = buildPlan(session.rollout, inputs, requireEnabled = true)
		return coordinator.start(
			SessionStartRequest(
				ownerToken = session.ownerToken,
				origin = session.origin,
				plan = plan,
				rolloutRevision = session.rollout.revision,
				clockDomainId = inputs.clockDomainId,
				foregroundCapabilityFlags = session.foregroundCapabilityFlags,
				wallTimeMs = Time.nowMillis,
				elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
				zoneId = inputs.zoneId,
				controlDependencies = controlDependencies(session.origin),
				automaticTrigger = session.automaticTrigger,
				logicalTrackingId = session.logicalTrackingId,
				serviceRunId = session.serviceRunId,
			),
		)
	}

	private suspend fun buildPlan(
		rollout: TrackingRolloutState,
		inputs: SourceSessionPlanInputs,
		requireEnabled: Boolean,
	): AcquisitionPlanRevision {
		val revision = (database.sourcePlanStateDao().latestRevision()?.revision ?: 0L) + 1L
		val desired = planFactory.create(inputs.settings, revision, Time.nowMillis, inputs.environment)
		val captureDemands = desired.plans
			.filter { (source, plan) ->
				plan.enabled && rollout.sourceOwners.getValue(source) == SourceOwner.EVENT
			}
			.map { (source, plan) ->
				plan.directCaptureDemand(inputs.settings.captureQosCode(source))
			}
		val resolved = planResolver.resolve(
			desired,
			inputs.demands + captureDemands,
			inputs.resolutionContext,
		)
		settingsStatusProvider.publishResolved(inputs.settings, rollout, resolved)
		telemetry.recordPlanRevision()
		val eventPlans = resolved.applicablePlans.filterKeys { source ->
			rollout.sourceOwners.getValue(source) == SourceOwner.EVENT
		}
		if (requireEnabled) {
			check(eventPlans.values.any { plan -> plan.enabled }) {
				"Event session requires an enabled event-owned source"
			}
		}
		return AcquisitionPlanRevision(
			revision = revision,
			planId = "${desired.planId}-event-rollout-${rollout.revision}",
			createdAtMs = desired.createdAtMs,
			plans = eventPlans,
			sourcePolicyRevision = desired.sourcePolicyRevision,
		)
	}

	private data class ActiveSession(
		val rollout: TrackingRolloutState,
		val ownerToken: String,
		val logicalTrackingId: String,
		val serviceRunId: String,
		val origin: SessionStartOrigin,
		val automaticTrigger: AutomaticTrackingStartTrigger?,
		val foregroundCapabilityFlags: Long,
		var lastInputs: SourceSessionPlanInputs,
		var coordinatorStarted: Boolean,
	)
}

private const val STARTUP_RECOVERY_NOT_READY = "STARTUP_RECOVERY_NOT_READY"

private fun controlDependencies(
	origin: SessionStartOrigin,
): Set<SourceKind> = if (origin != SessionStartOrigin.AUTOMATIC_BACKGROUND_START) {
	emptySet()
} else {
	setOf(SourceKind.ACTIVITY)
}

private fun SourceSessionPlanInputs.isNewerThan(other: SourceSessionPlanInputs): Boolean {
	val candidateRevision = settings.sourcePolicyRevision ?: return false
	val otherRevision = other.settings.sourcePolicyRevision
	return otherRevision == null || candidateRevision > otherRevision
}

private fun SourceSessionPlanInputs.isAtLeastAsCurrentAs(other: SourceSessionPlanInputs): Boolean {
	val candidateRevision = settings.sourcePolicyRevision ?: return false
	val otherRevision = other.settings.sourcePolicyRevision
	return otherRevision == null || candidateRevision >= otherRevision
}

private fun TrackingParamsState.enabledSemanticSources(): Set<SourceKind> = buildSet {
	val frequency = sourceCollectionSettings
	if (frequency.location != SourceCollectionFrequency.OFF && locationEnabled) add(SourceKind.LOCATION)
	if (frequency.activity != SourceCollectionFrequency.OFF && activityEnabled) add(SourceKind.ACTIVITY)
	if (frequency.steps != SourceCollectionFrequency.OFF && stepsEnabled) add(SourceKind.STEPS)
	if (frequency.pressure != SourceCollectionFrequency.OFF && barometerEnabled) add(SourceKind.PRESSURE)
	if (frequency.wifi != SourceCollectionFrequency.OFF && wifiEnabled) add(SourceKind.WIFI)
	if (frequency.cell != SourceCollectionFrequency.OFF && cellEnabled) add(SourceKind.CELL)
}


private fun TrackingParamsState.captureQosCode(source: SourceKind): Int = when (source) {
	SourceKind.LOCATION -> sourceCollectionSettings.location.stableCode
	SourceKind.ACTIVITY -> sourceCollectionSettings.activity.stableCode
	SourceKind.STEPS -> sourceCollectionSettings.steps.stableCode
	SourceKind.PRESSURE -> sourceCollectionSettings.pressure.stableCode
	SourceKind.WIFI -> sourceCollectionSettings.wifi.stableCode
	SourceKind.CELL -> sourceCollectionSettings.cell.stableCode
}

private fun SourcePlan.directCaptureDemand(qosCode: Int): SourceDemand {
	val contract = SourceDemandContractFactory.forQos(
		source,
		qosCode,
		DirectSourceDemandPurpose.SESSION_CAPTURE,
	)
	return SourceDemand(
		source = source,
		maximumAgeMs = contract.maximumProviderItemAgeMs,
		desiredLatencyMs = contract.targetPlanningLatencyMs,
		quality = EvidenceQuality.ANY,
		reason = DemandReason.SESSION,
		acquisitionFloor = contract.floor,
		requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs,
		adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
	)
}
