package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.SourcePlanIdentity
import com.adsamcik.tracker.tracker.source.coordinator.TrackerServiceSourceSession.ActiveSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

internal fun TrackerServiceSourceSession.permitsPreparedSourceStart(
	request: SourceSessionStartRequest,
	delivery: AndroidStartDeliveryMetadata,
	startupGeneration: Long,
): Boolean = (trackingStartupGateProvider.get().permitsNewCollection ||
	(request.origin == SessionStartOrigin.MANUAL_FOREGROUND_START && delivery.isUserInitiated &&
		!delivery.isAmbient &&
		trackingStartupGateProvider.get().permitsPendingManualStart(delivery.commandGeneration))) &&
	trackingStartupGateProvider.get().isReadyGeneration(startupGeneration)

internal suspend fun TrackerServiceSourceSession.prepareSourceSessionPlan(
	request: SourceSessionStartRequest,
	delivery: AndroidStartDeliveryMetadata,
	planInputs: SourceSessionPlanInputs,
): SessionStartPreparationResult {
	val plan = buildPlan(request.rollout, request.captureMode, planInputs, requireEnabled = true)
	val inputsFingerprint = planInputs.planInputsFingerprint(
		rolloutRevision = request.rollout.revision,
		captureMode = request.captureMode,
		startOrigin = request.origin,
		foregroundCapabilityFlags = request.foregroundCapabilityFlags,
		controlDependencies = sourceSessionControlDependencies(request.origin),
	)
	val desiredIdentity = SourcePlanIdentity(
		generation = plan.revision,
		inputsFingerprint = inputsFingerprint,
		planFingerprint = sourcePlanFingerprint(inputsFingerprint, plan, sourcePlanCodec),
	)
	return when (val result = coordinator.prepareAndroidStart(
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
			controlDependencies = sourceSessionControlDependencies(request.origin),
			automaticTrigger = request.automaticTrigger,
			logicalTrackingId = request.logicalTrackingId,
			serviceRunId = request.serviceRunId,
			continuationAuthority = request.continuationAuthority,
			providerStepsContinuation = request.providerStepsContinuation,
		),
		delivery,
	)) {
		is SessionStartPreparationResult.Prepared -> result.copy(
			start = result.start.copy(desiredSourcePlanIdentity = desiredIdentity),
		)
		else -> result
	}
}

internal suspend fun TrackerServiceSourceSession.prepareClaimedSourceSession(
	claim: ClaimedPreparedSessionStart,
	planInputs: SourceSessionPlanInputs,
	persistedDescriptor: ActiveTrackingSessionDescriptor?,
): ActiveSession? {
	val claimedReference = SourceCallerReplayReference(
		requireNotNull(claim.intent.sourceCallerAuthorityReference),
	)
	require(
		persistedDescriptor == null ||
			(
				persistedDescriptor.logicalTrackingId == claim.logicalTrackingId &&
					persistedDescriptor.serviceRunId == claim.serviceRunId &&
					persistedDescriptor.sourceCallerAuthorityReference == claimedReference
				)
	) { "Prepared source session descriptor does not match its claimed start" }
	val rollout = trackingRolloutStateStore.load()
	if (rollout.revision !=
		database.sourceSessionDao().serviceRun(claim.serviceRunId)?.rolloutRevision
	) {
		return null
	}
	return ActiveSession(
		rollout = rollout,
		ownerToken = "prepared-start:${claim.token.value}",
		logicalTrackingId = claim.logicalTrackingId,
		serviceRunId = claim.serviceRunId,
		origin = claim.startOrigin,
		captureMode = captureModeFor(claim.isUserInitiated, claim.isAmbient),
		automaticTrigger = null,
		foregroundCapabilityFlags = claim.desiredForegroundCapabilityFlags,
		desiredInputs = planInputs,
		coordinatorStarted = true,
		sourceCallerAuthorityReference = claimedReference,
		appliedSourcePlanIdentity = persistedDescriptor?.appliedSourcePlanIdentity,
		desiredSourcePlanIdentity = persistedDescriptor?.desiredSourcePlanIdentity,
		lastAppliedInputsFingerprint =
			persistedDescriptor?.appliedSourcePlanIdentity?.inputsFingerprint,
		pendingRetirementSourceCallerAuthorityReference =
			persistedDescriptor?.pendingRetirementSourceCallerAuthorityReference,
		catalogReconfigurationDebt = persistedDescriptor?.catalogReconfigurationDebt,
	)
}

internal suspend fun TrackerServiceSourceSession.applyClaimedSourceSession(
	applyingSession: ActiveSession,
	claim: ClaimedPreparedSessionStart,
	commandGeneration: Long,
	planInputs: SourceSessionPlanInputs,
): SessionStartResult = try {
	coordinator.applyPreparedAndroidStart(
		token = claim.token,
		commandGeneration = commandGeneration,
		currentBootId = planInputs.clockDomainId,
		elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
		wallTimeMs = Time.nowMillis,
	)
} catch (error: CancellationException) {
	applyingSession.runtimeCleanupRequired = true
	throw error
}

internal suspend fun TrackerServiceSourceSession.restoreStartedSourceSession(
	applyingSession: ActiveSession,
	claim: ClaimedPreparedSessionStart,
	planInputs: SourceSessionPlanInputs,
	persistedDescriptor: ActiveTrackingSessionDescriptor?,
	result: SessionStartResult.Started,
): SessionStartResult? {
	val appliedReference = result.sourceCallerAuthorityReference
		?: applyingSession.sourceCallerAuthorityReference
	if (appliedReference != applyingSession.sourceCallerAuthorityReference) {
		applyingSession.runtimeCleanupRequired = true
		settingsStatusProvider.publishFailure(
			SOURCE_SESSION_SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED,
		)
		return SessionStartResult.InvalidIntent(
			SOURCE_SESSION_SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED,
		)
	}
	val preparedIdentity = persistedDescriptor?.desiredSourcePlanIdentity
	if (preparedIdentity != null) {
		val restorationFailure = preparedPlanRestorationFailure(
			applyingSession, claim.planRevision, planInputs, preparedIdentity,
		)
		if (restorationFailure != null) {
			applyingSession.runtimeCleanupRequired = true
			return SessionStartResult.Failed(
				result.logicalTrackingId,
				result.serviceRunId,
				result.applied,
				restorationFailure,
			)
		}
	}
	return null
}

internal fun TrackerServiceSourceSession.requiresPreparedSourceReconfiguration(
	applyingSession: ActiveSession,
	planInputs: SourceSessionPlanInputs,
	preparedIdentity: SourcePlanIdentity?,
): Boolean {
	if (preparedIdentity == null) {
		return false
	}
	val currentInputsFingerprint = applyingSession.inputsFingerprint(planInputs)
	return currentInputsFingerprint != preparedIdentity.inputsFingerprint ||
		applyingSession.appliedSourcePlanIdentity != preparedIdentity
}

internal suspend fun TrackerServiceSourceSession.publishPreparedSourceSession(
	applyingSession: ActiveSession,
	result: SessionStartResult.Started,
	requiresImmediateReconfiguration: Boolean,
) {
	if (!requiresImmediateReconfiguration &&
		!clearCatalogDebtSatisfiedBySuccessfulStart(applyingSession)
	) {
		settingsStatusProvider.publishFailure(
			SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
		)
	}
	preStartInputs = null
	settingsStatusProvider.publishApplied(result.applied)
}

internal fun TrackerServiceSourceSession.publishPreparedSourceRejection(
	applyingSession: ActiveSession,
	result: SessionStartResult,
) {
	if (result.requiresSourceSessionRuntimeCleanup) {
		applyingSession.runtimeCleanupRequired = true
		settingsStatusProvider.publishFailure(SOURCE_RUNTIME_CLEANUP_PENDING)
	} else {
		active = null
		settingsStatusProvider.publishFailure("SESSION_START_REJECTED")
	}
}

/** Validate and restore the already applied prepared plan; null means the original identity was persisted. */
@Suppress("ReturnCount") // Ordered revision, payload, applied-row and identity-store failures retain distinct codes.
private suspend fun TrackerServiceSourceSession.preparedPlanRestorationFailure(
	applyingSession: ActiveSession,
	planRevision: Long,
	planInputs: SourceSessionPlanInputs,
	preparedIdentity: SourcePlanIdentity,
): String? {
	if (preparedIdentity.generation != planRevision) {
		return SOURCE_SESSION_PREPARED_SOURCE_PLAN_IDENTITY_STALE
	}
	val planStore = RoomSourcePlanStore(database, sourcePlanCodec)
	val preparedRequestedPlan = planStore.load(planRevision)
	if (preparedRequestedPlan == null ||
		sourcePlanFingerprint(
			preparedIdentity.inputsFingerprint,
			preparedRequestedPlan,
			sourcePlanCodec,
		) != preparedIdentity.planFingerprint
	) {
		return SOURCE_SESSION_PREPARED_SOURCE_PLAN_IDENTITY_STALE
	}
	val appliedPlan = when (val read = planStore.loadApplied(planRevision)) {
		is AppliedPlanRead.Available -> read.plan
		AppliedPlanRead.Missing,
		is AppliedPlanRead.Invalid,
		-> return SOURCE_SESSION_APPLIED_SOURCE_PLAN_STATE_INVALID
	}
	if (!applyingSession.markAppliedPlan(
			inputs = planInputs,
			appliedPlan = appliedPlan,
			desiredIdentity = preparedIdentity,
		)
	) {
		return SOURCE_SESSION_APPLIED_SOURCE_PLAN_IDENTITY_STORE_UNAVAILABLE
	}
	return null
}

/** Reconcile current inputs only after the original prepared apply has released the session mutex. */
internal suspend fun TrackerServiceSourceSession.reconfigurePreparedStart(
	started: SessionStartResult.Started,
	planInputs: SourceSessionPlanInputs,
): SessionStartResult {
	return when (val reconfigured = reconfigure(planInputs)) {
		is SourceSessionReconfigureOutcome.Applied -> SessionStartResult.Started(
			logicalTrackingId = started.logicalTrackingId,
			serviceRunId = started.serviceRunId,
			applied = reconfigured.result.applied,
			planStatus = reconfigured.result.status,
			sourceCallerAuthorityReference =
				reconfigured.result.sourceCallerAuthorityReference,
		)
		is SourceSessionReconfigureOutcome.Started -> reconfigured.result
		is SourceSessionReconfigureOutcome.Retryable,
		is SourceSessionReconfigureOutcome.Deferred,
		SourceSessionReconfigureOutcome.Unchanged,
		-> started
		SourceSessionReconfigureOutcome.NotActive,
		is SourceSessionReconfigureOutcome.Rejected,
		-> {
			mutex.withLock {
				active?.runtimeCleanupRequired = true
			}
			SessionStartResult.Failed(
				logicalTrackingId = started.logicalTrackingId,
				serviceRunId = started.serviceRunId,
				applied = started.applied,
				code = SOURCE_SESSION_PREPARED_START_DESIRED_PLAN_REJECTED,
			)
		}
	}
}
