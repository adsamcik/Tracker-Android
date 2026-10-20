package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.shared.preferences.tracking.captureParticipates
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.failure.isTrackingOperationalFailure
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStore
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.resilience.CatalogReconfigurationDebt
import com.adsamcik.tracker.tracker.resilience.SourcePlanIdentity
import com.adsamcik.tracker.tracker.source.catalog.SourceAcquisitionPlanFactory
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerAuthorityRetirementOutcome
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Immutable source ownership selected once for an Android service run. */
data class TrackingSessionOwnership(
	val rollout: TrackingRolloutState,
	val configuredSources: Set<SourceKind>,
	val enabledEventSources: Set<SourceKind>,
	val containedSources: Set<SourceKind>,
) {
	val eventCoordinatorRequired: Boolean get() = enabledEventSources.isNotEmpty()
	val isPartiallyAccepted: Boolean get() =
		enabledEventSources.isNotEmpty() && containedSources.isNotEmpty()

	companion object {
		fun resolve(
			rollout: TrackingRolloutState,
			settings: TrackingParamsState,
			captureMode: CaptureReachabilityMode = CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
		): TrackingSessionOwnership {
			require(rollout.sourceOwners.values.none { it == SourceOwner.LEGACY }) {
				"Legacy source ownership is retired"
			}
			val configured = settings.enabledSemanticSources()
			val reachable = configured.filterTo(linkedSetOf()) { source ->
				rollout.isCaptureReachable(source, captureMode)
			}
			require(reachable.isEmpty() || rollout.coordinatorMode == CoordinatorMode.EVENT) {
				"Source-native acquisition requires the event coordinator"
			}
			return TrackingSessionOwnership(
				rollout = rollout,
				configuredSources = configured,
				enabledEventSources = reachable,
				containedSources = configured - reachable,
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
	val captureMode: CaptureReachabilityMode = origin.defaultCaptureMode(),
	val continuationAuthority: ServiceRunContinuationAuthority? = null,
	val providerStepsContinuation: com.adsamcik.tracker.tracker.api.ProviderStepsSessionContinuation? = null,
	val automaticTrigger: AutomaticTrackingStartTrigger? = null,
	val foregroundCapabilityFlags: Long,
	val planInputs: SourceSessionPlanInputs,
	val ownerToken: String,
	/** Current revision captured after original manual choices are rechecked, not at the tap. */
	val requiredSourcePolicyRevision: Long? = null,
)

sealed interface SourceSessionStartOutcome {
	data class Started(val result: SessionStartResult.Started) : SourceSessionStartOutcome
	data object NotRequired : SourceSessionStartOutcome
	data class Rejected(val result: SessionStartResult) : SourceSessionStartOutcome
}

sealed interface SourceSessionReconfigureOutcome {
	data class Applied(val result: SessionReconfigureResult.Applied) : SourceSessionReconfigureOutcome
	data class Started(val result: SessionStartResult.Started) : SourceSessionReconfigureOutcome
	/** The service retains a reconfiguration that may succeed after its source failure is resolved. */
	data class Retryable(val result: SessionReconfigureResult.Retryable) :
		SourceSessionReconfigureOutcome
	/** The service retains a reconfiguration awaiting source settlement or a lifecycle boundary. */
	data class Deferred(val result: SessionReconfigureResult.Deferred) :
		SourceSessionReconfigureOutcome
	data object NotActive : SourceSessionReconfigureOutcome
	data object Unchanged : SourceSessionReconfigureOutcome
	data class Rejected(val result: SessionReconfigureResult) : SourceSessionReconfigureOutcome
}

/** Service-facing result that separates expected teardown contention from contract failures. */
internal sealed interface SourceSessionStopOutcome {
	data object Stopped : SourceSessionStopOutcome
	data object NotActive : SourceSessionStopOutcome
	data class Retryable(val code: SourceSessionStopRetryCode) : SourceSessionStopOutcome
}

internal enum class SourceSessionStopRetryCode {
	COORDINATOR_BUSY,
	DRAIN_PENDING,
	CLEANUP_PENDING,
	STORAGE_UNAVAILABLE,
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
@Singleton
class TrackerServiceSourceSession @Inject constructor(
	internal val database: AppDatabase,
	internal val coordinator: AuthoritativeSessionCoordinator,
	internal val planFactory: SourceAcquisitionPlanFactory,
	internal val planResolver: SourcePlanResolver,
	internal val telemetry: TrackingCoordinatorTelemetry,
	internal val settingsStatusProvider: TrackingSettingsStatusProvider,
	internal val trackingStartupGateProvider: Provider<TrackingStartupGate>,
	internal val trackingRolloutStateStore: RoomTrackingRolloutStateStore,
	internal val activeTrackingSessionStore: ActiveTrackingSessionStore,
	internal val sourcePlanCodec: SourcePlanCodec = SourcePlanCodec(),
) {
	internal val mutex = Mutex()
	internal var active: ActiveSession? = null
	internal var preStartInputs: SourceSessionPlanInputs? = null

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
		val planInputs = preStartInputs
			?.takeIf { pending -> pending.isAtLeastAsCurrentAsSourceSessionInputs(request.planInputs) }
			?: request.planInputs
		if (request.requiredSourcePolicyRevision != null &&
			planInputs.settings.sourcePolicyRevision != request.requiredSourcePolicyRevision
		) {
			return@withLock SessionStartPreparationResult.Rejected("MANUAL_START_SELECTION_CHANGED")
		}
		val ownership = TrackingSessionOwnership.resolve(
			request.rollout,
			planInputs.settings,
			request.captureMode,
		)
		if (ownership.eventCoordinatorRequired && !permitsPreparedSourceStart(request, delivery, startupGeneration)) {
			return@withLock SessionStartPreparationResult.Rejected(SOURCE_SESSION_STARTUP_RECOVERY_NOT_READY)
		}
		prepareSourceSessionPlan(request, delivery, planInputs)
	}

	/** Applies an already foreground-accepted Room plan and attaches it for later reconfigure/stop. */
	suspend fun applyPreparedAndroidStart(
		claim: ClaimedPreparedSessionStart,
		commandGeneration: Long,
		planInputs: SourceSessionPlanInputs,
		persistedDescriptor: ActiveTrackingSessionDescriptor? = null,
	): SessionStartResult {
		var requiresImmediateReconfiguration = false
		val preparedResult = mutex.withLock {
			val applyingSession = prepareClaimedSourceSession(claim, planInputs, persistedDescriptor)
				?: return@withLock SessionStartResult.InvalidRollout("PREPARED_START_ROLLOUT_STALE")
			// Attach cleanup ownership before the first provider side effect. If the Android service is
			// stopped and cancels this coroutine mid-apply, stop() must still fence a partially-started
			// runtime and terminalize the durable STARTING run.
			active = applyingSession
			val result = applyClaimedSourceSession(applyingSession, claim, commandGeneration, planInputs)
			if (result is SessionStartResult.Started) {
				val restorationFailure = restoreStartedSourceSession(
					applyingSession, claim, planInputs, persistedDescriptor, result,
				)
				if (restorationFailure != null) {
					return@withLock restorationFailure
				}
				requiresImmediateReconfiguration = requiresPreparedSourceReconfiguration(
					applyingSession, planInputs, persistedDescriptor?.desiredSourcePlanIdentity,
				)
				publishPreparedSourceSession(applyingSession, result, requiresImmediateReconfiguration)
			} else {
				publishPreparedSourceRejection(applyingSession, result)
			}
			result
		}
		if (preparedResult !is SessionStartResult.Started || !requiresImmediateReconfiguration) {
			return preparedResult
		}
		return reconfigurePreparedStart(preparedResult, planInputs)
	}

	suspend fun start(request: SourceSessionStartRequest): SourceSessionStartOutcome {
		val startupGate = trackingStartupGateProvider.get()
		if (startupGate.reconcile() !is TrackingStartupResult.Ready || !startupGate.permitsNewCollection) {
			return startupRejectedStart()
		}
		val startupGeneration = startupGate.currentGeneration
		return startupGate.withReadyGenerationWork(startupGeneration) {
			startUnderReadyGeneration(request)
		} ?: startupRejectedStart()
	}

	suspend fun reconfigure(inputs: SourceSessionPlanInputs): SourceSessionReconfigureOutcome {
		val inactive = mutex.withLock {
			if (active == null) {
				preStartInputs = preStartInputs
					?.takeIf { current -> current.isNewerThan(inputs) }
					?: inputs
				SourceSessionReconfigureOutcome.NotActive
			} else {
				null
			}
		}
		if (inactive != null) {
			return inactive
		}

		val startupGate = trackingStartupGateProvider.get()
		if (startupGate.reconcile() !is TrackingStartupResult.Ready || !startupGate.permitsNewCollection) {
			return startupRejectedReconfigure()
		}
		val startupGeneration = startupGate.currentGeneration
		return startupGate.withReadyGenerationWork(startupGeneration) {
			reconfigureUnderReadyGeneration(inputs)
		} ?: startupRejectedReconfigure()
	}

	/**
	 * Retries the durable pending plan only under a ready startup generation that permits new collection.
	 */
	suspend fun retryPendingReconfiguration(): SourceSessionReconfigureOutcome? {
		val startupGate = trackingStartupGateProvider.get()
		if (startupGate.reconcile() !is TrackingStartupResult.Ready || !startupGate.permitsNewCollection) {
			return null
		}
		val startupGeneration = startupGate.currentGeneration
		return startupGate.withReadyGenerationWork(startupGeneration) {
			retryDurableCatalogReconfiguration()
		}
	}

	private suspend fun reconfigureUnderReadyGeneration(
		inputs: SourceSessionPlanInputs,
	): SourceSessionReconfigureOutcome = mutex.withLock {
		val session = active ?: run {
			preStartInputs = preStartInputs
				?.takeIf { current -> current.isNewerThan(inputs) }
				?: inputs
			return@withLock SourceSessionReconfigureOutcome.NotActive
		}
		val requestedInputs = inputs
		val requestedInputsFingerprint = session.inputsFingerprint(requestedInputs)
		val appliedIdentity = session.appliedSourcePlanIdentity
		val previousDesiredIdentity = session.desiredSourcePlanIdentity
		if (session.hasUnchangedAppliedInputs(
				requestedInputsFingerprint, appliedIdentity, previousDesiredIdentity,
			)
		) {
			return@withLock SourceSessionReconfigureOutcome.Unchanged
		}
		if (!session.coordinatorStarted) {
			val ownership = TrackingSessionOwnership.resolve(
				session.rollout,
				requestedInputs.settings,
				session.captureMode,
			)
			if (!ownership.eventCoordinatorRequired) {
				session.desiredInputs = requestedInputs
				settingsStatusProvider.publishActivePreview(
					requestedInputs.settings,
					session.rollout,
					requestedInputs,
					session.captureMode,
				)
				return@withLock SourceSessionReconfigureOutcome.Unchanged
			}
			val started = startCoordinator(session, requestedInputs)
			return@withLock if (started is SessionStartResult.Started) {
				session.coordinatorStarted = true
				if (!propagateSourceCallerAuthority(
						session,
						started.sourceCallerAuthorityReference,
					)
				) {
					session.runtimeCleanupRequired = true
					return@withLock SourceSessionReconfigureOutcome.Rejected(
						SessionReconfigureResult.InvalidState(
							SOURCE_SESSION_SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED,
						),
					)
				}
				settingsStatusProvider.publishApplied(started.applied)
				SourceSessionReconfigureOutcome.Started(started)
			} else {
				settingsStatusProvider.publishFailure("SESSION_START_REJECTED")
				SourceSessionReconfigureOutcome.Rejected(
					SessionReconfigureResult.InvalidState("START_REJECTED:$started"),
				)
			}
		}
		val plan = buildPlan(
			session.rollout,
			session.captureMode,
			requestedInputs,
			requireEnabled = false,
		)
		val desiredInputsFingerprint = session.inputsFingerprint(requestedInputs)
		val desiredFingerprint = session.fingerprint(requestedInputs, plan)
		val desiredGeneration = session.desiredSourcePlanIdentity?.generation
			?.takeIf {
				session.desiredSourcePlanIdentity?.planFingerprint == desiredFingerprint
			}
			?: maxOf(plan.revision, (session.desiredSourcePlanIdentity?.generation ?: 0L) + 1L)
		val desiredIdentity = SourcePlanIdentity(
			generation = desiredGeneration,
			inputsFingerprint = desiredInputsFingerprint,
			planFingerprint = desiredFingerprint,
		)
		session.desiredInputs = requestedInputs
		session.desiredSourcePlanIdentity = desiredIdentity
		val request = SessionReconfigureRequest(
			ownerToken = session.ownerToken,
			plan = plan,
			wallTimeMs = Time.nowMillis,
			elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
			clockDomainId = requestedInputs.clockDomainId,
			zoneId = requestedInputs.zoneId,
			foregroundCapabilityFlags = session.foregroundCapabilityFlags,
			controlDependencies = sourceSessionControlDependencies(session.origin),
			desiredPlanFingerprint = desiredFingerprint,
			desiredPlanGeneration = desiredGeneration,
		)
		val result = try {
			val firstAttempt = coordinator.reconfigure(request)
			if (firstAttempt is SessionReconfigureResult.Retryable) {
				if (!persistCatalogReconfigurationDebt(session, request, firstAttempt.sources)) {
					SessionReconfigureResult.Retryable(
						revision = plan.revision,
						failureCode = SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
						sources = firstAttempt.sources,
					)
				} else {
					coordinator.reconfigure(
						request.copy(
							catalogDebtPersistedSources = firstAttempt.sources,
							catalogDebtPersistedFingerprint = desiredFingerprint,
							catalogDebtPersistedGeneration = desiredGeneration,
						),
					)
				}
			} else {
				firstAttempt
			}
		} catch (error: CancellationException) {
			session.runtimeCleanupRequired = true
			throw error
		}
		val committedReference = when (result) {
			is SessionReconfigureResult.Applied -> result.sourceCallerAuthorityReference
			is SessionReconfigureResult.Failed -> result.sourceCallerAuthorityReference
			else -> null
		}
		if (committedReference != null &&
			!propagateSourceCallerAuthority(session, committedReference)
		) {
			session.runtimeCleanupRequired = true
			// The replacement is already applied and stored even if retiring its predecessor needs retry.
			if (result is SessionReconfigureResult.Applied &&
				session.sourceCallerAuthorityReference == committedReference &&
				!session.markAppliedReconfiguration(requestedInputs, desiredIdentity, result)
			) {
				settingsStatusProvider.publishFailure(SOURCE_SESSION_APPLIED_SOURCE_PLAN_STATE_INVALID)
				return@withLock SourceSessionReconfigureOutcome.Rejected(
					SessionReconfigureResult.InvalidState(SOURCE_SESSION_APPLIED_SOURCE_PLAN_STATE_INVALID),
				)
			}
			settingsStatusProvider.publishFailure(SOURCE_SESSION_SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED)
			return@withLock SourceSessionReconfigureOutcome.Rejected(
				if (session.sourceCallerAuthorityReference == committedReference) {
					when (result) {
						is SessionReconfigureResult.Applied -> SessionReconfigureResult.Failed(
							revision = result.revision,
							applied = result.applied,
							failureCode = SOURCE_SESSION_SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED,
							sourceCallerAuthorityReference = committedReference,
						)
						is SessionReconfigureResult.Failed -> result.copy(
							failureCode = SOURCE_SESSION_SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED,
							sourceCallerAuthorityReference = committedReference,
						)
						else -> error("Committed reconfiguration reference requires a result")
					}
				} else {
					SessionReconfigureResult.InvalidState(
						SOURCE_SESSION_SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED,
					)
				},
			)
		}
		if (result is SessionReconfigureResult.Applied) {
			if (!session.markAppliedReconfiguration(
					inputs = requestedInputs,
					desiredIdentity = desiredIdentity,
					result = result,
				)
			) {
				session.runtimeCleanupRequired = true
				settingsStatusProvider.publishFailure(
					SOURCE_SESSION_APPLIED_SOURCE_PLAN_STATE_INVALID,
				)
				return@withLock SourceSessionReconfigureOutcome.Rejected(
					SessionReconfigureResult.InvalidState(
						SOURCE_SESSION_APPLIED_SOURCE_PLAN_STATE_INVALID,
					),
				)
			}
			val debtUpdated = if (result.deferredCatalogSources.isEmpty()) {
				clearCatalogReconfigurationDebt(session)
			} else {
				updateCatalogReconfigurationDebtSources(
					session,
					result.deferredCatalogSources,
				)
			}
			if (!debtUpdated) {
				settingsStatusProvider.publishFailure(
					SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
				)
				return@withLock SourceSessionReconfigureOutcome.Rejected(
					SessionReconfigureResult.InvalidState(
						SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
					),
				)
			}
			settingsStatusProvider.publishApplied(result.applied)
			SourceSessionReconfigureOutcome.Applied(result)
		} else if (result is SessionReconfigureResult.Retryable) {
			if (result.failureCode != SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE &&
				!persistCatalogReconfigurationDebt(session, request, result.sources)
			) {
				settingsStatusProvider.publishFailure(
					SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
				)
				return@withLock SourceSessionReconfigureOutcome.Retryable(
					SessionReconfigureResult.Retryable(
						revision = result.revision,
						failureCode = SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
						sources = result.sources,
					),
				)
			}
			settingsStatusProvider.publishFailure(result.failureCode)
			SourceSessionReconfigureOutcome.Retryable(result)
		} else if (result is SessionReconfigureResult.Deferred) {
			settingsStatusProvider.publishFailure(result.failureCode)
			SourceSessionReconfigureOutcome.Deferred(result)
		} else {
			if (!clearCatalogReconfigurationDebt(session)) {
				settingsStatusProvider.publishFailure(
					SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
				)
				return@withLock SourceSessionReconfigureOutcome.Rejected(
					SessionReconfigureResult.InvalidState(
						SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
					),
				)
			}
			if (result.requiresSourceSessionRuntimeCleanup) {
				session.runtimeCleanupRequired = true
			}
			settingsStatusProvider.publishFailure("PLAN_RECONFIGURE_REJECTED")
			SourceSessionReconfigureOutcome.Rejected(result)
		}
	}

	internal suspend fun persistedDescriptorForActiveSession(
		expectedReference: SourceCallerReplayReference,
	): ActiveTrackingSessionDescriptor? = mutex.withLock {
		val session = active ?: return@withLock null
		val stored = when (val result = activeTrackingSessionStore.read()) {
			is ActiveTrackingSessionStoreResult.Failure -> return@withLock null
			is ActiveTrackingSessionStoreResult.Success -> result.descriptor
		} ?: return@withLock null
		if (
			stored.logicalTrackingId != session.logicalTrackingId ||
			stored.serviceRunId != session.serviceRunId ||
			stored.sourceCallerAuthorityReference != expectedReference
		) {
			return@withLock null
		}
		session.sourceCallerAuthorityReference = stored.sourceCallerAuthorityReference
		session.pendingRetirementSourceCallerAuthorityReference =
			stored.pendingRetirementSourceCallerAuthorityReference
		session.appliedSourcePlanIdentity = stored.appliedSourcePlanIdentity
		session.desiredSourcePlanIdentity = stored.desiredSourcePlanIdentity
		session.catalogReconfigurationDebt = stored.catalogReconfigurationDebt
		stored
	}

	internal suspend fun stop(
		reason: String,
		preserveLogicalSession: Boolean,
		factualCutoff: SourceSessionStopCutoff? = null,
	): SourceSessionStopOutcome = mutex.withLock {
		val session = active ?: return@withLock finishInactiveStop()
		if (!session.coordinatorStarted) {
			return@withLock finishUnstartedStop()
		}
		session.pendingRetirementSourceCallerAuthorityReference?.let { predecessor ->
			val current = session.sourceCallerAuthorityReference
				?: return@withLock SourceSessionStopOutcome.Retryable(
					SourceSessionStopRetryCode.CLEANUP_PENDING,
				)
			when (retireRecordedSupersededAuthority(session, current, predecessor)) {
				SourceCallerAuthorityRetirementOutcome.Completed,
				SourceCallerAuthorityRetirementOutcome.RetainedForSteps -> Unit
				is SourceCallerAuthorityRetirementOutcome.Retryable ->
					return@withLock SourceSessionStopOutcome.Retryable(
						SourceSessionStopRetryCode.CLEANUP_PENDING,
					)
				SourceCallerAuthorityRetirementOutcome.TerminalMissing,
				SourceCallerAuthorityRetirementOutcome.TerminalCorrupt,
				SourceCallerAuthorityRetirementOutcome.TerminalInvariant,
				SourceCallerAuthorityRetirementOutcome.TerminalAmbiguous,
				-> session.runtimeCleanupRequired = true
			}
		}

		val outcome = stopStartedSession(
			session = session,
			reason = reason,
			preserveLogicalSession = preserveLogicalSession,
			factualCutoff = factualCutoff,
		)
		if (outcome == SourceSessionStopOutcome.Stopped) {
			settingsStatusProvider.publishInactive()
		}
		outcome
	}

	internal fun ActiveSession.fingerprint(
		inputs: SourceSessionPlanInputs,
		plan: AcquisitionPlanRevision,
	): String = inputs.desiredPlanFingerprint(
		plan = plan,
		rolloutRevision = rollout.revision,
		captureMode = captureMode,
		startOrigin = origin,
		foregroundCapabilityFlags = foregroundCapabilityFlags,
		controlDependencies = sourceSessionControlDependencies(origin),
		codec = sourcePlanCodec,
	)

	internal fun ActiveSession.inputsFingerprint(
		inputs: SourceSessionPlanInputs,
	): String = inputs.planInputsFingerprint(
		rolloutRevision = rollout.revision,
		captureMode = captureMode,
		startOrigin = origin,
		foregroundCapabilityFlags = foregroundCapabilityFlags,
		controlDependencies = sourceSessionControlDependencies(origin),
	)

	internal suspend fun ActiveSession.markAppliedPlan(
		inputs: SourceSessionPlanInputs,
		appliedPlan: AcquisitionPlanRevision,
		desiredIdentity: SourcePlanIdentity,
	): Boolean {
		val appliedIdentity = SourcePlanIdentity(
			generation = desiredIdentity.generation,
			inputsFingerprint = desiredIdentity.inputsFingerprint,
			planFingerprint = sourcePlanFingerprint(
				desiredIdentity.inputsFingerprint,
				appliedPlan,
				sourcePlanCodec,
			),
		)
		lastAppliedInputsFingerprint = desiredIdentity.inputsFingerprint
		desiredInputs = inputs
		appliedSourcePlanIdentity = appliedIdentity
		desiredSourcePlanIdentity = desiredIdentity
		return persistSourcePlanIdentities(this)
	}

	internal suspend fun ActiveSession.markAppliedReconfiguration(
		inputs: SourceSessionPlanInputs,
		desiredIdentity: SourcePlanIdentity,
		result: SessionReconfigureResult.Applied,
	): Boolean {
		val appliedIdentity = when (
			val read = RoomSourcePlanStore(database, sourcePlanCodec).loadApplied(result.revision)
		) {
			is AppliedPlanRead.Available -> SourcePlanIdentity(
				generation = desiredIdentity.generation,
				inputsFingerprint = desiredIdentity.inputsFingerprint,
				planFingerprint = sourcePlanFingerprint(
					desiredIdentity.inputsFingerprint,
					read.plan,
					sourcePlanCodec,
				),
			)
			AppliedPlanRead.Missing -> {
				if (result.applied.isNotEmpty()) {
					return false
				}
				desiredIdentity
			}
			is AppliedPlanRead.Invalid -> return false
		}
		lastAppliedInputsFingerprint = desiredIdentity.inputsFingerprint
		desiredInputs = inputs
		desiredSourcePlanIdentity = desiredIdentity
		appliedSourcePlanIdentity = appliedIdentity
		return persistSourcePlanIdentities(this)
	}

	internal suspend fun propagateSourceCallerAuthority(
		session: ActiveSession,
		currentReference: SourceCallerReplayReference?,
		markCleanupOnFailure: Boolean = true,
	): Boolean {
		return try {
			val replacementReference = currentReference ?: return false
			val replacement = persistSourceCallerAuthority(session, replacementReference) ?: return false
			val predecessor = replacement.pendingRetirementSourceCallerAuthorityReference
			predecessor == null ||
				retireRecordedSupersededAuthority(
					session,
					replacementReference,
					predecessor,
					replacement,
				) in setOf(SourceCallerAuthorityRetirementOutcome.Completed,
					SourceCallerAuthorityRetirementOutcome.RetainedForSteps)
		} catch (cancelled: CancellationException) {
			if (markCleanupOnFailure) {
				session.runtimeCleanupRequired = true
			}
			throw cancelled
		} catch (failure: Exception) {
			if (!failure.isTrackingOperationalFailure()) {
				throw failure
			}
			if (markCleanupOnFailure) {
				session.runtimeCleanupRequired = true
			}
			false
		}
	}

	private suspend fun stopStartedSession(
		session: ActiveSession,
		reason: String,
		preserveLogicalSession: Boolean,
		factualCutoff: SourceSessionStopCutoff?,
	): SourceSessionStopOutcome = try {
		if (preserveLogicalSession && !session.runtimeCleanupRequired) {
			suspendForRestart(session, reason)
		} else {
			stopCompletely(session, reason, factualCutoff)
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (failure: Exception) {
		if (!failure.isTrackingOperationalFailure()) {
			throw failure
		}
		SourceSessionStopOutcome.Retryable(SourceSessionStopRetryCode.STORAGE_UNAVAILABLE)
	}

	internal data class ActiveSession(
		val rollout: TrackingRolloutState,
		val ownerToken: String,
		val logicalTrackingId: String,
		val serviceRunId: String,
		val origin: SessionStartOrigin,
		val captureMode: CaptureReachabilityMode,
		val automaticTrigger: AutomaticTrackingStartTrigger?,
		val foregroundCapabilityFlags: Long,
		var desiredInputs: SourceSessionPlanInputs,
		var coordinatorStarted: Boolean,
		var sourceCallerAuthorityReference: SourceCallerReplayReference?,
		var appliedSourcePlanIdentity: SourcePlanIdentity? = null,
		var desiredSourcePlanIdentity: SourcePlanIdentity? = appliedSourcePlanIdentity,
		/** Preparation/reconfiguration input identity that produced the live applied plan. */
		var lastAppliedInputsFingerprint: String? =
			appliedSourcePlanIdentity?.inputsFingerprint,
		var pendingSourceCallerAuthorityReference: SourceCallerReplayReference? = null,
		var pendingRetirementSourceCallerAuthorityReference: SourceCallerReplayReference? = null,
		var catalogReconfigurationDebt: CatalogReconfigurationDebt? = null,
		var coordinatorSuspended: Boolean = false,
		var runtimeCleanupRequired: Boolean = false,
	)
}

internal val SessionStartResult.requiresSourceSessionRuntimeCleanup: Boolean
	get() = this is SessionStartResult.Failed && code in setOf(
		SOURCE_RUNTIME_CLEANUP_PENDING,
		SOURCE_SESSION_APPLIED_SOURCE_PLAN_STATE_INVALID,
		SOURCE_SESSION_APPLIED_SOURCE_PLAN_IDENTITY_STORE_UNAVAILABLE,
		SOURCE_SESSION_PREPARED_START_DESIRED_PLAN_REJECTED,
		SOURCE_SESSION_PREPARED_SOURCE_PLAN_IDENTITY_STALE,
	)

internal val SessionReconfigureResult.requiresSourceSessionRuntimeCleanup: Boolean
	get() = this is SessionReconfigureResult.Failed &&
		failureCode == SOURCE_RUNTIME_CLEANUP_PENDING

internal fun captureModeFor(
	isUserInitiated: Boolean,
	isAmbient: Boolean,
): CaptureReachabilityMode = when {
	isAmbient -> CaptureReachabilityMode.AMBIENT
	isUserInitiated -> CaptureReachabilityMode.MANUAL_SESSION_CAPTURE
	else -> CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE
}

private fun SessionStartOrigin.defaultCaptureMode(): CaptureReachabilityMode = when (this) {
	SessionStartOrigin.AUTOMATIC_BACKGROUND_START -> CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE
	SessionStartOrigin.MANUAL_FOREGROUND_START,
	SessionStartOrigin.RECOVERY,
	SessionStartOrigin.POLICY_RECONCILIATION,
	-> CaptureReachabilityMode.MANUAL_SESSION_CAPTURE
}

internal fun Set<SourceKind>.toSourceSessionMask(): Long = fold(0L) { mask, source ->
	require(source.stableCode in 1..Long.SIZE_BITS)
	mask or (1L shl (source.stableCode - 1))
}

internal const val SOURCE_SESSION_STARTUP_RECOVERY_NOT_READY = "STARTUP_RECOVERY_NOT_READY"
internal const val SOURCE_SESSION_ZERO_REACHABLE_CAPTURE_SOURCES = "ZERO_REACHABLE_CAPTURE_SOURCES"
internal const val SOURCE_SESSION_SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED =
	"SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED"
internal const val SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE =
	"CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE"
internal const val SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_INVALID =
	"CATALOG_RECONFIGURATION_DEBT_INVALID"
internal const val SOURCE_SESSION_APPLIED_SOURCE_PLAN_STATE_INVALID =
	"APPLIED_SOURCE_PLAN_STATE_INVALID"
internal const val SOURCE_SESSION_APPLIED_SOURCE_PLAN_IDENTITY_STORE_UNAVAILABLE =
	"APPLIED_SOURCE_PLAN_IDENTITY_STORE_UNAVAILABLE"
internal const val SOURCE_SESSION_PREPARED_START_DESIRED_PLAN_REJECTED =
	"PREPARED_START_DESIRED_PLAN_REJECTED"
internal const val SOURCE_SESSION_PREPARED_SOURCE_PLAN_IDENTITY_STALE =
	"PREPARED_SOURCE_PLAN_IDENTITY_STALE"

internal fun sourceSessionControlDependencies(
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

internal fun SourceSessionPlanInputs.isAtLeastAsCurrentAsSourceSessionInputs(other: SourceSessionPlanInputs): Boolean {
	val candidateRevision = settings.sourcePolicyRevision ?: return false
	val otherRevision = other.settings.sourcePolicyRevision
	return otherRevision == null || candidateRevision >= otherRevision
}

private fun TrackingParamsState.enabledSemanticSources(): Set<SourceKind> =
    SourceKind.entries.filterTo(linkedSetOf()) { source ->
        captureParticipates(TrackingSourceComponent.fromStableCode(source.stableCode))
    }

private fun TrackerServiceSourceSession.ActiveSession.hasUnchangedAppliedInputs(
	requestedInputsFingerprint: String,
	appliedIdentity: SourcePlanIdentity?,
	previousDesiredIdentity: SourcePlanIdentity?,
): Boolean = lastAppliedInputsFingerprint == requestedInputsFingerprint &&
	catalogReconfigurationDebt == null && appliedIdentity != null && previousDesiredIdentity != null &&
	appliedIdentity == previousDesiredIdentity &&
	appliedIdentity.inputsFingerprint == requestedInputsFingerprint &&
	previousDesiredIdentity.inputsFingerprint == requestedInputsFingerprint
