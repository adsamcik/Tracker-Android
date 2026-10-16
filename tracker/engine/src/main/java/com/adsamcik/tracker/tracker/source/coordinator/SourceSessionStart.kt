package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.tracking.SourceBalanceSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.shared.preferences.tracking.baselineBalanceLevel
import com.adsamcik.tracker.tracker.resilience.SourcePlanIdentity
import com.adsamcik.tracker.tracker.source.coordinator.TrackerServiceSourceSession.ActiveSession
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.DemandReason
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.EvidenceQuality
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

internal suspend fun TrackerServiceSourceSession.startUnderReadyGeneration(
	request: SourceSessionStartRequest,
): SourceSessionStartOutcome = mutex.withLock {
	require(request.rollout == request.ownership.rollout) { "Ownership must use the supplied rollout snapshot" }
	require(request.logicalTrackingId.isNotBlank())
	require(request.serviceRunId.isNotBlank())
	val planInputs = preStartInputs
		?.takeIf { pending -> pending.isAtLeastAsCurrentAsSourceSessionInputs(request.planInputs) }
		?: request.planInputs
	val ownership = TrackingSessionOwnership.resolve(
		request.rollout,
		planInputs.settings,
		request.captureMode,
	)
	preStartInputs = null
	val session = ActiveSession(
		rollout = request.rollout,
		ownerToken = request.ownerToken,
		logicalTrackingId = request.logicalTrackingId,
		serviceRunId = request.serviceRunId,
		origin = request.origin,
		captureMode = request.captureMode,
		automaticTrigger = request.automaticTrigger,
		foregroundCapabilityFlags = request.foregroundCapabilityFlags,
		desiredInputs = planInputs,
		coordinatorStarted = false,
		sourceCallerAuthorityReference = null,
	)
	active = session
	if (!ownership.eventCoordinatorRequired) {
		settingsStatusProvider.publishFailure(SOURCE_SESSION_ZERO_REACHABLE_CAPTURE_SOURCES)
		active = null
		settingsStatusProvider.publishInactive()
		return@withLock SourceSessionStartOutcome.Rejected(
			SessionStartResult.InvalidIntent(SOURCE_SESSION_ZERO_REACHABLE_CAPTURE_SOURCES),
		)
	}
	check(request.rollout.coordinatorMode == CoordinatorMode.EVENT) {
		"Event-owned sources require event coordinator mode"
	}
	// Attach cleanup ownership before the coordinator can perform its first provider side effect.
	// Cancellation or an exception must route later service teardown through durable retirement.
	session.coordinatorStarted = true
	val result = try {
		startCoordinator(session, planInputs)
	} catch (error: CancellationException) {
		session.runtimeCleanupRequired = true
		throw error
	}
	publishSourceSessionStartOutcome(session, result)
}

private fun TrackerServiceSourceSession.publishSourceSessionStartOutcome(
	session: ActiveSession,
	result: SessionStartResult,
): SourceSessionStartOutcome = if (result is SessionStartResult.Started) {
	session.sourceCallerAuthorityReference = result.sourceCallerAuthorityReference
	settingsStatusProvider.publishApplied(result.applied)
	SourceSessionStartOutcome.Started(result)
} else if (result.requiresSourceSessionRuntimeCleanup) {
	session.runtimeCleanupRequired = true
	settingsStatusProvider.publishFailure(SOURCE_RUNTIME_CLEANUP_PENDING)
	SourceSessionStartOutcome.Rejected(result)
} else {
	settingsStatusProvider.publishFailure("SESSION_START_REJECTED")
	active = null
	settingsStatusProvider.publishInactive()
	SourceSessionStartOutcome.Rejected(result)
}

internal fun TrackerServiceSourceSession.startupRejectedStart(): SourceSessionStartOutcome {
	settingsStatusProvider.publishFailure(SOURCE_SESSION_STARTUP_RECOVERY_NOT_READY)
	return SourceSessionStartOutcome.Rejected(
		SessionStartResult.InvalidIntent(SOURCE_SESSION_STARTUP_RECOVERY_NOT_READY),
	)
}

internal suspend fun TrackerServiceSourceSession.startCoordinator(
	session: ActiveSession,
	inputs: SourceSessionPlanInputs,
): SessionStartResult {
	val plan = buildPlan(session.rollout, session.captureMode, inputs, requireEnabled = true)
	val inputsFingerprint = session.inputsFingerprint(inputs)
	val desiredIdentity = SourcePlanIdentity(
		generation = plan.revision,
		inputsFingerprint = inputsFingerprint,
		planFingerprint = sourcePlanFingerprint(inputsFingerprint, plan, sourcePlanCodec),
	)
	session.desiredInputs = inputs
	session.desiredSourcePlanIdentity = desiredIdentity
	val result = coordinator.start(
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
			controlDependencies = sourceSessionControlDependencies(session.origin),
			automaticTrigger = session.automaticTrigger,
			logicalTrackingId = session.logicalTrackingId,
			serviceRunId = session.serviceRunId,
		),
	)
	if (result is SessionStartResult.Started) {
		return settleCoordinatorStart(session, inputs, plan.revision, desiredIdentity, result)
	}
	return result
}

/** Read and persist the accepted plan before considering its saved catalog debt satisfied. */
private suspend fun TrackerServiceSourceSession.settleCoordinatorStart(
	session: ActiveSession,
	inputs: SourceSessionPlanInputs,
	planRevision: Long,
	desiredIdentity: SourcePlanIdentity,
	result: SessionStartResult.Started,
): SessionStartResult {
	val appliedPlan = when (val read =
		RoomSourcePlanStore(database, sourcePlanCodec).loadApplied(planRevision)
	) {
		is AppliedPlanRead.Available -> read.plan
		AppliedPlanRead.Missing -> null
		is AppliedPlanRead.Invalid -> {
			session.runtimeCleanupRequired = true
			return SessionStartResult.Failed(
				result.logicalTrackingId,
				result.serviceRunId,
				result.applied,
				SOURCE_SESSION_APPLIED_SOURCE_PLAN_STATE_INVALID,
			)
		}
	}
	val failure = qualifyStartedAppliedPlan(session, inputs, appliedPlan, desiredIdentity, result)
	if (failure != null) {
		return failure
	}
	settleStartedCatalogDebt(session, appliedPlan, result)
	return result
}

private suspend fun TrackerServiceSourceSession.qualifyStartedAppliedPlan(
	session: ActiveSession,
	inputs: SourceSessionPlanInputs,
	appliedPlan: AcquisitionPlanRevision?,
	desiredIdentity: SourcePlanIdentity,
	result: SessionStartResult.Started,
): SessionStartResult? {
	if (appliedPlan != null &&
		!session.markAppliedPlan(inputs, appliedPlan, desiredIdentity)
	) {
		session.runtimeCleanupRequired = true
		return SessionStartResult.Failed(
			result.logicalTrackingId,
			result.serviceRunId,
			result.applied,
			SOURCE_SESSION_APPLIED_SOURCE_PLAN_IDENTITY_STORE_UNAVAILABLE,
		)
	}
	if (appliedPlan == null && result.applied.isNotEmpty()) {
		session.runtimeCleanupRequired = true
		return SessionStartResult.Failed(
			result.logicalTrackingId,
			result.serviceRunId,
			result.applied,
			SOURCE_SESSION_APPLIED_SOURCE_PLAN_STATE_INVALID,
		)
	}
	return null
}

private suspend fun TrackerServiceSourceSession.settleStartedCatalogDebt(
	session: ActiveSession,
	appliedPlan: AcquisitionPlanRevision?,
	result: SessionStartResult.Started,
) {
	val noAppliedSources = appliedPlan == null && result.applied.isEmpty()
	if ((session.appliedSourcePlanIdentity == session.desiredSourcePlanIdentity || noAppliedSources) &&
		!clearCatalogDebtSatisfiedBySuccessfulStart(
			session,
			allowMissingAppliedIdentity = noAppliedSources,
		)
	) {
		settingsStatusProvider.publishFailure(
			SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
		)
	}
}

internal suspend fun TrackerServiceSourceSession.buildPlan(
	rollout: TrackingRolloutState,
	captureMode: CaptureReachabilityMode,
	inputs: SourceSessionPlanInputs,
	requireEnabled: Boolean,
): AcquisitionPlanRevision {
	val revision = (database.sourcePlanStateDao().latestRevision()?.revision ?: 0L) + 1L
	val desired = planFactory.create(inputs.settings, revision, Time.nowMillis, inputs.environment)
	val captureDemands = desired.plans
		.filter { (source, plan) -> plan.enabled && rollout.isCaptureReachable(source, captureMode) }
		.map { (source, plan) ->
			plan.directCaptureDemand(
				inputs.settings.captureQosCode(source),
				inputs.settings.baselineBalanceLevel(TrackingSourceComponent.fromStableCode(source.stableCode))?.let { level ->
					if (source in inputs.environment.liveSources) {
						maxOf(level, SourceBalanceSettings.LIVE_LEVEL)
					} else {
						level
					}
				},
			)
		}
	val resolved = planResolver.resolve(
		desired,
		inputs.demands + captureDemands,
		inputs.resolutionContext,
	)
	val eventPlans = resolved.applicablePlans.filterKeys { source ->
		rollout.isCaptureReachable(source, captureMode)
	}
	settingsStatusProvider.publishResolved(
		inputs.settings,
		rollout,
		resolved,
		captureMode,
		eventPlans,
	)
	telemetry.recordPlanRevision()
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
		balanceProfileVersion = desired.balanceProfileVersion,
		sourcePreferences = desired.sourcePreferences.filterKeys { it in eventPlans },
	)
}

private fun TrackingParamsState.captureQosCode(source: SourceKind): Int = when (source) {
	SourceKind.LOCATION -> sourceCollectionSettings.location.stableCode
	SourceKind.ACTIVITY -> sourceCollectionSettings.activity.stableCode
	SourceKind.STEPS -> sourceCollectionSettings.steps.stableCode
	SourceKind.PRESSURE -> sourceCollectionSettings.pressure.stableCode
	SourceKind.WIFI -> sourceCollectionSettings.wifi.stableCode
	SourceKind.CELL -> sourceCollectionSettings.cell.stableCode
}

private fun SourcePlan.directCaptureDemand(qosCode: Int, balanceLevel: Int?): SourceDemand {
	val contract = if (balanceLevel != null) {
		SourceDemandContractFactory.forProfile(
			source,
			1,
			balanceLevel,
			DirectSourceDemandPurpose.SESSION_CAPTURE,
		)
	} else {
		SourceDemandContractFactory.forQos(
			source,
			qosCode,
			DirectSourceDemandPurpose.SESSION_CAPTURE,
		)
	}
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
