package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.failure.isTrackingOperationalFailure
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStore
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreFailureKind
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.resilience.CatalogReconfigurationDebt
import com.adsamcik.tracker.tracker.resilience.CatalogReconfigurationSourcePlan
import com.adsamcik.tracker.tracker.source.catalog.SourceAcquisitionPlanFactory
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.DemandReason
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.EvidenceQuality
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerAuthorityRetirementOutcome
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerAuthorityRetirementRetryReason
import java.time.ZoneId
import java.util.Base64
import javax.inject.Inject
import javax.inject.Provider
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
	data class Retryable(val result: SessionReconfigureResult.Retryable) :
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
class TrackerServiceSourceSession @Inject constructor(
	private val database: AppDatabase,
	private val coordinator: AuthoritativeSessionCoordinator,
	private val planFactory: SourceAcquisitionPlanFactory,
	private val planResolver: SourcePlanResolver,
	private val telemetry: TrackingCoordinatorTelemetry,
	private val settingsStatusProvider: TrackingSettingsStatusProvider,
	private val trackingStartupGateProvider: Provider<TrackingStartupGate>,
	private val trackingRolloutStateStore: RoomTrackingRolloutStateStore,
	private val activeTrackingSessionStore: ActiveTrackingSessionStore,
	private val sourcePlanCodec: SourcePlanCodec = SourcePlanCodec(),
) {
	private val mutex = Mutex()
	private var active: ActiveSession? = null
	private var preStartInputs: SourceSessionPlanInputs? = null

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
			?.takeIf { pending -> pending.isAtLeastAsCurrentAs(request.planInputs) }
			?: request.planInputs
		val ownership = TrackingSessionOwnership.resolve(
			request.rollout,
			planInputs.settings,
			request.captureMode,
		)
		if (ownership.eventCoordinatorRequired &&
			!trackingStartupGateProvider.get().isReadyGeneration(startupGeneration)
		) {
			return@withLock SessionStartPreparationResult.Rejected(STARTUP_RECOVERY_NOT_READY)
		}
		val plan = buildPlan(request.rollout, request.captureMode, planInputs, requireEnabled = true)
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
		persistedDescriptor: ActiveTrackingSessionDescriptor? = null,
	): SessionStartResult = mutex.withLock {
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
		if (rollout.revision != database.sourceSessionDao().serviceRun(claim.serviceRunId)?.rolloutRevision) {
			return@withLock SessionStartResult.InvalidRollout("PREPARED_START_ROLLOUT_STALE")
		}
		val applyingSession = ActiveSession(
			rollout = rollout,
			ownerToken = "prepared-start:${claim.token.value}",
			logicalTrackingId = claim.logicalTrackingId,
			serviceRunId = claim.serviceRunId,
			origin = claim.startOrigin,
			captureMode = captureModeFor(claim.isUserInitiated, claim.isAmbient),
			automaticTrigger = null,
			foregroundCapabilityFlags = claim.desiredForegroundCapabilityFlags,
			lastInputs = planInputs,
			coordinatorStarted = true,
			sourceCallerAuthorityReference = claimedReference,
			pendingRetirementSourceCallerAuthorityReference =
				persistedDescriptor?.pendingRetirementSourceCallerAuthorityReference,
			catalogReconfigurationDebt = persistedDescriptor?.catalogReconfigurationDebt,
		)
		// Attach cleanup ownership before the first provider side effect. If the Android service is
		// stopped and cancels this coroutine mid-apply, stop() must still fence a partially-started
		// runtime and terminalize the durable STARTING run.
		active = applyingSession
		val result = try {
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
		if (result is SessionStartResult.Started) {
			val appliedReference = result.sourceCallerAuthorityReference
				?: applyingSession.sourceCallerAuthorityReference
			if (appliedReference != applyingSession.sourceCallerAuthorityReference) {
				applyingSession.runtimeCleanupRequired = true
				settingsStatusProvider.publishFailure(
					SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED,
				)
				return@withLock SessionStartResult.InvalidIntent(
					SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED,
				)
			}
			preStartInputs = null
			settingsStatusProvider.publishApplied(result.applied)
		} else if (result.requiresRuntimeCleanup) {
			applyingSession.runtimeCleanupRequired = true
			settingsStatusProvider.publishFailure(SOURCE_RUNTIME_CLEANUP_PENDING)
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
		val planInputs = preStartInputs
			?.takeIf { pending -> pending.isAtLeastAsCurrentAs(request.planInputs) }
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
			lastInputs = planInputs,
			coordinatorStarted = false,
			sourceCallerAuthorityReference = null,
		)
		active = session
		if (!ownership.eventCoordinatorRequired) {
			settingsStatusProvider.publishFailure(ZERO_REACHABLE_CAPTURE_SOURCES)
			active = null
			settingsStatusProvider.publishInactive()
			return@withLock SourceSessionStartOutcome.Rejected(
				SessionStartResult.InvalidIntent(ZERO_REACHABLE_CAPTURE_SOURCES),
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
		if (result is SessionStartResult.Started) {
			session.sourceCallerAuthorityReference = result.sourceCallerAuthorityReference
			settingsStatusProvider.publishApplied(result.applied)
			SourceSessionStartOutcome.Started(result)
		} else if (result.requiresRuntimeCleanup) {
			session.runtimeCleanupRequired = true
			settingsStatusProvider.publishFailure(SOURCE_RUNTIME_CLEANUP_PENDING)
			SourceSessionStartOutcome.Rejected(result)
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
				preStartInputs = preStartInputs
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

	suspend fun retryPendingReconfiguration(): SourceSessionReconfigureOutcome? {
		val startupGate = trackingStartupGateProvider.get()
		if (startupGate.reconcile() !is TrackingStartupResult.Ready) return null
		val startupGeneration = startupGate.currentGeneration
		return startupGate.withReadyGenerationOperation(startupGeneration) {
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
		if (session.lastInputs == requestedInputs) {
			return@withLock SourceSessionReconfigureOutcome.Unchanged
		}
		if (!session.coordinatorStarted) {
			val ownership = TrackingSessionOwnership.resolve(
				session.rollout,
				requestedInputs.settings,
				session.captureMode,
			)
			if (!ownership.eventCoordinatorRequired) {
				session.lastInputs = requestedInputs
				settingsStatusProvider.publishActivePreview(
					requestedInputs.settings,
					session.rollout,
					requestedInputs,
					session.captureMode,
				)
				return@withLock SourceSessionReconfigureOutcome.Unchanged
			}
			session.lastInputs = requestedInputs
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
							SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED,
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
		val request = SessionReconfigureRequest(
			ownerToken = session.ownerToken,
			plan = plan,
			wallTimeMs = Time.nowMillis,
			elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
			clockDomainId = requestedInputs.clockDomainId,
			zoneId = requestedInputs.zoneId,
			foregroundCapabilityFlags = session.foregroundCapabilityFlags,
			controlDependencies = controlDependencies(session.origin),
		)
		val result = try {
			val firstAttempt = coordinator.reconfigure(request)
			if (firstAttempt is SessionReconfigureResult.Retryable) {
				if (!persistCatalogReconfigurationDebt(session, request, firstAttempt.sources)) {
					SessionReconfigureResult.Retryable(
						revision = plan.revision,
						failureCode = CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
						sources = firstAttempt.sources,
					)
				} else {
					coordinator.reconfigure(
						request.copy(catalogDebtPersistedSources = firstAttempt.sources),
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
			settingsStatusProvider.publishFailure(SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED)
			return@withLock SourceSessionReconfigureOutcome.Rejected(
				if (session.sourceCallerAuthorityReference == committedReference) {
					when (result) {
						is SessionReconfigureResult.Applied -> SessionReconfigureResult.Failed(
							revision = result.revision,
							applied = result.applied,
							failureCode = SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED,
							sourceCallerAuthorityReference = committedReference,
						)
						is SessionReconfigureResult.Failed -> result.copy(
							failureCode = SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED,
							sourceCallerAuthorityReference = committedReference,
						)
						else -> error("Committed reconfiguration reference requires a result")
					}
				} else {
					SessionReconfigureResult.InvalidState(
						SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED,
					)
				},
			)
		}
		if (result is SessionReconfigureResult.Applied) {
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
					CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
				)
				return@withLock SourceSessionReconfigureOutcome.Rejected(
					SessionReconfigureResult.InvalidState(
						CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
					),
				)
			}
			session.lastInputs = requestedInputs
			settingsStatusProvider.publishApplied(result.applied)
			SourceSessionReconfigureOutcome.Applied(result)
		} else if (result is SessionReconfigureResult.Retryable) {
			if (result.failureCode != CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE &&
				!persistCatalogReconfigurationDebt(session, request, result.sources)
			) {
				settingsStatusProvider.publishFailure(
					CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
				)
				return@withLock SourceSessionReconfigureOutcome.Retryable(
					SessionReconfigureResult.Retryable(
						revision = result.revision,
						failureCode = CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
						sources = result.sources,
					),
				)
			}
			settingsStatusProvider.publishFailure(result.failureCode)
			SourceSessionReconfigureOutcome.Retryable(result)
		} else {
			if (!clearCatalogReconfigurationDebt(session)) {
				settingsStatusProvider.publishFailure(
					CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
				)
				return@withLock SourceSessionReconfigureOutcome.Rejected(
					SessionReconfigureResult.InvalidState(
						CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
					),
				)
			}
			if (result.requiresRuntimeCleanup) {
				session.runtimeCleanupRequired = true
			}
			settingsStatusProvider.publishFailure("PLAN_RECONFIGURE_REJECTED")
			SourceSessionReconfigureOutcome.Rejected(result)
		}
	}

	private suspend fun retryDurableCatalogReconfiguration(): SourceSessionReconfigureOutcome? =
		mutex.withLock {
			val session = active ?: return@withLock null
			val stored = when (val result = activeTrackingSessionStore.read()) {
				is ActiveTrackingSessionStoreResult.Failure -> {
					settingsStatusProvider.publishFailure(
						CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
					)
					return@withLock null
				}
				is ActiveTrackingSessionStoreResult.Success -> result.descriptor
			} ?: return@withLock null
			if (stored.logicalTrackingId != session.logicalTrackingId ||
				stored.serviceRunId != session.serviceRunId
			) return@withLock null
			val debt = stored.catalogReconfigurationDebt ?: return@withLock null
			session.catalogReconfigurationDebt = debt
			val currentPolicyRevision = session.lastInputs.settings.sourcePolicyRevision
			if (debt.sourcePolicyRevision != currentPolicyRevision ||
				debt.clockDomainId != session.lastInputs.clockDomainId
			) {
				if (!replaceCatalogDebt(stored, null)) {
					settingsStatusProvider.publishFailure(
						CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
					)
					return@withLock null
				}
				session.catalogReconfigurationDebt = null
				return@withLock SourceSessionReconfigureOutcome.Unchanged
			}
			val revision = (database.sourcePlanStateDao().latestRevision()?.revision ?: 0L) + 1L
			val plan = debt.toPlan(revision) ?: run {
				if (replaceCatalogDebt(stored, null)) {
					session.catalogReconfigurationDebt = null
				}
				settingsStatusProvider.publishFailure(CATALOG_RECONFIGURATION_DEBT_INVALID)
				return@withLock SourceSessionReconfigureOutcome.Rejected(
					SessionReconfigureResult.InvalidState(CATALOG_RECONFIGURATION_DEBT_INVALID),
				)
			}
			val request = SessionReconfigureRequest(
				ownerToken = session.ownerToken,
				plan = plan,
				wallTimeMs = Time.nowMillis,
				elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
				clockDomainId = debt.clockDomainId,
				zoneId = debt.zoneId,
				foregroundCapabilityFlags = debt.foregroundCapabilityFlags,
				controlDependencies = sourceKindsFromMask(debt.controlDependencyMask),
				catalogDebtPersistedSources = sourceKindsFromMask(debt.deferredSourceMask),
			)
			val result = try {
				coordinator.reconfigure(request)
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
				return@withLock SourceSessionReconfigureOutcome.Rejected(
					SessionReconfigureResult.InvalidState(
						SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED,
					),
				)
			}
			when (result) {
				is SessionReconfigureResult.Applied -> {
					val debtUpdated = if (result.deferredCatalogSources.isEmpty()) {
						clearCatalogReconfigurationDebt(session)
					} else {
						updateCatalogReconfigurationDebtSources(
							session,
							result.deferredCatalogSources,
						)
					}
					if (!debtUpdated) {
						return@withLock SourceSessionReconfigureOutcome.Rejected(
							SessionReconfigureResult.InvalidState(
								CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
							),
						)
					}
					settingsStatusProvider.publishApplied(result.applied)
					SourceSessionReconfigureOutcome.Applied(result)
				}
				is SessionReconfigureResult.Retryable -> {
					if (!persistCatalogReconfigurationDebt(session, request, result.sources)) {
						return@withLock SourceSessionReconfigureOutcome.Retryable(
							result.copy(
								failureCode = CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
							),
						)
					}
					settingsStatusProvider.publishFailure(result.failureCode)
					SourceSessionReconfigureOutcome.Retryable(result)
				}
				else -> {
					if (!clearCatalogReconfigurationDebt(session)) {
						return@withLock SourceSessionReconfigureOutcome.Rejected(
							SessionReconfigureResult.InvalidState(
								CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
							),
						)
					}
					settingsStatusProvider.publishFailure("PLAN_RECONFIGURE_REJECTED")
					SourceSessionReconfigureOutcome.Rejected(result)
				}
			}
		}

	private suspend fun persistCatalogReconfigurationDebt(
		session: ActiveSession,
		request: SessionReconfigureRequest,
		deferredSources: Set<SourceKind>,
	): Boolean {
		val stored = currentStoredDescriptor(session) ?: return false
		val debt = request.toCatalogReconfigurationDebt(session, deferredSources)
		return replaceCatalogDebt(stored, debt).also { replaced ->
			if (replaced) session.catalogReconfigurationDebt = debt
		}
	}

	private suspend fun updateCatalogReconfigurationDebtSources(
		session: ActiveSession,
		deferredSources: Set<SourceKind>,
	): Boolean {
		val activeDebt = session.catalogReconfigurationDebt ?: return false
		val stored = currentStoredDescriptor(session) ?: return false
		val currentDebt = stored.catalogReconfigurationDebt
			?.takeIf { debt -> debt == activeDebt }
			?: return false
		val replacementDebt = currentDebt.copy(
			deferredSourceMask = deferredSources.toSourceMask(),
		)
		return replaceCatalogDebt(
			stored,
			replacementDebt,
		).also { replaced ->
			if (replaced) session.catalogReconfigurationDebt = replacementDebt
		}
	}

	private suspend fun clearCatalogReconfigurationDebt(session: ActiveSession): Boolean {
		if (session.catalogReconfigurationDebt == null) return true
		val stored = currentStoredDescriptor(session) ?: return false
		if (stored.catalogReconfigurationDebt == null) {
			session.catalogReconfigurationDebt = null
			return true
		}
		return replaceCatalogDebt(stored, null).also { replaced ->
			if (replaced) session.catalogReconfigurationDebt = null
		}
	}

	private suspend fun currentStoredDescriptor(
		session: ActiveSession,
	): ActiveTrackingSessionDescriptor? = when (val result = activeTrackingSessionStore.read()) {
		is ActiveTrackingSessionStoreResult.Failure -> null
		is ActiveTrackingSessionStoreResult.Success -> result.descriptor?.takeIf { stored ->
			stored.logicalTrackingId == session.logicalTrackingId &&
				stored.serviceRunId == session.serviceRunId
		}
	}

	private suspend fun replaceCatalogDebt(
		stored: ActiveTrackingSessionDescriptor,
		debt: CatalogReconfigurationDebt?,
	): Boolean {
		val replacement = stored.copy(catalogReconfigurationDebt = debt)
		if (replacement == stored) return true
		return when (val result = activeTrackingSessionStore.replaceExact(stored, replacement)) {
			is ActiveTrackingSessionStoreResult.Failure -> false
			is ActiveTrackingSessionStoreResult.Success -> result.descriptor == replacement
		}
	}

	private suspend fun propagateSourceCallerAuthority(
		session: ActiveSession,
		currentReference: SourceCallerReplayReference?,
		markCleanupOnFailure: Boolean = true,
	): Boolean {
		return try {
			val replacementReference = currentReference ?: return false
			val supersededReference = session.sourceCallerAuthorityReference
			val stored = when (val result = activeTrackingSessionStore.read()) {
				is ActiveTrackingSessionStoreResult.Failure -> return false
				is ActiveTrackingSessionStoreResult.Success -> result.descriptor
			}
			if (stored == null ||
				stored.logicalTrackingId != session.logicalTrackingId ||
				stored.serviceRunId != session.serviceRunId
			) {
				return false
			}
			if (stored.sourceCallerAuthorityReference != supersededReference &&
				stored.sourceCallerAuthorityReference != replacementReference
			) {
				return false
			}
			val predecessor = stored.pendingRetirementSourceCallerAuthorityReference
				?: supersededReference?.takeIf { it != replacementReference }
			val replacement = stored.copy(
				sourceCallerAuthorityReference = replacementReference,
				pendingRetirementSourceCallerAuthorityReference = predecessor,
			)
			val persisted = if (stored == replacement) {
				stored
			} else when (val result = activeTrackingSessionStore.replaceExact(stored, replacement)) {
				is ActiveTrackingSessionStoreResult.Failure -> return false
				is ActiveTrackingSessionStoreResult.Success -> result.descriptor
			}
			if (persisted != replacement) {
				return false
			}
			session.sourceCallerAuthorityReference = replacementReference
			session.pendingRetirementSourceCallerAuthorityReference = predecessor
			predecessor == null ||
				retireRecordedSupersededAuthority(
					session,
					replacementReference,
					predecessor,
					replacement,
				) == SourceCallerAuthorityRetirementOutcome.Completed
		} catch (cancelled: CancellationException) {
			if (markCleanupOnFailure) session.runtimeCleanupRequired = true
			throw cancelled
		} catch (failure: Exception) {
			if (!failure.isTrackingOperationalFailure()) throw failure
			if (markCleanupOnFailure) session.runtimeCleanupRequired = true
			false
		}
	}

	private suspend fun retireRecordedSupersededAuthority(
		session: ActiveSession,
		currentReference: SourceCallerReplayReference,
		supersededReference: SourceCallerReplayReference,
		recordedDescriptor: ActiveTrackingSessionDescriptor? = null,
	): SourceCallerAuthorityRetirementOutcome {
		val retirement = coordinator.retireSupersededSourceCallerAuthority(
			session.logicalTrackingId,
			currentReference,
			supersededReference,
			Time.nowMillis,
		)
		if (retirement != SourceCallerAuthorityRetirementOutcome.Completed) return retirement
		val stored = recordedDescriptor ?: when (val result = activeTrackingSessionStore.read()) {
			is ActiveTrackingSessionStoreResult.Failure -> return result.kind.toRetirementOutcome()
			is ActiveTrackingSessionStoreResult.Success -> result.descriptor
		}
		if (stored == null) return SourceCallerAuthorityRetirementOutcome.TerminalMissing
		if (stored.logicalTrackingId != session.logicalTrackingId ||
			stored.serviceRunId != session.serviceRunId ||
			stored.sourceCallerAuthorityReference != currentReference
		) return SourceCallerAuthorityRetirementOutcome.Retryable(
			SourceCallerAuthorityRetirementRetryReason.CURRENT_AUTHORITY_CHANGED,
		)
		if (stored.pendingRetirementSourceCallerAuthorityReference == null) {
			session.pendingRetirementSourceCallerAuthorityReference = null
			return SourceCallerAuthorityRetirementOutcome.Completed
		}
		if (stored.pendingRetirementSourceCallerAuthorityReference != supersededReference) {
			return SourceCallerAuthorityRetirementOutcome.TerminalAmbiguous
		}
		val cleared = stored.copy(
			pendingRetirementSourceCallerAuthorityReference = null,
		)
		val persisted = when (val result = activeTrackingSessionStore.replaceExact(stored, cleared)) {
			is ActiveTrackingSessionStoreResult.Failure -> return result.kind.toRetirementOutcome()
			is ActiveTrackingSessionStoreResult.Success -> result.descriptor
		}
		if (persisted != cleared) {
			return SourceCallerAuthorityRetirementOutcome.Retryable(
				SourceCallerAuthorityRetirementRetryReason.CURRENT_AUTHORITY_CHANGED,
			)
		}
		session.pendingRetirementSourceCallerAuthorityReference = null
		return SourceCallerAuthorityRetirementOutcome.Completed
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
		) return@withLock null
		session.sourceCallerAuthorityReference = stored.sourceCallerAuthorityReference
		session.pendingRetirementSourceCallerAuthorityReference =
			stored.pendingRetirementSourceCallerAuthorityReference
		stored
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
	): SourceSessionStopOutcome = mutex.withLock {
		val session = active ?: return@withLock finishInactiveStop()
		if (!session.coordinatorStarted) return@withLock finishUnstartedStop()
		session.pendingRetirementSourceCallerAuthorityReference?.let { predecessor ->
			val current = session.sourceCallerAuthorityReference
				?: return@withLock SourceSessionStopOutcome.Retryable(
					SourceSessionStopRetryCode.CLEANUP_PENDING,
				)
			when (retireRecordedSupersededAuthority(session, current, predecessor)) {
				SourceCallerAuthorityRetirementOutcome.Completed -> Unit
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

	private fun finishInactiveStop(): SourceSessionStopOutcome {
		preStartInputs = null
		settingsStatusProvider.publishInactive()
		return SourceSessionStopOutcome.NotActive
	}

	private suspend fun finishUnstartedStop(): SourceSessionStopOutcome {
		active?.let { session ->
			if (!clearCatalogReconfigurationDebt(session)) {
				return SourceSessionStopOutcome.Retryable(
					SourceSessionStopRetryCode.STORAGE_UNAVAILABLE,
				)
			}
		}
		active = null
		settingsStatusProvider.publishInactive()
		return SourceSessionStopOutcome.Stopped
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

	private suspend fun suspendForRestart(
		session: ActiveSession,
		reason: String,
	): SourceSessionStopOutcome {
		session.pendingSourceCallerAuthorityReference?.let { pending ->
			if (!propagateSourceCallerAuthority(
					session,
					pending,
					markCleanupOnFailure = false,
				)
			) {
				return SourceSessionStopOutcome.Retryable(
					SourceSessionStopRetryCode.STORAGE_UNAVAILABLE,
				)
			}
			session.pendingSourceCallerAuthorityReference = null
			if (session.coordinatorSuspended) {
				active = null
				return SourceSessionStopOutcome.Stopped
			}
		}
		val cutoff = currentStopCutoff(session.lastInputs.clockDomainId)
		val result = coordinator.suspendForRestart(
			SessionSuspendRequest(
				ownerToken = session.ownerToken,
				reason = reason,
				wallTimeMs = cutoff.wallTimeMs,
				elapsedRealtimeNanos = cutoff.elapsedRealtimeNanos,
				clockDomainId = cutoff.clockDomainId,
			),
		)
		val authorityReference = result.sourceCallerAuthorityReferenceOrNull()
		if (authorityReference != null) {
			session.pendingSourceCallerAuthorityReference = authorityReference
			session.coordinatorSuspended = result is SessionSuspendResult.Suspended
			if (!propagateSourceCallerAuthority(
					session,
					authorityReference,
					markCleanupOnFailure = false,
				)
			) {
				return SourceSessionStopOutcome.Retryable(
					SourceSessionStopRetryCode.STORAGE_UNAVAILABLE,
				)
			}
			session.pendingSourceCallerAuthorityReference = null
		}
		val outcome = result.toSourceSessionStopOutcome()
		if (outcome == SourceSessionStopOutcome.Stopped) {
			active = null
		}
		return outcome
	}

	private suspend fun stopCompletely(
		session: ActiveSession,
		reason: String,
		factualCutoff: SourceSessionStopCutoff?,
	): SourceSessionStopOutcome {
		val cutoff = selectStopCutoff(session.lastInputs.clockDomainId, factualCutoff)
		val outcome = coordinator.stop(
			SessionStopRequest(
				ownerToken = session.ownerToken,
				reason = reason,
				wallTimeMs = cutoff.wallTimeMs,
				elapsedRealtimeNanos = cutoff.elapsedRealtimeNanos,
				clockDomainId = cutoff.clockDomainId,
			),
		).toSourceSessionStopOutcome()
		if (outcome == SourceSessionStopOutcome.Stopped) {
			if (!clearCatalogReconfigurationDebt(session)) {
				return SourceSessionStopOutcome.Retryable(
					SourceSessionStopRetryCode.STORAGE_UNAVAILABLE,
				)
			}
			active = null
		}
		return outcome
	}

	private suspend fun startCoordinator(
		session: ActiveSession,
		inputs: SourceSessionPlanInputs,
	): SessionStartResult {
		val plan = buildPlan(session.rollout, session.captureMode, inputs, requireEnabled = true)
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
		captureMode: CaptureReachabilityMode,
		inputs: SourceSessionPlanInputs,
		requireEnabled: Boolean,
	): AcquisitionPlanRevision {
		val revision = (database.sourcePlanStateDao().latestRevision()?.revision ?: 0L) + 1L
		val desired = planFactory.create(inputs.settings, revision, Time.nowMillis, inputs.environment)
		val captureDemands = desired.plans
			.filter { (source, plan) -> plan.enabled && rollout.isCaptureReachable(source, captureMode) }
			.map { (source, plan) ->
				plan.directCaptureDemand(inputs.settings.captureQosCode(source))
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
		)
	}

	private fun SessionReconfigureRequest.toCatalogReconfigurationDebt(
		session: ActiveSession,
		deferredSources: Set<SourceKind>,
	): CatalogReconfigurationDebt {
		val policyRevision = requireNotNull(plan.sourcePolicyRevision)
		return CatalogReconfigurationDebt(
			logicalTrackingId = session.logicalTrackingId,
			serviceRunId = session.serviceRunId,
			sourcePolicyRevision = policyRevision,
			requestedPlanRevision = plan.revision,
			requestedPlanId = plan.planId,
			requestedPlanCreatedAtMs = plan.createdAtMs,
			requestedPlans = plan.plans.values
				.sortedBy { sourcePlan -> sourcePlan.source.stableCode }
				.map { sourcePlan ->
					val encoded = sourcePlanCodec.encode(sourcePlan)
					CatalogReconfigurationSourcePlan(
						sourceStableCode = sourcePlan.source.stableCode,
						payloadVersion = SourcePlanCodec.FORMAT_VERSION,
						payloadBase64 = Base64.getEncoder().encodeToString(encoded.bytes),
						payloadChecksum = encoded.checksum,
					)
				},
			deferredSourceMask = deferredSources.toSourceMask(),
			clockDomainId = clockDomainId,
			zoneId = zoneId,
			foregroundCapabilityFlags = foregroundCapabilityFlags,
			controlDependencyMask = controlDependencies.toSourceMask(),
		)
	}

	private data class ActiveSession(
		val rollout: TrackingRolloutState,
		val ownerToken: String,
		val logicalTrackingId: String,
		val serviceRunId: String,
		val origin: SessionStartOrigin,
		val captureMode: CaptureReachabilityMode,
		val automaticTrigger: AutomaticTrackingStartTrigger?,
		val foregroundCapabilityFlags: Long,
		var lastInputs: SourceSessionPlanInputs,
		var coordinatorStarted: Boolean,
		var sourceCallerAuthorityReference: SourceCallerReplayReference?,
		var pendingSourceCallerAuthorityReference: SourceCallerReplayReference? = null,
		var pendingRetirementSourceCallerAuthorityReference: SourceCallerReplayReference? = null,
		var catalogReconfigurationDebt: CatalogReconfigurationDebt? = null,
		var coordinatorSuspended: Boolean = false,
		var runtimeCleanupRequired: Boolean = false,
	)
}

private fun ActiveTrackingSessionStoreFailureKind.toRetirementOutcome(): SourceCallerAuthorityRetirementOutcome =
	when (this) {
	ActiveTrackingSessionStoreFailureKind.UNAVAILABLE ->
		SourceCallerAuthorityRetirementOutcome.Retryable(
			SourceCallerAuthorityRetirementRetryReason.STORAGE_UNAVAILABLE,
		)
	ActiveTrackingSessionStoreFailureKind.CORRUPT ->
		SourceCallerAuthorityRetirementOutcome.TerminalCorrupt
}

private fun currentStopCutoff(clockDomainId: String) = SourceSessionStopCutoff(
	wallTimeMs = Time.nowMillis,
	elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
	clockDomainId = clockDomainId,
)

private fun selectStopCutoff(
	clockDomainId: String,
	factualCutoff: SourceSessionStopCutoff?,
): SourceSessionStopCutoff = factualCutoff
	?.takeIf { it.clockDomainId == clockDomainId }
	?: currentStopCutoff(clockDomainId)

private fun SessionSuspendResult.toSourceSessionStopOutcome(): SourceSessionStopOutcome = when (this) {
	is SessionSuspendResult.Suspended,
	SessionSuspendResult.NoActiveSession,
	-> SourceSessionStopOutcome.Stopped
	is SessionSuspendResult.DrainPending -> SourceSessionStopOutcome.Retryable(
		SourceSessionStopRetryCode.DRAIN_PENDING,
	)
	is SessionSuspendResult.CleanupPending -> SourceSessionStopOutcome.Retryable(
		SourceSessionStopRetryCode.CLEANUP_PENDING,
	)
	is SessionSuspendResult.Retryable -> SourceSessionStopOutcome.Retryable(
		SourceSessionStopRetryCode.STORAGE_UNAVAILABLE,
	)
	is SessionSuspendResult.InvalidIntent -> error(
		"Event-source suspension intent rejected: ${code}",
	)
	SessionSuspendResult.Busy -> SourceSessionStopOutcome.Retryable(
		SourceSessionStopRetryCode.COORDINATOR_BUSY,
	)
}

private fun SessionSuspendResult.sourceCallerAuthorityReferenceOrNull():
	SourceCallerReplayReference? = when (this) {
	is SessionSuspendResult.Suspended -> sourceCallerAuthorityReference
	is SessionSuspendResult.CleanupPending -> sourceCallerAuthorityReference
	is SessionSuspendResult.DrainPending -> sourceCallerAuthorityReference
	is SessionSuspendResult.InvalidIntent,
	is SessionSuspendResult.Retryable,
	SessionSuspendResult.NoActiveSession,
	SessionSuspendResult.Busy,
	-> null
}

private fun SessionStopResult.toSourceSessionStopOutcome(): SourceSessionStopOutcome = when (this) {
	is SessionStopResult.Stopped,
	SessionStopResult.NoActiveSession,
	-> SourceSessionStopOutcome.Stopped
	is SessionStopResult.DrainPending -> SourceSessionStopOutcome.Retryable(
		SourceSessionStopRetryCode.DRAIN_PENDING,
	)
	is SessionStopResult.CleanupPending -> SourceSessionStopOutcome.Retryable(
		SourceSessionStopRetryCode.CLEANUP_PENDING,
	)
	is SessionStopResult.InvalidIntent -> error(
		"Event-source shutdown intent rejected: ${code}",
	)
	SessionStopResult.Busy -> SourceSessionStopOutcome.Retryable(
		SourceSessionStopRetryCode.COORDINATOR_BUSY,
	)
}

private val SessionStartResult.requiresRuntimeCleanup: Boolean
	get() = this is SessionStartResult.Failed && code == SOURCE_RUNTIME_CLEANUP_PENDING

private val SessionReconfigureResult.requiresRuntimeCleanup: Boolean
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

private fun CatalogReconfigurationDebt.toPlan(revision: Long): AcquisitionPlanRevision? {
	return try {
		val codec = SourcePlanCodec()
		val decoded = requestedPlans.associate { stored ->
			if (stored.payloadVersion != SourcePlanCodec.FORMAT_VERSION) return null
			val payload = Base64.getDecoder().decode(stored.payloadBase64)
			val sourcePlan = codec.decode(payload)
			val reencoded = codec.encode(sourcePlan)
			if (sourcePlan.source.stableCode != stored.sourceStableCode ||
				sourcePlan.revision != requestedPlanRevision ||
				reencoded.checksum != stored.payloadChecksum ||
				!reencoded.bytes.contentEquals(payload)
			) return null
			sourcePlan.source to sourcePlan.withRevision(revision)
		}
		val deferredSources = sourceKindsFromMask(deferredSourceMask)
		val controlDependencies = sourceKindsFromMask(controlDependencyMask)
		if (deferredSources.toSourceMask() != deferredSourceMask ||
			controlDependencies.toSourceMask() != controlDependencyMask ||
			deferredSources.any { source -> decoded[source]?.enabled != true }
		) return null
		AcquisitionPlanRevision(
			revision = revision,
			planId = "catalog-retry-$sourcePolicyRevision-$revision",
			createdAtMs = Time.nowMillis,
			plans = decoded,
			sourcePolicyRevision = sourcePolicyRevision,
		)
	} catch (_: Exception) {
		null
	}
}

private fun SourcePlan.withRevision(revision: Long): SourcePlan = when (this) {
	is com.adsamcik.tracker.tracker.source.model.LocationPlan -> copy(revision = revision)
	is com.adsamcik.tracker.tracker.source.model.ActivityPlan -> copy(revision = revision)
	is com.adsamcik.tracker.tracker.source.model.StepsPlan -> copy(revision = revision)
	is com.adsamcik.tracker.tracker.source.model.PressurePlan -> copy(revision = revision)
	is com.adsamcik.tracker.tracker.source.model.WifiPlan -> copy(revision = revision)
	is com.adsamcik.tracker.tracker.source.model.CellPlan -> copy(revision = revision)
}

private fun Set<SourceKind>.toSourceMask(): Long = fold(0L) { mask, source ->
	require(source.stableCode in 1..Long.SIZE_BITS)
	mask or (1L shl (source.stableCode - 1))
}

private fun sourceKindsFromMask(mask: Long): Set<SourceKind> =
	SourceKind.entries.filterTo(linkedSetOf()) { source ->
		source.stableCode in 1..Long.SIZE_BITS &&
			mask and (1L shl (source.stableCode - 1)) != 0L
	}

private const val STARTUP_RECOVERY_NOT_READY = "STARTUP_RECOVERY_NOT_READY"
private const val ZERO_REACHABLE_CAPTURE_SOURCES = "ZERO_REACHABLE_CAPTURE_SOURCES"
private const val SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED =
	"SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED"
private const val CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE =
	"CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE"
private const val CATALOG_RECONFIGURATION_DEBT_INVALID =
	"CATALOG_RECONFIGURATION_DEBT_INVALID"

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
