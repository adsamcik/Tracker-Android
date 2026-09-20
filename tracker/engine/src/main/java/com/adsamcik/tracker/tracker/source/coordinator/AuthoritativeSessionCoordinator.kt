package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.StepsCountDomainRetirementEvidence
import com.adsamcik.tracker.shared.base.database.StepsCountDomainWriteResult
import com.adsamcik.tracker.shared.base.database.StepsTerminalCompletenessAuthentication
import com.adsamcik.tracker.shared.base.database.publishStepsCountDomainEvidenceRevisionAtWallTime
import com.adsamcik.tracker.shared.base.database.withMonotonicStepsCountDomainRevision
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.api.SourceCallerAcceptanceReceipt
import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerRejectionReason
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.TrackingStartFailureDisposition
import com.adsamcik.tracker.tracker.api.isRetryable
import com.adsamcik.tracker.tracker.failure.isTrackingOperationalFailure
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartContext
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.hasAuthoritativeConsentReference
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import com.adsamcik.tracker.tracker.source.catalog.SourceAvailabilityRequest
import com.adsamcik.tracker.tracker.source.catalog.SourceAvailabilityTier
import com.adsamcik.tracker.tracker.source.catalog.SourceCatalogAvailability
import com.adsamcik.tracker.tracker.source.catalog.SourceProviderAvailability
import com.adsamcik.tracker.tracker.source.catalog.SourceProviderAvailabilityEvidence
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomaticStartActionRepository
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomaticStartServiceValidation
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationDrainSignal
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEpochAuthority
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedFactProjectionLane
import com.adsamcik.tracker.tracker.source.cell.CellSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.wifi.WifiSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.runtime.ProviderCoverage
import com.adsamcik.tracker.tracker.source.runtime.ProviderFlushOutcome
import com.adsamcik.tracker.tracker.source.runtime.RegistrationRemovalOutcome
import com.adsamcik.tracker.tracker.source.runtime.SessionCutoff
import com.adsamcik.tracker.tracker.source.runtime.SourceApplyResult
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionFailureCode
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionHandoff
import com.adsamcik.tracker.tracker.source.runtime.SourceEventSink
import com.adsamcik.tracker.tracker.source.runtime.OwnedSourceShutdown
import com.adsamcik.tracker.tracker.source.runtime.SourceProviderKey
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeClaim
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeRegistry
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerAuthorityRetirementOutcome
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerAuthorityRetirementRetryReason
import com.adsamcik.tracker.tracker.source.runtime.SessionDemandMutation
import com.adsamcik.tracker.tracker.source.runtime.SessionSourceDemandDispatchRequest
import com.adsamcik.tracker.tracker.source.runtime.SessionSourceDemandDispatchResult
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerDemandDispatcher
import com.adsamcik.tracker.tracker.source.runtime.SourceStartResult
import com.adsamcik.tracker.tracker.source.runtime.SourceStopAck
import com.adsamcik.tracker.tracker.source.runtime.SourceStopStatus
import com.adsamcik.tracker.tracker.source.runtime.RuntimeCheckpointLifecycle
import com.adsamcik.tracker.tracker.source.runtime.SENSOR_RUNTIME_CHECKPOINT_VERSION
import com.adsamcik.tracker.tracker.source.runtime.decodeSensorRuntimeCheckpoint
import com.adsamcik.tracker.tracker.source.runtime.hasIncompleteTerminalRetirement
import com.adsamcik.tracker.tracker.source.runtime.hasTerminalStepsRetirement
import com.adsamcik.tracker.tracker.source.runtime.providerKeyOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

internal const val SOURCE_RUNTIME_CLEANUP_PENDING = "SOURCE_RUNTIME_CLEANUP_PENDING"
internal const val RUNTIME_CLEANUP_RETRY = "RUNTIME_CLEANUP_RETRY"
internal const val MAX_RUN_RETIREMENT_MANIFEST_SOURCES = 12
internal const val MAX_RUN_RETIREMENT_ACTIONS = 2_048
internal const val MAX_RUN_RETIREMENT_RECEIPTS = 512
internal const val MAX_RUN_RETIREMENT_INTENTS_PER_MANIFEST = 256
internal const val MAX_RUN_DRAIN_MANIFEST_REVISIONS = 2_048
private const val RUN_MANIFEST_REVISION_PAGE_SIZE = 64
private val LIVE_RUNTIME_ACTION_STATUSES = setOf(
	LifecycleActionStatus.START_ACCEPTED,
	LifecycleActionStatus.STOP_ACCEPTED,
)

/** Room-first lifecycle actor for logical tracking sessions and their service runs. */
@Singleton
class AuthoritativeSessionCoordinator @Inject internal constructor(
	private val database: AppDatabase,
	private val planStore: RoomSourcePlanStore,
	private val runtimes: SourceRuntimeRegistry,
	private val sinkFactory: DurableSourceEventSinkFactory,
	private val eventCoordinator: TrackingCoordinator,
	private val automaticStartActions: ActivityAutomaticStartActionRepository,
	private val activityAutomationDrainSignal: ActivityAutomationDrainSignal,
	private val activityAutomationEpochAuthority: ActivityAutomationEpochAuthority,
	private val bootClockDomainProvider: BootClockDomainProvider,
	private val clock: Clock,
	private val sourceCallerDemandDispatcher: SourceCallerDemandDispatcher,
	private val rolloutStore: TrackingRolloutStateStore = RoomTrackingRolloutStateStore(database),
	private val sourceBroker: SourceBroker = SourceBroker(database),
	private val executableLaneCatalog: ExecutableSourceLaneCatalog = ExecutableSourceLaneCatalog(),
	private val sourceProductDrainRouter: SourceProductDrainRouter = SourceProductDrainRouter { request ->
		SourceProductDrainResult.Inactive(request, "SOURCE_PRODUCT_DRAIN_ROUTER_UNAVAILABLE")
	},
) {
	private data class BoundServiceRunTransition(
		val session: LogicalTrackingSessionEntity,
		val serviceRunId: String,
		val expectedRunRevision: Long,
		val sourceCallerAuthorityReference: SourceCallerReplayReference? = null,
	)
	private data class FailedMaterializationShutdown(
		val closed: Boolean,
		val cleanupRequired: Boolean,
		val stopAck: SourceStopAck?,
	)
	private data class RuntimeRollbackOutcome(
		val applied: List<AppliedSourcePlan>,
		val cleanupRequired: Boolean,
	)
	private data class RunRetirementTarget(
		val source: SourceKind,
		val serviceRunId: String,
		val claims: List<RunRetirementClaim>,
		val actions: List<LifecycleDesiredActionEntity>,
		val captureBindings: List<SessionManifestSourceEntity>,
	)
	private data class RunRetirementClaim(
		val action: LifecycleDesiredActionEntity,
		val runtimeClaim: SourceRuntimeClaim,
		val provider: SourceProviderKey?,
		val manifestEnvelope: VerifiedSessionManifest,
	)
	private data class RunSourceRetirementOutcome(
		val source: SourceKind,
		val acknowledgements: List<SourceStopAck>,
		val retirementClaims: List<SourceDrainRetirementClaim>,
	) {
		init {
			require(retirementClaims.all { claim -> claim.source == source })
			require(retirementClaims.distinct().size == retirementClaims.size)
			require(retirementClaims.filter(SourceDrainRetirementClaim::cleanupOnly).none { claim ->
				acknowledgements.any { acknowledgement ->
					acknowledgement.sourceInstanceId.value == claim.sourceInstanceId &&
						acknowledgement.registrationGeneration == claim.registrationGeneration
				}
			})
		}
	}
	private data class RunRetirementOwnershipKey(
		val sourceKind: Int,
		val logicalTrackingId: String,
		val serviceRunId: String,
		val actionId: String,
		val attemptCount: Int,
		val leaseGeneration: Long,
		val sourceInstanceId: String,
		val registrationGeneration: Long,
	)
	private sealed interface RunRetirementTargetRead {
		data class Ready(
			val targets: List<RunRetirementTarget>,
			val drainAuthority: SourceProductDrainAuthority,
		) : RunRetirementTargetRead
		data class Blocked(val reason: String) : RunRetirementTargetRead
	}
	private sealed interface RunRetirementManifestRead {
		data class Ready(
			val envelopesByRevision: Map<Long, VerifiedSessionManifest>,
		) : RunRetirementManifestRead
		data class Blocked(val reason: String) : RunRetirementManifestRead
	}
	private sealed interface RunManifestRevisionRead {
		data class Ready(val revisions: List<Long>) : RunManifestRevisionRead
		data class Blocked(val reason: String) : RunManifestRevisionRead
	}
	private sealed interface RunRetirementReplay {
		data class Ready(
			val acknowledgements: List<SourceStopAck>,
			val cleanupOnlyProviders: Set<SourceProviderKey>,
			val unresolvedClaims: List<RunRetirementClaim>,
		) : RunRetirementReplay
		data object AuthenticationBlocked : RunRetirementReplay
	}
	private sealed interface CompletenessPersistenceResult {
		data object Stored : CompletenessPersistenceResult
		data object AuthenticationBlocked : CompletenessPersistenceResult
	}
	private class CompletenessAuthenticationBlockedException :
		IllegalStateException("Stored source completeness is unverifiable")
	private sealed interface RequestedStepsRetirementRecovery {
		data class Authenticated(
			val acknowledgement: SourceStopAck,
		) : RequestedStepsRetirementRecovery
		data object CleanupOnlyCompleted : RequestedStepsRetirementRecovery
		data object Absent : RequestedStepsRetirementRecovery
		data object Blocked : RequestedStepsRetirementRecovery
	}
	private sealed interface SettledSourceDrainBatch {
		data class Complete(val results: List<SourceProductDrainResult>) : SettledSourceDrainBatch
		data class Pending(
			val results: List<SourceProductDrainResult>,
			val failedSource: SourceKind?,
			val reason: String,
			val memberships: List<SourceDrainMembership> = emptyList(),
		) : SettledSourceDrainBatch
	}
	private data class VerifiedSessionManifest(
		val manifest: SessionManifestVersionEntity,
		val bindings: List<SessionManifestSourceEntity>,
	)

	/**
	 * Persists the exact provider-eligible source manifest while retaining every requested source in
	 * the desired plan for a typed applied outcome. Provider actions remain gated behind
	 * [LifecycleActionStatus.AWAITING_FOREGROUND] until APPLY or compensation.
	 */
	suspend fun prepareAndroidStart(
		request: SessionStartRequest,
		delivery: AndroidStartDeliveryMetadata,
	): SessionStartPreparationResult {
		if (request.plan.plans.values.none(SourcePlan::enabled)) {
			return SessionStartPreparationResult.Rejected("ZERO_CAPTURE_SOURCES")
		}
		validateStartIntent(request)?.let { return SessionStartPreparationResult.Rejected(it) }
		validateSourcePolicy(request.plan)?.let { return SessionStartPreparationResult.Rejected(it) }
		validateControlDependencies(request.plan.sourcePolicyRevision, request.controlDependencies)?.let {
			return SessionStartPreparationResult.Rejected(it)
		}
		val rollout = rolloutStore.load()
		rollout.validateEventPlan(
			request.rolloutRevision,
			request.plan,
			request.origin.toSessionMode().captureReachabilityModeOrNull(),
		)?.let {
			return SessionStartPreparationResult.Rejected(it)
		}
		val catalogDecisions = runtimes.catalogPlanDecisions(
			request.plan,
			request.origin.toSourceAvailabilityTier(),
		)
		catalogDecisions.failureIfNoAcceptedSource()?.let { failure ->
			return SessionStartPreparationResult.Rejected(failure.code, failure.disposition)
		}
		val ownerToken = preparedStartOwner(delivery.token)
		val lease = acquireLease(ownerToken)
			?: return SessionStartPreparationResult.Busy
		var keepLease = false
		return try {
			var active = database.sourceSessionDao().activeSession()
			if (active != null && request.continuationAuthority != null &&
				request.logicalTrackingId == active.logicalTrackingId
			) {
				val result = prepareRecoveryAndroidStart(
					active,
					request,
					delivery,
					lease,
					catalogDecisions,
				)
				keepLease = result is SessionStartPreparationResult.Prepared
				return result
			}
			if (active != null && isStalePriorSession(active, request)) {
				finalizeInterruptedSession(active, lease, request.wallTimeMs, "STALE_PRIOR_SESSION")
				active = database.sourceSessionDao().activeSession()
			}
			if (active != null) return SessionStartPreparationResult.AlreadyActive
			if (request.origin == SessionStartOrigin.RECOVERY || request.continuationAuthority != null) {
				return SessionStartPreparationResult.Rejected("RECOVERY_SESSION_MISSING")
			}
			val result = prepareFreshAndroidStart(request, delivery, lease, catalogDecisions)
			keepLease = result is SessionStartPreparationResult.Prepared
			result
		} finally {
			if (!keepLease) releaseLease(lease)
		}
	}

	private suspend fun prepareFreshAndroidStart(
		request: SessionStartRequest,
		delivery: AndroidStartDeliveryMetadata,
		lease: LifecycleLeaseToken,
		catalogDecisions: CatalogPlanDecisions,
	): SessionStartPreparationResult {
		val logicalTrackingId = request.logicalTrackingId ?: UUID.randomUUID().toString()
		if (request.automaticTrigger == null &&
			database.sourceSessionDao().session(logicalTrackingId) != null
		) return SessionStartPreparationResult.Rejected("LOGICAL_SESSION_ID_ALREADY_EXISTS")
		val serviceRunId = serviceRunIdFor(request)
		var failure: String? = null
		var failureDisposition = TrackingStartFailureDisposition.TERMINAL
		var alreadyAccepted = false
		var prepared: PreparedSessionStart? = null
		if (request.automaticTrigger != null) activityAutomationEpochAuthority.currentForValidation()
		try {
			database.withTransaction {
			requireLeaseInTransaction(lease)
			failure = validateSourcePolicyInTransaction(request.plan)
				?: validateRolloutInTransaction(
					request.rolloutRevision,
					request.plan,
					request.origin.toSessionMode(),
				)
			if (failure != null || database.sourceSessionDao().activeSession() != null) return@withTransaction
			val automaticAction = request.automaticTrigger?.let { trigger ->
				when (val validation = automaticStartActions.validateForLifecycleIntentTransaction(trigger)) {
					is ActivityAutomaticStartServiceValidation.Valid -> validation.action
					is ActivityAutomaticStartServiceValidation.LifecycleIntentAlreadyAccepted -> {
						alreadyAccepted = true
						return@withTransaction
					}
					is ActivityAutomaticStartServiceValidation.Rejected -> {
						failure = validation.reason
						database.activityAutomaticStartActionDao().markTerminal(
							trigger.triggerId,
							trigger.collectedDataEpoch,
							request.wallTimeMs,
							validation.reason,
						)
						return@withTransaction
					}
				}
			}
			if (database.sourceSessionDao().session(logicalTrackingId) != null) {
				failure = "LOGICAL_SESSION_ID_ALREADY_EXISTS"
				return@withTransaction
			}
			val rawDraft = buildManifestDraft(
				logicalTrackingId = logicalTrackingId,
				manifestRevision = 1L,
				intentRevision = 1L,
				request = request,
				sessionMode = request.origin.toSessionMode(),
				changeReason = "SESSION_START",
				lease = lease,
				serviceRunId = serviceRunId,
				acceptedCaptureSources = catalogDecisions.acceptedSources,
			)
			val draft = rawDraft.copy(
				actions = rawDraft.actions.map { action ->
					action.copy(status = LifecycleActionStatus.AWAITING_FOREGROUND.name)
				},
			)
			if (automaticAction != null) {
				val captureMask = request.plan.captureSourceMask()
				val controlConsentEpoch = draft.bindings.singleOrNull { binding ->
					binding.sourceKind == SourceKind.ACTIVITY.stableCode &&
						binding.purpose == SessionManifestPurpose.CONTROL.name
				}?.consentEpoch
				failure = when {
					automaticAction.sourcePolicyRevision != request.plan.sourcePolicyRevision ->
						"AUTOMATIC_START_SOURCE_POLICY_MISMATCH"
					automaticAction.intendedCaptureSourceMask != captureMask ->
						"AUTOMATIC_START_CAPTURE_MASK_MISMATCH"
					automaticAction.intendedForegroundServiceTypeMask != request.foregroundCapabilityFlags ->
						"AUTOMATIC_START_FGS_MASK_MISMATCH"
					automaticAction.controlConsentEpoch != controlConsentEpoch ->
						"AUTOMATIC_START_CONTROL_CONSENT_MISMATCH"
					else -> null
				}
				if (failure != null) {
					database.activityAutomaticStartActionDao().markTerminal(
						automaticAction.triggerId,
						automaticAction.collectedDataEpoch,
						request.wallTimeMs,
						requireNotNull(failure),
					)
					return@withTransaction
				}
			}
			planStore.persistDesired(request.plan, DesiredPlanStatus.DESIRED)
			val dao = database.sourceSessionDao()
			dao.insertSession(
				LogicalTrackingSessionEntity(
					logicalTrackingId = logicalTrackingId,
					state = SessionLifecycleState.STARTING.name,
					lifecycleRevision = 1L,
					desiredPlanRevision = request.plan.revision,
					rolloutRevision = request.rolloutRevision,
					startOrigin = request.origin.name,
					clockDomainId = request.clockDomainId,
					startedAtMs = request.wallTimeMs,
					startedElapsedNanos = request.elapsedRealtimeNanos,
					cutoffAtMs = null,
					cutoffElapsedNanos = null,
					completedAtMs = null,
					finalAdmissionOrdinal = null,
					failureCode = null,
					sessionMode = draft.manifest.sessionMode,
					currentManifestRevision = draft.manifest.manifestRevision,
					currentIntentRevision = draft.intent.intentRevision,
					currentServiceRunId = serviceRunId,
					lifecycleLeaseGeneration = lease.generation,
					lifecycleBootId = lease.bootId,
					automationEpoch = draft.intent.automationEpoch,
				),
			)
			dao.insertManifest(draft.manifest)
			dao.insertManifestSources(draft.bindings)
			val guardedDraft = dispatchSourceCallerDemands(
				draft = draft,
				sessionMode = request.origin.toSessionMode(),
				startOrigin = request.origin,
				mutation = SessionDemandMutation.STAGE_UNTIL_FOREGROUND,
				lease = lease,
				bootId = request.clockDomainId,
				elapsedRealtimeNanos = request.elapsedRealtimeNanos,
				wallTimeMs = request.wallTimeMs,
			)
			dao.insertLifecycleIntent(guardedDraft.intent)
			dao.insertLifecycleActions(guardedDraft.actions)
			dao.insertServiceRun(
				preparedServiceRun(
					serviceRunId,
					logicalTrackingId,
					request,
					delivery,
					guardedDraft,
					lease,
				),
			)
			if (automaticAction != null) {
				check(database.activityAutomaticStartActionDao().markLifecycleIntentAccepted(
					triggerId = automaticAction.triggerId,
					collectedDataEpoch = automaticAction.collectedDataEpoch,
					logicalTrackingId = logicalTrackingId,
					intentRevision = guardedDraft.intent.intentRevision,
					acceptedAtMs = request.wallTimeMs,
				) == 1) { "Automatic-start action changed before lifecycle-intent acceptance" }
			}
			prepared = PreparedSessionStart(
				delivery.token,
				logicalTrackingId,
				serviceRunId,
				guardedDraft.manifest.manifestRevision,
				guardedDraft.intent.intentRevision,
				SourceCallerReplayReference(
					requireNotNull(guardedDraft.intent.sourceCallerAuthorityReference),
				),
			)
			}
		} catch (rejection: SourceCallerDemandRejectedException) {
			failure = rejection.failureCode
			failureDisposition = rejection.disposition
		}
		failure?.let { return SessionStartPreparationResult.Rejected(it, failureDisposition) }
		if (alreadyAccepted) return SessionStartPreparationResult.AlreadyActive
		val result = prepared ?: return SessionStartPreparationResult.AlreadyActive
		if (request.automaticTrigger != null) activityAutomationDrainSignal.requestDrain()
		return SessionStartPreparationResult.Prepared(result)
	}

	private suspend fun prepareRecoveryAndroidStart(
		session: LogicalTrackingSessionEntity,
		request: SessionStartRequest,
		delivery: AndroidStartDeliveryMetadata,
		lease: LifecycleLeaseToken,
		catalogDecisions: CatalogPlanDecisions,
	): SessionStartPreparationResult {
		if (session.sessionMode != SessionMode.MANUAL.name ||
			session.state !in setOf(
				SessionLifecycleState.ACTIVE.name,
				SessionLifecycleState.RECONFIGURING.name,
			) ||
			session.currentManifestRevision == null || session.currentIntentRevision == null
		) {
			finalizeInterruptedSession(session, lease, request.wallTimeMs, "UNRECOVERABLE_SESSION")
			return SessionStartPreparationResult.Rejected("SESSION_NOT_RECOVERY_ELIGIBLE")
		}
		if (session.rolloutRevision != request.rolloutRevision) {
			return SessionStartPreparationResult.Rejected("SESSION_ROLLOUT_REVISION_MISMATCH")
		}
		val serviceRunId = serviceRunIdFor(request)
		var failure: String? = null
		var failureDisposition = TrackingStartFailureDisposition.TERMINAL
		var prepared: PreparedSessionStart? = null
		try {
			database.withTransaction {
			requireLeaseInTransaction(lease)
			failure = validateSourcePolicyInTransaction(request.plan)
				?: validateRolloutInTransaction(
					request.rolloutRevision,
					request.plan,
					request.origin.toSessionMode(),
				)
			if (failure != null) return@withTransaction
			val dao = database.sourceSessionDao()
			val current = requireNotNull(dao.session(session.logicalTrackingId))
			check(current.lifecycleRevision == session.lifecycleRevision) { "Session changed during recovery" }
			val continuation = requireNotNull(request.continuationAuthority)
			val previousRun = dao.serviceRun(continuation.previousServiceRunId)
			val incompleteRuns = dao.incompleteServiceRuns(session.logicalTrackingId)
			val previousRunIsActive = previousRun?.let { run ->
				run.state in setOf(
					SessionLifecycleState.ACTIVE.name,
					SessionLifecycleState.RECONFIGURING.name,
				) && run.completedAtMs == null &&
					current.currentServiceRunId == run.serviceRunId &&
					incompleteRuns.map { it.serviceRunId } == listOf(run.serviceRunId)
			} == true
			val previousRunWasSuspended = previousRun?.let { run ->
				run.state == SessionLifecycleState.FINALIZED.name && run.completedAtMs != null &&
					current.currentServiceRunId == null &&
					incompleteRuns.isEmpty()
			} == true
			failure = when {
				previousRun == null -> "CONTINUATION_RUN_MISSING"
				previousRun.logicalTrackingId != session.logicalTrackingId ->
					"CONTINUATION_LOGICAL_ID_MISMATCH"
				!previousRunIsActive && !previousRunWasSuspended ->
					"CONTINUATION_RUN_NOT_RECOVERABLE"
				previousRun.bootId != request.clockDomainId -> "CONTINUATION_RUN_OLD_BOOT"
				!previousRun.startIsUserInitiated || current.sessionMode != SessionMode.MANUAL.name ->
					"CONTINUATION_NOT_MANUAL"
				continuation.previousDeliveryToken != null &&
					previousRun.startDeliveryToken != continuation.previousDeliveryToken.value ->
					"CONTINUATION_DELIVERY_TOKEN_MISMATCH"
				continuation.previousCommandGeneration != null &&
					previousRun.startCommandGeneration != continuation.previousCommandGeneration ->
					"CONTINUATION_COMMAND_MISMATCH"
				else -> null
			}
			if (failure != null) return@withTransaction
			val manifestRevision = requireNotNull(current.currentManifestRevision) + 1L
			val intentRevision = requireNotNull(current.currentIntentRevision) + 1L
			val rawDraft = buildManifestDraft(
				logicalTrackingId = session.logicalTrackingId,
				manifestRevision = manifestRevision,
				intentRevision = intentRevision,
				request = request,
				sessionMode = SessionMode.MANUAL,
				changeReason = if (request.origin == SessionStartOrigin.RECOVERY) {
					"PROCESS_RECOVERY"
				} else {
					"USER_FOREGROUND_CONTINUATION"
				},
				lease = lease,
				serviceRunId = serviceRunId,
				acceptedCaptureSources = catalogDecisions.acceptedSources,
			)
			val draft = rawDraft.copy(
				actions = rawDraft.actions.map { action ->
					action.copy(status = LifecycleActionStatus.AWAITING_FOREGROUND.name)
				},
			)
			planStore.persistDesired(request.plan, DesiredPlanStatus.DESIRED)
			dao.serviceRun(continuation.previousServiceRunId)
				?.takeIf { previous -> previous.completedAtMs == null }
				?.let { previous ->
				dao.updateServiceRun(
					previous.copy(
						state = SessionLifecycleState.FINALIZED.name,
						completedAtMs = request.wallTimeMs,
						completionReason = if (request.origin == SessionStartOrigin.RECOVERY) {
							"PROCESS_DEATH_RECOVERY"
						} else {
							"USER_FOREGROUND_CONTINUATION"
						},
						runtimeAcknowledgement = LifecycleActionStatus.TERMINAL_FAILURE.name,
						runtimeFailureCode = "PROCESS_DEATH_RECOVERY",
						androidDeliveryState = AndroidStartDeliveryState.TERMINAL_FAILURE.name,
						androidDeliveryUpdatedAtMs = request.wallTimeMs,
						runRevision = previous.runRevision + 1L,
					),
				)
			}
			supersedePendingActions(session.logicalTrackingId, request.wallTimeMs, request.elapsedRealtimeNanos)
			dao.insertManifest(draft.manifest)
			dao.insertManifestSources(draft.bindings)
			dao.updateSession(
				current.copy(
					state = SessionLifecycleState.STARTING.name,
					lifecycleRevision = current.lifecycleRevision + 1L,
					desiredPlanRevision = request.plan.revision,
					cutoffAtMs = null,
					cutoffElapsedNanos = null,
					completedAtMs = null,
					finalAdmissionOrdinal = null,
					failureCode = null,
					currentManifestRevision = manifestRevision,
					currentIntentRevision = intentRevision,
					currentServiceRunId = serviceRunId,
					lifecycleLeaseGeneration = lease.generation,
					lifecycleBootId = lease.bootId,
				),
			)
			val guardedDraft = dispatchSourceCallerDemands(
				draft = draft,
				sessionMode = SessionMode.MANUAL,
				startOrigin = request.origin,
				mutation = SessionDemandMutation.STAGE_UNTIL_FOREGROUND,
				lease = lease,
				bootId = request.clockDomainId,
				elapsedRealtimeNanos = request.elapsedRealtimeNanos,
				wallTimeMs = request.wallTimeMs,
			)
			dao.insertLifecycleIntent(guardedDraft.intent)
			dao.insertLifecycleActions(guardedDraft.actions)
			dao.insertServiceRun(
				preparedServiceRun(
					serviceRunId,
					session.logicalTrackingId,
					request,
					delivery,
					guardedDraft,
					lease,
				),
			)
			prepared = PreparedSessionStart(
				delivery.token,
				session.logicalTrackingId,
				serviceRunId,
				manifestRevision,
				intentRevision,
				SourceCallerReplayReference(
					requireNotNull(guardedDraft.intent.sourceCallerAuthorityReference),
				),
			)
			}
		} catch (rejection: SourceCallerDemandRejectedException) {
			failure = rejection.failureCode
			failureDisposition = rejection.disposition
			if (rejection.disposition == TrackingStartFailureDisposition.TERMINAL) {
				finalizeInterruptedSession(
					session,
					lease,
					request.wallTimeMs,
					rejection.failureCode,
				)
			}
		}
		failure?.let { return SessionStartPreparationResult.Rejected(it, failureDisposition) }
		return prepared?.let(SessionStartPreparationResult::Prepared)
			?: SessionStartPreparationResult.Rejected("RECOVERY_PREPARE_FAILED")
	}

	private fun preparedServiceRun(
		serviceRunId: String,
		logicalTrackingId: String,
		request: SessionStartRequest,
		delivery: AndroidStartDeliveryMetadata,
		draft: PersistedLifecycleIntent,
		lease: LifecycleLeaseToken,
	) = SourceServiceRunEntity(
		serviceRunId = serviceRunId,
		logicalTrackingId = logicalTrackingId,
		state = SessionLifecycleState.STARTING.name,
		desiredPlanRevision = request.plan.revision,
		rolloutRevision = request.rolloutRevision,
		foregroundCapabilityFlags = request.foregroundCapabilityFlags,
		startedAtMs = request.wallTimeMs,
		startedElapsedNanos = request.elapsedRealtimeNanos,
		completedAtMs = null,
		completionReason = null,
		bootId = lease.bootId,
		leaseGeneration = lease.generation,
		startOrigin = request.origin.name,
		desiredForegroundCapabilityFlags = request.foregroundCapabilityFlags,
		appliedForegroundCapabilityFlags = null,
		runtimeAcknowledgement = LifecycleActionStatus.AWAITING_FOREGROUND.name,
		runtimeFailureCode = null,
		runRevision = 1L,
		startDeliveryToken = delivery.token.value,
		startCommandGeneration = delivery.commandGeneration,
		preparedManifestRevision = draft.manifest.manifestRevision,
		preparedIntentRevision = draft.intent.intentRevision,
		androidDeliveryState = AndroidStartDeliveryState.PREPARED.name,
		androidDeliveryUpdatedAtMs = request.wallTimeMs,
		startIsUserInitiated = delivery.isUserInitiated,
		startIsAmbient = delivery.isAmbient,
	)

	/**
	 * Terminalizes only the exact active manual run named by a failed continuation attempt. This is
	 * used when Android legality/capability prevents a passive redelivery from preparing its
	 * replacement; leaving the old process-owned run ACTIVE would create a ghost session.
	 */
	suspend fun finalizeUnrecoverableContinuation(
		logicalTrackingId: String,
		authority: ServiceRunContinuationAuthority,
		currentBootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		failureCode: String,
	): Boolean {
		require(currentBootId.isNotBlank())
		require(elapsedRealtimeNanos >= 0L)
		val lease = acquireLease("continuation-failure:${UUID.randomUUID()}") ?: return false
		return try {
			val eligible = database.withTransaction {
				requireLeaseInTransaction(lease)
				val dao = database.sourceSessionDao()
				val session = dao.session(logicalTrackingId) ?: return@withTransaction null
				val run = dao.serviceRun(authority.previousServiceRunId)
					?: return@withTransaction null
				if (session.state != SessionLifecycleState.ACTIVE.name ||
					session.sessionMode != SessionMode.MANUAL.name ||
					session.currentServiceRunId != run.serviceRunId ||
					run.logicalTrackingId != logicalTrackingId ||
					run.state != SessionLifecycleState.ACTIVE.name || run.completedAtMs != null ||
					!run.startIsUserInitiated ||
					authority.previousDeliveryToken?.value?.let { it != run.startDeliveryToken } == true ||
					authority.previousCommandGeneration?.let { it != run.startCommandGeneration } == true ||
					dao.incompleteServiceRuns(logicalTrackingId).map { it.serviceRunId } !=
						listOf(run.serviceRunId)
				) return@withTransaction null
				session
			} ?: return false
			finalizeInterruptedSession(eligible, lease, wallTimeMs, failureCode)
			database.sourceSessionDao().session(logicalTrackingId)?.let { terminal ->
				terminal.state == SessionLifecycleState.FINALIZED.name &&
					terminal.failureCode == failureCode
			} == true
		} finally {
			releaseLease(lease)
		}
	}

	suspend fun markAndroidStartEnqueued(
		token: PreparedTrackingStartToken,
		commandGeneration: Long,
		wallTimeMs: Long,
	): Boolean = database.withTransaction {
		val dao = database.sourceSessionDao()
		val run = dao.serviceRunByDeliveryToken(token.value) ?: return@withTransaction false
		if (run.startCommandGeneration != commandGeneration || run.completedAtMs != null) {
			return@withTransaction false
		}
		val session = dao.session(run.logicalTrackingId) ?: return@withTransaction false
		val manifestEnvelope = verifiedManifest(
			run.logicalTrackingId,
			run.preparedManifestRevision,
			run.serviceRunId,
		) ?: return@withTransaction false
		val manifest = manifestEnvelope.manifest
		if (session.currentServiceRunId != run.serviceRunId || manifest.serviceRunId != run.serviceRunId) {
			return@withTransaction false
		}
		when (run.androidDeliveryState) {
			AndroidStartDeliveryState.PREPARED.name -> check(
				dao.updateServiceRun(
					run.copy(
						androidDeliveryState = AndroidStartDeliveryState.ENQUEUED.name,
						androidDeliveryUpdatedAtMs = wallTimeMs,
						runRevision = run.runRevision + 1L,
					),
				) == 1,
			)
			AndroidStartDeliveryState.ENQUEUED.name,
			AndroidStartDeliveryState.DELIVERED.name,
			AndroidStartDeliveryState.FOREGROUND_ACCEPTED.name,
			-> Unit
			else -> return@withTransaction false
		}
		true
	}

	/** Claims only an exact current prepared start; no provider or foreground side effect occurs. */
	suspend fun claimAndroidStart(
		token: PreparedTrackingStartToken,
		commandGeneration: Long,
		currentBootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): PreparedSessionClaimResult {
		if (validateCanonicalBootId(currentBootId) != null) {
			return PreparedSessionClaimResult.Rejected("PREPARED_START_OLD_BOOT")
		}
		val initial = database.sourceSessionDao().serviceRunByDeliveryToken(token.value)
			?: return PreparedSessionClaimResult.Rejected("PREPARED_START_NOT_FOUND")
		if (initial.startCommandGeneration != commandGeneration) {
			return PreparedSessionClaimResult.Rejected("PREPARED_START_COMMAND_MISMATCH")
		}
		if (initial.bootId != currentBootId) {
			return PreparedSessionClaimResult.Rejected("PREPARED_START_OLD_BOOT")
		}
		if (initial.completedAtMs != null || initial.state in TERMINAL_STATES ||
			initial.androidDeliveryState == AndroidStartDeliveryState.TERMINAL_FAILURE.name
		) return PreparedSessionClaimResult.Rejected("PREPARED_START_TERMINAL")
		val lease = acquireLease(preparedStartOwner(token))
			?: return PreparedSessionClaimResult.Rejected("PREPARED_START_LEASE_UNAVAILABLE")
		if (lease.generation != initial.leaseGeneration) {
			terminalizeExpiredPreparedStart(
				token,
				commandGeneration,
				lease,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			releaseLease(lease)
			return PreparedSessionClaimResult.Rejected("PREPARED_START_LEASE_EXPIRED")
		}
		var rejection: String? = null
		var claimed: ClaimedPreparedSessionStart? = null
		database.withTransaction {
			requireLeaseInTransaction(lease)
			val dao = database.sourceSessionDao()
			val run = dao.serviceRunByDeliveryToken(token.value)
			if (run == null || run.startCommandGeneration != commandGeneration ||
				run.leaseGeneration != lease.generation || run.bootId != currentBootId ||
				run.completedAtMs != null || run.state in TERMINAL_STATES
			) {
				rejection = "PREPARED_START_STALE"
				return@withTransaction
			}
			if (run.androidDeliveryState !in setOf(
					AndroidStartDeliveryState.PREPARED.name,
					AndroidStartDeliveryState.ENQUEUED.name,
					AndroidStartDeliveryState.DELIVERED.name,
					AndroidStartDeliveryState.FOREGROUND_ACCEPTED.name,
				)
			) {
				rejection = "PREPARED_START_DELIVERY_TERMINAL"
				return@withTransaction
			}
			val session = dao.session(run.logicalTrackingId)
			val manifestEnvelope = session?.let {
				verifiedManifest(it.logicalTrackingId, run.preparedManifestRevision, run.serviceRunId)
			}
			val manifest = manifestEnvelope?.manifest
			val intent = session?.let {
				dao.lifecycleIntent(it.logicalTrackingId, run.preparedIntentRevision)
			}
			if (session == null || manifest == null || intent == null ||
				session.currentServiceRunId != run.serviceRunId ||
				session.currentManifestRevision != run.preparedManifestRevision ||
				session.currentIntentRevision != run.preparedIntentRevision ||
				session.lifecycleLeaseGeneration != lease.generation ||
				session.lifecycleBootId != currentBootId ||
				session.state != SessionLifecycleState.STARTING.name ||
				manifest.acquisitionPlanRevision != run.desiredPlanRevision ||
				manifest.serviceRunId != run.serviceRunId ||
				intent.manifestRevision != run.preparedManifestRevision ||
				intent.desiredState != LifecycleDesiredState.ACTIVE.name ||
				intent.sourceCallerAuthorityReference.isNullOrBlank()
			) {
				rejection = "PREPARED_START_ROOM_ENVELOPE_STALE"
				return@withTransaction
			}
			val triggerId = intent.triggerId
			var automaticTrigger: AutomaticTrackingStartTrigger? = null
			if (triggerId != null) {
				val action = database.activityAutomaticStartActionDao().action(triggerId)
				val currentEpoch = database.sourceEvidenceStateDao().get()?.collectedDataEpoch
				if (action == null || action.status != "LIFECYCLE_INTENT_ACCEPTED" ||
					action.acceptedLogicalTrackingId != session.logicalTrackingId ||
					action.acceptedIntentRevision != intent.intentRevision ||
					action.collectedDataEpoch != intent.triggerCollectedDataEpoch ||
					currentEpoch != action.collectedDataEpoch
				) {
					rejection = "PREPARED_AUTOMATIC_TRIGGER_STALE"
					return@withTransaction
				}
				automaticTrigger = AutomaticTrackingStartTrigger(
					triggerId = action.triggerId,
					kind = action.triggerKind,
					bootId = action.bootId,
					observedElapsedRealtimeNanos = action.observedElapsedRealtimeNanos,
					receivedElapsedRealtimeNanos = action.receivedElapsedRealtimeNanos,
					expiresElapsedRealtimeNanos = action.expiresElapsedRealtimeNanos,
					automationEpoch = action.automationEpoch,
					startContext = AutomaticTrackingStartContext.ACTIVITY_TRANSITION_CALLBACK,
					sourcePolicyRevision = action.sourcePolicyRevision,
					intendedCaptureSourceMask = action.intendedCaptureSourceMask,
					requestedCaptureSourceMask = action.requestedCaptureSourceMask,
					intendedForegroundServiceTypeMask = action.intendedForegroundServiceTypeMask,
					collectedDataEpoch = action.collectedDataEpoch,
				)
			}
			val bindings = requireNotNull(manifestEnvelope).bindings
			val captureSources = bindings.asSequence()
				.filter { it.purpose == SessionManifestPurpose.SESSION_CAPTURE.name }
				.mapNotNull { binding -> SourceKind.entries.firstOrNull { it.stableCode == binding.sourceKind } }
				.toSet()
			if (captureSources.isEmpty()) {
				rejection = "PREPARED_START_ZERO_CAPTURE_SOURCES"
				return@withTransaction
			}
			val updated = if (run.androidDeliveryState in setOf(
					AndroidStartDeliveryState.DELIVERED.name,
					AndroidStartDeliveryState.FOREGROUND_ACCEPTED.name,
				)
			) run else run.copy(
				androidDeliveryState = AndroidStartDeliveryState.DELIVERED.name,
				androidDeliveryUpdatedAtMs = wallTimeMs,
				runRevision = run.runRevision + 1L,
			).also { check(dao.updateServiceRun(it) == 1) }
			claimed = ClaimedPreparedSessionStart(
				token = token,
				logicalTrackingId = session.logicalTrackingId,
				serviceRunId = run.serviceRunId,
				manifestRevision = manifest.manifestRevision,
				intentRevision = intent.intentRevision,
				planRevision = manifest.acquisitionPlanRevision,
				sourcePolicyRevision = manifest.sourcePolicyRevision,
				startOrigin = SessionStartOrigin.valueOf(run.startOrigin),
				sessionMode = SessionMode.valueOf(session.sessionMode),
				acceptedSources = captureSources,
				desiredForegroundCapabilityFlags = run.desiredForegroundCapabilityFlags,
				intent = intent,
				automaticTrigger = automaticTrigger,
				isUserInitiated = run.startIsUserInitiated,
				isAmbient = run.startIsAmbient,
				alreadyForegroundAccepted = updated.androidDeliveryState ==
					AndroidStartDeliveryState.FOREGROUND_ACCEPTED.name,
			)
		}
		if (claimed == null) {
			releaseLease(lease)
			return PreparedSessionClaimResult.Rejected(rejection ?: "PREPARED_START_CLAIM_FAILED")
		}
		return PreparedSessionClaimResult.Claimed(requireNotNull(claimed))
	}

	/** Opens provider desired actions only after Android foreground promotion succeeded. */
	suspend fun markPreparedForegroundAccepted(
		token: PreparedTrackingStartToken,
		commandGeneration: Long,
		currentBootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Boolean {
		if (validateCanonicalBootId(currentBootId) != null) return false
		val run = database.sourceSessionDao().serviceRunByDeliveryToken(token.value) ?: return false
		if (run.startCommandGeneration != commandGeneration || run.bootId != currentBootId) return false
		val plan = planStore.load(run.desiredPlanRevision) ?: return false
		val acceptedAtPrepare = verifiedManifest(
			run.logicalTrackingId,
			run.preparedManifestRevision,
			run.serviceRunId,
		)?.bindings.orEmpty()
			.asSequence()
			.filter { binding -> binding.purpose == SessionManifestPurpose.SESSION_CAPTURE.name }
			.mapNotNullTo(linkedSetOf()) { binding ->
				SourceKind.entries.firstOrNull { source -> source.stableCode == binding.sourceKind }
			}
		val acceptedAtForeground = runtimes.catalogPlanDecisions(
			plan,
			SourceAvailabilityTier.SESSION_ALREADY_FOREGROUND,
		).constrainedToAcceptedSources(acceptedAtPrepare).acceptedSources
		val lease = acquireLease(preparedStartOwner(token)) ?: return false
		if (lease.generation != run.leaseGeneration) {
			terminalizeExpiredPreparedStart(
				token,
				commandGeneration,
				lease,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			releaseLease(lease)
			return false
		}
		return database.withTransaction {
			requireLeaseInTransaction(lease)
			val dao = database.sourceSessionDao()
			val current = dao.serviceRunByDeliveryToken(token.value) ?: return@withTransaction false
			if (current.startCommandGeneration != commandGeneration || current.completedAtMs != null ||
				current.state != SessionLifecycleState.STARTING.name ||
				current.androidDeliveryState !in setOf(
					AndroidStartDeliveryState.DELIVERED.name,
					AndroidStartDeliveryState.FOREGROUND_ACCEPTED.name,
				)
			) return@withTransaction false
			val session = dao.session(current.logicalTrackingId) ?: return@withTransaction false
			val manifestEnvelope = verifiedManifest(
				current.logicalTrackingId,
				current.preparedManifestRevision,
				current.serviceRunId,
			) ?: return@withTransaction false
			val manifest = manifestEnvelope.manifest
			val intent = dao.lifecycleIntent(
				current.logicalTrackingId,
				current.preparedIntentRevision,
			) ?: return@withTransaction false
			if (session.state != SessionLifecycleState.STARTING.name ||
				session.currentServiceRunId != current.serviceRunId ||
				session.currentManifestRevision != current.preparedManifestRevision ||
				session.currentIntentRevision != current.preparedIntentRevision ||
				session.lifecycleLeaseGeneration != current.leaseGeneration ||
				session.lifecycleBootId != current.bootId ||
				manifest.acquisitionPlanRevision != current.desiredPlanRevision ||
				manifest.serviceRunId != current.serviceRunId ||
				intent.manifestRevision != current.preparedManifestRevision ||
				intent.desiredState != LifecycleDesiredState.ACTIVE.name ||
				intent.sourceCallerAuthorityReference.isNullOrBlank()
			) return@withTransaction false
			if (current.androidDeliveryState != AndroidStartDeliveryState.FOREGROUND_ACCEPTED.name) {
				val sourceCallerReference = requireNotNull(
					intent.sourceCallerAuthorityReference,
				).let(::SourceCallerReplayReference)
				if (!sourceBroker.activatePreparedSessionDemandsInTransaction(
					logicalTrackingId = current.logicalTrackingId,
					serviceRunId = current.serviceRunId,
					manifestRevision = current.preparedManifestRevision,
					leaseGeneration = current.leaseGeneration,
					sourceCallerAuthorityReference = sourceCallerReference.value,
					bootId = currentBootId,
					elapsedRealtimeNanos = elapsedRealtimeNanos,
					wallTimeMs = wallTimeMs,
					currentAuthority = sourceCallerDemandDispatcher,
					acceptedSourceKinds = acceptedAtForeground
						.mapTo(linkedSetOf(), SourceKind::stableCode),
				)) return@withTransaction false
				check(dao.updateServiceRun(
					current.copy(
						androidDeliveryState = AndroidStartDeliveryState.FOREGROUND_ACCEPTED.name,
						androidDeliveryUpdatedAtMs = wallTimeMs,
						appliedForegroundCapabilityFlags = current.desiredForegroundCapabilityFlags,
						runtimeAcknowledgement = LifecycleActionStatus.PENDING.name,
						runRevision = current.runRevision + 1L,
					),
				) == 1)
			}
			dao.lifecycleActions(current.logicalTrackingId).filter { action ->
				action.serviceRunId == current.serviceRunId &&
					action.manifestRevision == current.preparedManifestRevision &&
					action.status == LifecycleActionStatus.AWAITING_FOREGROUND.name
			}.forEach { action ->
				check(dao.updateLifecycleAction(action.copy(status = LifecycleActionStatus.PENDING.name)) == 1)
			}
			true
		}
	}

	/** Applies only the exact persisted plan/manifest opened by foreground acknowledgement. */
	suspend fun applyPreparedAndroidStart(
		token: PreparedTrackingStartToken,
		commandGeneration: Long,
		currentBootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): SessionStartResult {
		if (validateCanonicalBootId(currentBootId) != null) {
			return SessionStartResult.InvalidIntent("PREPARED_START_OLD_BOOT")
		}
		val run = database.sourceSessionDao().serviceRunByDeliveryToken(token.value)
			?: return SessionStartResult.InvalidIntent("PREPARED_START_NOT_FOUND")
		if (run.startCommandGeneration != commandGeneration || run.bootId != currentBootId ||
			run.androidDeliveryState != AndroidStartDeliveryState.FOREGROUND_ACCEPTED.name
		) return SessionStartResult.InvalidIntent("PREPARED_START_NOT_FOREGROUND_ACCEPTED")
		val lease = acquireLease(preparedStartOwner(token))
			?: return SessionStartResult.Busy
		if (lease.generation != run.leaseGeneration) {
			terminalizeExpiredPreparedStart(
				token,
				commandGeneration,
				lease,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			releaseLease(lease)
			return SessionStartResult.InvalidIntent("PREPARED_START_LEASE_EXPIRED")
		}
		return try {
			val plan = planStore.load(run.desiredPlanRevision)
				?: return SessionStartResult.InvalidIntent("PREPARED_START_PLAN_MISSING")
			val persisted = database.withTransaction {
				val dao = database.sourceSessionDao()
				val manifestEnvelope = verifiedManifest(
					run.logicalTrackingId,
					run.preparedManifestRevision,
					run.serviceRunId,
				) ?: return@withTransaction null
				val intent = dao.lifecycleIntent(run.logicalTrackingId, run.preparedIntentRevision)
					?: return@withTransaction null
				val session = dao.session(run.logicalTrackingId) ?: return@withTransaction null
				if (session.currentServiceRunId != run.serviceRunId ||
					session.currentManifestRevision != run.preparedManifestRevision ||
					session.currentIntentRevision != run.preparedIntentRevision
				) return@withTransaction null
				PersistedLifecycleIntent(
					manifest = manifestEnvelope.manifest,
					bindings = manifestEnvelope.bindings,
					intent = intent,
					actions = dao.lifecycleActions(run.logicalTrackingId).filter { action ->
						action.serviceRunId == run.serviceRunId &&
							action.manifestRevision == run.preparedManifestRevision
					},
					demands = database.sourceBrokerDao().currentDemands("session:${run.logicalTrackingId}"),
					foregroundCapabilityFlags = run.desiredForegroundCapabilityFlags,
				)
			} ?: return SessionStartResult.InvalidIntent("PREPARED_START_MANIFEST_INTEGRITY_FAILED")
			validateSourcePolicy(plan)?.let { return SessionStartResult.InvalidPolicy(it) }
			val catalogDecisions = runtimes.catalogPlanDecisions(
				plan,
				SourceAvailabilityTier.SESSION_ALREADY_FOREGROUND,
			).constrainedToAcceptedSources(persisted.activeCaptureSources())
			val blockedSources = catalogDecisions.bySource
				.filterValues { decision -> decision is CatalogSourcePlanDecision.Blocked }
				.keys
				.mapTo(linkedSetOf(), SourceKind::stableCode)
			val demandsReconciled = database.withTransaction {
				requireLeaseInTransaction(lease)
				sourceBroker.retirePreparedSessionCaptureDemandsInTransaction(
					logicalTrackingId = run.logicalTrackingId,
					serviceRunId = run.serviceRunId,
					manifestRevision = run.preparedManifestRevision,
					leaseGeneration = run.leaseGeneration,
					sourceKinds = blockedSources,
					bootId = currentBootId,
					elapsedRealtimeNanos = elapsedRealtimeNanos,
					wallTimeMs = wallTimeMs,
				)
			}
			if (!demandsReconciled) {
				return SessionStartResult.InvalidIntent(
					"PREPARED_START_DEMAND_RECONCILIATION_FAILED",
				)
			}
			planStore.updateStatus(plan.revision, DesiredPlanStatus.APPLYING)
			val sink = sinkFactory.forSession(run.logicalTrackingId, run.serviceRunId)
			val executions = reconcileStartActions(
				plan,
				persisted,
				sink,
				lease,
				wallTimeMs,
				elapsedRealtimeNanos,
				catalogDecisions,
			)
			var applied = executions.map(SourceActionExecution::applied)
			if (executions.any { execution -> execution.status == LifecycleActionStatus.CLEANUP_REQUIRED }) {
				planStore.updateStatus(plan.revision, DesiredPlanStatus.APPLYING)
				return SessionStartResult.Failed(
					run.logicalTrackingId,
					run.serviceRunId,
					applied,
					SOURCE_RUNTIME_CLEANUP_PENDING,
				)
			}
			val finalPolicyFailure = validateSourcePolicy(plan)
			if (finalPolicyFailure != null) {
				val rollback = rollbackStalePolicySources(plan, executions, elapsedRealtimeNanos, wallTimeMs)
				applied = rollback.applied
				if (rollback.cleanupRequired) {
					planStore.updateStatus(plan.revision, DesiredPlanStatus.APPLYING)
					return SessionStartResult.Failed(
						run.logicalTrackingId,
						run.serviceRunId,
						applied,
						SOURCE_RUNTIME_CLEANUP_PENDING,
					)
				}
			}
			val effectiveStatus = applied.desiredStatus()
			planStore.updateStatus(plan.revision, effectiveStatus)
			val hasRunningSource = applied.any { state ->
				plan.plans[state.source]?.enabled == true &&
					state.status in setOf(SourceApplyStatus.APPLIED, SourceApplyStatus.DEGRADED)
			}
			if (!hasRunningSource) {
				val failureCode = catalogDecisions.failureIfNoAcceptedSource()?.code
					?: "NO_SOURCE_STARTED"
				markStartFailed(run.logicalTrackingId, run.serviceRunId, wallTimeMs, failureCode, lease)
				SessionStartResult.Failed(run.logicalTrackingId, run.serviceRunId, applied, failureCode)
			} else {
				val failure = markRunningIfPolicyCurrent(
					run.logicalTrackingId,
					run.serviceRunId,
					plan,
					run.preparedManifestRevision,
					lease,
					run.desiredForegroundCapabilityFlags,
				)
				if (failure == null) {
					SessionStartResult.Started(
						run.logicalTrackingId,
						run.serviceRunId,
						applied,
						effectiveStatus,
						SourceCallerReplayReference(
							requireNotNull(persisted.intent.sourceCallerAuthorityReference),
						),
					)
				} else {
					val rollback = rollbackStalePolicySources(plan, executions, elapsedRealtimeNanos, wallTimeMs)
					applied = rollback.applied
					if (rollback.cleanupRequired) {
						planStore.updateStatus(plan.revision, DesiredPlanStatus.APPLYING)
						return SessionStartResult.Failed(
							run.logicalTrackingId,
							run.serviceRunId,
							applied,
							SOURCE_RUNTIME_CLEANUP_PENDING,
						)
					}
					planStore.updateStatus(plan.revision, DesiredPlanStatus.FAILED)
					markStartFailed(run.logicalTrackingId, run.serviceRunId, wallTimeMs, failure, lease)
					SessionStartResult.Failed(run.logicalTrackingId, run.serviceRunId, applied, failure)
				}
			}
		} finally {
			releaseLease(lease)
		}
	}

	suspend fun compensatePreparedAndroidStart(
		token: PreparedTrackingStartToken,
		commandGeneration: Long,
		failureCode: String,
		currentBootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Boolean {
		val initial = database.sourceSessionDao().serviceRunByDeliveryToken(token.value) ?: return true
		if (initial.startCommandGeneration != commandGeneration) return false
		if (initial.completedAtMs != null || initial.state in TERMINAL_STATES) return true
		if (initial.bootId != currentBootId) return false
		val lease = acquireLease(preparedStartOwner(token)) ?: return false
		if (lease.generation != initial.leaseGeneration) {
			val finalized = terminalizeExpiredPreparedStart(
				token,
				commandGeneration,
				lease,
				elapsedRealtimeNanos,
				wallTimeMs,
				failureCode,
			)
			releaseLease(lease)
			return finalized
		}
		return try {
			database.withTransaction {
				requireLeaseInTransaction(lease)
				val dao = database.sourceSessionDao()
				val run = dao.serviceRunByDeliveryToken(token.value) ?: return@withTransaction true
				if (run.startCommandGeneration != commandGeneration) return@withTransaction false
				if (run.completedAtMs != null || run.state in TERMINAL_STATES) return@withTransaction true
				if (run.state != SessionLifecycleState.STARTING.name) return@withTransaction false
				val session = dao.session(run.logicalTrackingId) ?: return@withTransaction false
				if (session.state != SessionLifecycleState.STARTING.name ||
					session.currentServiceRunId != run.serviceRunId ||
					session.currentManifestRevision != run.preparedManifestRevision ||
					session.currentIntentRevision != run.preparedIntentRevision ||
					session.lifecycleLeaseGeneration != run.leaseGeneration ||
					session.lifecycleBootId != run.bootId
				) return@withTransaction false
				val manifest = verifiedManifest(
					run.logicalTrackingId,
					run.preparedManifestRevision,
					run.serviceRunId,
				)?.manifest ?: return@withTransaction false
				val intent = dao.lifecycleIntent(run.logicalTrackingId, run.preparedIntentRevision)
					?: return@withTransaction false
				if (manifest.acquisitionPlanRevision != run.desiredPlanRevision ||
					manifest.serviceRunId != run.serviceRunId ||
					intent.manifestRevision != run.preparedManifestRevision ||
					intent.desiredState != LifecycleDesiredState.ACTIVE.name
				) return@withTransaction false
				val exactActions = dao.lifecycleActions(run.logicalTrackingId).filter { action ->
					action.serviceRunId == run.serviceRunId &&
						action.manifestRevision == run.preparedManifestRevision
				}
				if (exactActions.any { action ->
						action.status == LifecycleActionStatus.CLEANUP_REQUIRED.name
				}) return@withTransaction false
				sourceBroker.retireSessionDemandsInTransaction(
					run.logicalTrackingId,
					currentBootId,
					elapsedRealtimeNanos,
					wallTimeMs,
				)
				exactActions.filter { action ->
					action.status in setOf(
						LifecycleActionStatus.AWAITING_FOREGROUND.name,
						LifecycleActionStatus.PENDING.name,
						LifecycleActionStatus.APPLYING.name,
					)
				}.forEach { action ->
					check(dao.updateLifecycleAction(
						action.copy(
							status = LifecycleActionStatus.SUPERSEDED.name,
							acknowledgedAtMs = wallTimeMs,
							acknowledgedElapsedRealtimeNanos = elapsedRealtimeNanos,
							failureCode = failureCode,
						),
					) == 1)
				}
				check(dao.updateSession(
					session.copy(
						state = SessionLifecycleState.FAILED.name,
						lifecycleRevision = session.lifecycleRevision + 1L,
						completedAtMs = wallTimeMs,
						failureCode = failureCode,
						currentServiceRunId = null,
					),
				) == 1)
				check(dao.updateServiceRun(
					run.copy(
						state = SessionLifecycleState.FAILED.name,
						completedAtMs = wallTimeMs,
						completionReason = failureCode,
						runtimeAcknowledgement = LifecycleActionStatus.TERMINAL_FAILURE.name,
						runtimeFailureCode = failureCode,
						androidDeliveryState = AndroidStartDeliveryState.TERMINAL_FAILURE.name,
						androidDeliveryUpdatedAtMs = wallTimeMs,
						runRevision = run.runRevision + 1L,
					),
				) == 1)
				val triggerId = intent.triggerId
				val triggerCollectedDataEpoch = intent.triggerCollectedDataEpoch
				if (triggerId != null && triggerCollectedDataEpoch != null) {
					database.activityAutomaticStartActionDao().markAcceptedLifecycleTerminal(
						triggerId = triggerId,
						collectedDataEpoch = triggerCollectedDataEpoch,
						logicalTrackingId = run.logicalTrackingId,
						intentRevision = run.preparedIntentRevision,
						terminalAtMs = wallTimeMs,
						reason = failureCode,
					)
				}
				true
			}
		} finally {
			releaseLease(lease)
		}
	}

	/**
	 * A prepared lease may expire before Android delivers the service. Reacquisition increments its
	 * generation, so the old desired actions cannot be applied. This exact terminal CAS is the only
	 * legal rebind: it requires every provider action to remain foreground-gated and never revives
	 * or rewrites a newer lifecycle.
	 */
	private suspend fun terminalizeExpiredPreparedStart(
		token: PreparedTrackingStartToken,
		commandGeneration: Long,
		lease: LifecycleLeaseToken,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		failureCode: String = "PREPARED_START_LEASE_EXPIRED",
	): Boolean = database.withTransaction {
		requireLeaseInTransaction(lease)
		val dao = database.sourceSessionDao()
		val run = dao.serviceRunByDeliveryToken(token.value) ?: return@withTransaction true
		if (run.startCommandGeneration != commandGeneration) return@withTransaction false
		if (run.completedAtMs != null || run.state in TERMINAL_STATES) return@withTransaction true
		if (run.state != SessionLifecycleState.STARTING.name ||
			run.androidDeliveryState !in setOf(
				AndroidStartDeliveryState.PREPARED.name,
				AndroidStartDeliveryState.ENQUEUED.name,
				AndroidStartDeliveryState.DELIVERED.name,
				AndroidStartDeliveryState.FOREGROUND_ACCEPTED.name,
			)
		) return@withTransaction false
		val session = dao.session(run.logicalTrackingId) ?: return@withTransaction false
		if (session.state != SessionLifecycleState.STARTING.name ||
			session.currentServiceRunId != run.serviceRunId ||
			session.currentManifestRevision != run.preparedManifestRevision ||
			session.currentIntentRevision != run.preparedIntentRevision ||
			session.lifecycleLeaseGeneration != run.leaseGeneration
		) return@withTransaction false
		val manifest = verifiedManifest(
			run.logicalTrackingId,
			run.preparedManifestRevision,
			run.serviceRunId,
		)?.manifest ?: return@withTransaction false
		val exactActions = dao.lifecycleActions(run.logicalTrackingId).filter { action ->
			action.serviceRunId == run.serviceRunId &&
				action.manifestRevision == run.preparedManifestRevision
		}
		val providerStillUntouched = exactActions.all { action ->
			action.status == LifecycleActionStatus.AWAITING_FOREGROUND.name ||
				(action.status == LifecycleActionStatus.PENDING.name &&
					action.attemptCount == 0 && action.sourceInstanceId == null &&
					action.registrationGeneration == null)
		}
		if (!providerStillUntouched) {
			return@withTransaction false
		}
		sourceBroker.retireSessionDemandsInTransaction(
			run.logicalTrackingId,
			lease.bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
		exactActions.forEach { action ->
			check(dao.updateLifecycleAction(
				action.copy(
					status = LifecycleActionStatus.SUPERSEDED.name,
					acknowledgedAtMs = wallTimeMs,
					acknowledgedElapsedRealtimeNanos = elapsedRealtimeNanos,
					failureCode = failureCode,
				),
			) == 1)
		}
		dao.updateSession(
			session.copy(
				state = SessionLifecycleState.FAILED.name,
				lifecycleRevision = session.lifecycleRevision + 1L,
				lifecycleLeaseGeneration = lease.generation,
				lifecycleBootId = lease.bootId,
				completedAtMs = wallTimeMs,
				failureCode = failureCode,
				currentServiceRunId = null,
			),
		)
		dao.updateServiceRun(
			run.copy(
				state = SessionLifecycleState.FAILED.name,
				leaseGeneration = lease.generation,
				completedAtMs = wallTimeMs,
				completionReason = failureCode,
				runtimeAcknowledgement = LifecycleActionStatus.TERMINAL_FAILURE.name,
				runtimeFailureCode = failureCode,
				androidDeliveryState = AndroidStartDeliveryState.TERMINAL_FAILURE.name,
				androidDeliveryUpdatedAtMs = wallTimeMs,
				runRevision = run.runRevision + 1L,
			),
		)
		val intent = dao.lifecycleIntent(run.logicalTrackingId, run.preparedIntentRevision)
		val triggerId = intent?.triggerId
		val triggerEpoch = intent?.triggerCollectedDataEpoch
		if (triggerId != null && triggerEpoch != null) {
			database.activityAutomaticStartActionDao().markAcceptedLifecycleTerminal(
				triggerId,
				triggerEpoch,
				run.logicalTrackingId,
				run.preparedIntentRevision,
				wallTimeMs,
				failureCode,
			)
		}
		true
	}

	suspend fun start(request: SessionStartRequest): SessionStartResult {
		if (request.plan.plans.values.none(SourcePlan::enabled)) {
			return SessionStartResult.InvalidIntent("ZERO_CAPTURE_SOURCES")
		}
		validateStartIntent(request)?.let { return SessionStartResult.InvalidIntent(it) }
		validateSourcePolicy(request.plan)?.let { return SessionStartResult.InvalidPolicy(it) }
		validateControlDependencies(request.plan.sourcePolicyRevision, request.controlDependencies)?.let {
			return SessionStartResult.InvalidPolicy(it)
		}
		val rollout = rolloutStore.load()
		val rolloutFailure = rollout.validateEventPlan(
			request.rolloutRevision,
			request.plan,
			request.origin.toSessionMode().captureReachabilityModeOrNull(),
		)
		if (rolloutFailure != null) return SessionStartResult.InvalidRollout(rolloutFailure)
		val catalogDecisions = runtimes.catalogPlanDecisions(
			request.plan,
			request.origin.toSourceAvailabilityTier(),
		)
		catalogDecisions.failureIfNoAcceptedSource()?.let {
			return SessionStartResult.InvalidIntent(it.code, it.disposition)
		}
		val lease = acquireLease(request.ownerToken)
			?: return SessionStartResult.Busy
		return try {
			validateSourcePolicy(request.plan)?.let { return SessionStartResult.InvalidPolicy(it) }
			var active = database.sourceSessionDao().activeSession()
			if (active != null) {
				if (request.origin == SessionStartOrigin.RECOVERY &&
					request.logicalTrackingId == active.logicalTrackingId
				) {
					return resume(active, request, lease, catalogDecisions)
				}
				if (isStalePriorSession(active, request)) {
					finalizeInterruptedSession(active, lease, request.wallTimeMs, "STALE_PRIOR_SESSION")
					active = database.sourceSessionDao().activeSession()
				}
				if (active != null) return SessionStartResult.AlreadyActive
			}
			val logicalTrackingId = request.logicalTrackingId ?: UUID.randomUUID().toString()
			if (request.origin == SessionStartOrigin.RECOVERY) {
				return SessionStartResult.InvalidIntent("RECOVERY_SESSION_MISSING")
			}
			if (request.automaticTrigger == null &&
				database.sourceSessionDao().session(logicalTrackingId) != null
			) {
				return SessionStartResult.InvalidIntent("LOGICAL_SESSION_ID_ALREADY_EXISTS")
			}
			val serviceRunId = serviceRunIdFor(request)
			var intentPolicyFailure: String? = null
			var intentRolloutFailure: String? = null
			var automaticActionFailure: String? = null
			var automaticActionAlreadyAccepted = false
			var persisted: PersistedLifecycleIntent? = null
			if (request.automaticTrigger != null) {
				activityAutomationEpochAuthority.currentForValidation()
			}
			val created = try {
				database.withTransaction {
				requireLeaseInTransaction(lease)
				intentPolicyFailure = validateSourcePolicyInTransaction(request.plan)
				if (intentPolicyFailure != null) return@withTransaction false
				intentRolloutFailure = validateRolloutInTransaction(
					request.rolloutRevision,
					request.plan,
					request.origin.toSessionMode(),
				)
				if (intentRolloutFailure != null) return@withTransaction false
				if (database.sourceSessionDao().activeSession() != null) return@withTransaction false
				val automaticAction = request.automaticTrigger?.let { trigger ->
					when (val validation =
						automaticStartActions.validateForLifecycleIntentTransaction(trigger)
					) {
						is ActivityAutomaticStartServiceValidation.Valid -> validation.action
						is ActivityAutomaticStartServiceValidation.LifecycleIntentAlreadyAccepted -> {
							automaticActionAlreadyAccepted = true
							return@withTransaction false
						}
						is ActivityAutomaticStartServiceValidation.Rejected -> {
							automaticActionFailure = validation.reason
							database.activityAutomaticStartActionDao().markTerminal(
								trigger.triggerId,
								trigger.collectedDataEpoch,
								request.wallTimeMs,
								validation.reason,
							)
							return@withTransaction false
						}
					}
				}
				if (database.sourceSessionDao().session(logicalTrackingId) != null) {
					automaticActionFailure = "LOGICAL_SESSION_ID_ALREADY_EXISTS"
					automaticAction?.let { action ->
						database.activityAutomaticStartActionDao().markTerminal(
							action.triggerId,
							action.collectedDataEpoch,
							request.wallTimeMs,
							"LOGICAL_SESSION_ID_ALREADY_EXISTS",
						)
					}
					return@withTransaction false
				}
				val draft = buildManifestDraft(
					logicalTrackingId = logicalTrackingId,
					manifestRevision = 1L,
					intentRevision = 1L,
					request = request,
					sessionMode = request.origin.toSessionMode(),
					changeReason = "SESSION_START",
					lease = lease,
					serviceRunId = serviceRunId,
					acceptedCaptureSources = catalogDecisions.acceptedSources,
				)
				if (automaticAction != null) {
					val captureMask = request.plan.captureSourceMask()
					val activityControlConsentEpoch = draft.bindings.singleOrNull { binding ->
						binding.sourceKind == SourceKind.ACTIVITY.stableCode &&
							binding.purpose == SessionManifestPurpose.CONTROL.name
					}?.consentEpoch
					automaticActionFailure = when {
						automaticAction.sourcePolicyRevision != request.plan.sourcePolicyRevision ->
							"AUTOMATIC_START_SOURCE_POLICY_MISMATCH"
						automaticAction.intendedCaptureSourceMask != captureMask ->
							"AUTOMATIC_START_CAPTURE_MASK_MISMATCH"
						automaticAction.intendedForegroundServiceTypeMask !=
							request.foregroundCapabilityFlags ->
							"AUTOMATIC_START_FGS_MASK_MISMATCH"
						automaticAction.controlConsentEpoch != activityControlConsentEpoch ->
							"AUTOMATIC_START_CONTROL_CONSENT_MISMATCH"
						else -> null
					}
					if (automaticActionFailure != null) {
						database.activityAutomaticStartActionDao().markTerminal(
							automaticAction.triggerId,
							automaticAction.collectedDataEpoch,
							request.wallTimeMs,
							requireNotNull(automaticActionFailure),
						)
						return@withTransaction false
					}
				}
				planStore.persistDesired(request.plan, DesiredPlanStatus.DESIRED)
				database.sourceSessionDao().insertSession(
					LogicalTrackingSessionEntity(
						logicalTrackingId = logicalTrackingId,
						state = SessionLifecycleState.STARTING.name,
						lifecycleRevision = 1,
						desiredPlanRevision = request.plan.revision,
						rolloutRevision = request.rolloutRevision,
						startOrigin = request.origin.name,
						clockDomainId = request.clockDomainId,
						startedAtMs = request.wallTimeMs,
						startedElapsedNanos = request.elapsedRealtimeNanos,
						cutoffAtMs = null,
						cutoffElapsedNanos = null,
						completedAtMs = null,
						finalAdmissionOrdinal = null,
						failureCode = null,
						sessionMode = draft.manifest.sessionMode,
					currentManifestRevision = draft.manifest.manifestRevision,
					currentIntentRevision = draft.intent.intentRevision,
					currentServiceRunId = serviceRunId,
					lifecycleLeaseGeneration = lease.generation,
						lifecycleBootId = lease.bootId,
						automationEpoch = draft.intent.automationEpoch,
					),
				)
				database.sourceSessionDao().insertManifest(draft.manifest)
				database.sourceSessionDao().insertManifestSources(draft.bindings)
				val guardedDraft = dispatchSourceCallerDemands(
					draft = draft,
					sessionMode = request.origin.toSessionMode(),
					startOrigin = request.origin,
					mutation = SessionDemandMutation.REPLACE_ACTIVE,
					lease = lease,
					bootId = request.clockDomainId,
					elapsedRealtimeNanos = request.elapsedRealtimeNanos,
					wallTimeMs = request.wallTimeMs,
				)
				database.sourceSessionDao().insertLifecycleIntent(guardedDraft.intent)
				database.sourceSessionDao().insertLifecycleActions(guardedDraft.actions)
				database.sourceSessionDao().insertServiceRun(
					SourceServiceRunEntity(
						serviceRunId = serviceRunId,
						logicalTrackingId = logicalTrackingId,
						state = SessionLifecycleState.STARTING.name,
						desiredPlanRevision = request.plan.revision,
						rolloutRevision = request.rolloutRevision,
						foregroundCapabilityFlags = request.foregroundCapabilityFlags,
						startedAtMs = request.wallTimeMs,
						startedElapsedNanos = request.elapsedRealtimeNanos,
						completedAtMs = null,
						completionReason = null,
						bootId = lease.bootId,
						leaseGeneration = lease.generation,
						startOrigin = request.origin.name,
						desiredForegroundCapabilityFlags = request.foregroundCapabilityFlags,
						appliedForegroundCapabilityFlags = null,
						runtimeAcknowledgement = LifecycleActionStatus.PENDING.name,
						runtimeFailureCode = null,
						runRevision = 1L,
					),
				)
				if (automaticAction != null) {
					check(
						database.activityAutomaticStartActionDao().markLifecycleIntentAccepted(
							triggerId = automaticAction.triggerId,
							collectedDataEpoch = automaticAction.collectedDataEpoch,
							logicalTrackingId = logicalTrackingId,
							intentRevision = guardedDraft.intent.intentRevision,
							acceptedAtMs = request.wallTimeMs,
						) == 1,
					) { "Automatic-start action changed before lifecycle-intent acceptance" }
				}
				persisted = guardedDraft
				true
				}
			} catch (rejection: SourceCallerDemandRejectedException) {
				automaticActionFailure = rejection.failureCode
				false
			}
			intentPolicyFailure?.let { return SessionStartResult.InvalidPolicy(it) }
			intentRolloutFailure?.let { return SessionStartResult.InvalidRollout(it) }
			automaticActionFailure?.let { return SessionStartResult.InvalidIntent(it) }
			if (automaticActionAlreadyAccepted) return SessionStartResult.AlreadyActive
			if (!created) return SessionStartResult.AlreadyActive
			if (request.automaticTrigger != null) activityAutomationDrainSignal.requestDrain()

			val lifecycleIntent = requireNotNull(persisted)
			planStore.updateStatus(request.plan.revision, DesiredPlanStatus.APPLYING)
			val sink = sinkFactory.forSession(logicalTrackingId, serviceRunId)
			val executions = reconcileStartActions(
				plan = request.plan,
				intent = lifecycleIntent,
				sink = sink,
				lease = lease,
				wallTimeMs = request.wallTimeMs,
				elapsedRealtimeNanos = request.elapsedRealtimeNanos,
				catalogDecisions = catalogDecisions,
			)
			var applied = executions.map(SourceActionExecution::applied)
			if (executions.any { execution -> execution.status == LifecycleActionStatus.CLEANUP_REQUIRED }) {
				planStore.updateStatus(request.plan.revision, DesiredPlanStatus.APPLYING)
				return SessionStartResult.Failed(
					logicalTrackingId,
					serviceRunId,
					applied,
					SOURCE_RUNTIME_CLEANUP_PENDING,
				)
			}
			val finalPolicyFailure = validateSourcePolicy(request.plan)
			if (finalPolicyFailure != null) {
				val rollback = rollbackStalePolicySources(
					request.plan,
					executions,
					request.elapsedRealtimeNanos,
					request.wallTimeMs,
				)
				applied = rollback.applied
				if (rollback.cleanupRequired) {
					planStore.updateStatus(request.plan.revision, DesiredPlanStatus.APPLYING)
					return SessionStartResult.Failed(
						logicalTrackingId,
						serviceRunId,
						applied,
						SOURCE_RUNTIME_CLEANUP_PENDING,
					)
				}
			}
			val effectiveStatus = applied.desiredStatus()
			planStore.updateStatus(request.plan.revision, effectiveStatus)
			val hasRunningSource = applied.any { state ->
				request.plan.plans[state.source]?.enabled == true &&
					state.status in setOf(SourceApplyStatus.APPLIED, SourceApplyStatus.DEGRADED)
			}
			if (!hasRunningSource) {
				markStartFailed(logicalTrackingId, serviceRunId, request.wallTimeMs, "NO_SOURCE_STARTED", lease)
				SessionStartResult.Failed(logicalTrackingId, serviceRunId, applied, "NO_SOURCE_STARTED")
			} else {
				val runningPolicyFailure = markRunningIfPolicyCurrent(
					logicalTrackingId,
					serviceRunId,
					request.plan,
					lifecycleIntent.manifest.manifestRevision,
					lease,
					request.foregroundCapabilityFlags,
				)
				if (runningPolicyFailure == null) {
					SessionStartResult.Started(
						logicalTrackingId,
						serviceRunId,
						applied,
						effectiveStatus,
						SourceCallerReplayReference(
							requireNotNull(lifecycleIntent.intent.sourceCallerAuthorityReference),
						),
					)
				} else {
					val rollback = rollbackStalePolicySources(
						request.plan,
						executions,
						request.elapsedRealtimeNanos,
						request.wallTimeMs,
					)
					applied = rollback.applied
					if (rollback.cleanupRequired) {
						planStore.updateStatus(request.plan.revision, DesiredPlanStatus.APPLYING)
						return SessionStartResult.Failed(
							logicalTrackingId,
							serviceRunId,
							applied,
							SOURCE_RUNTIME_CLEANUP_PENDING,
						)
					}
					planStore.updateStatus(request.plan.revision, DesiredPlanStatus.FAILED)
					markStartFailed(logicalTrackingId, serviceRunId, request.wallTimeMs, runningPolicyFailure, lease)
					SessionStartResult.Failed(logicalTrackingId, serviceRunId, applied, runningPolicyFailure)
				}
			}
		} finally {
			releaseLease(lease)
		}
	}

	private suspend fun resume(
		session: LogicalTrackingSessionEntity,
		request: SessionStartRequest,
		lease: LifecycleLeaseToken,
		catalogDecisions: CatalogPlanDecisions,
	): SessionStartResult {
		if (session.sessionMode != SessionMode.MANUAL.name ||
			session.state in TERMINAL_OR_STOPPING_STATES ||
			session.currentManifestRevision == null ||
			session.currentIntentRevision == null
		) {
			finalizeInterruptedSession(session, lease, request.wallTimeMs, "UNRECOVERABLE_SESSION")
			return SessionStartResult.InvalidIntent("SESSION_NOT_RECOVERY_ELIGIBLE")
		}
		if (session.rolloutRevision != request.rolloutRevision) {
			return SessionStartResult.InvalidRollout("SESSION_ROLLOUT_REVISION_MISMATCH")
		}
		val serviceRunId = serviceRunIdFor(request)
		var policyFailure: String? = null
		var rolloutFailure: String? = null
		var callerFailure: String? = null
		var persisted: PersistedLifecycleIntent? = null
		try {
			database.withTransaction {
			requireLeaseInTransaction(lease)
			policyFailure = validateSourcePolicyInTransaction(request.plan)
			if (policyFailure != null) return@withTransaction
			rolloutFailure = validateRolloutInTransaction(
				request.rolloutRevision,
				request.plan,
				request.origin.toSessionMode(),
			)
			if (rolloutFailure != null) return@withTransaction
			val current = requireNotNull(database.sourceSessionDao().session(session.logicalTrackingId))
			check(current.lifecycleRevision == session.lifecycleRevision) { "Session changed during recovery" }
			val manifestRevision = requireNotNull(current.currentManifestRevision) + 1L
			val intentRevision = requireNotNull(current.currentIntentRevision) + 1L
			val draft = buildManifestDraft(
				logicalTrackingId = session.logicalTrackingId,
				manifestRevision = manifestRevision,
				intentRevision = intentRevision,
				request = request,
				sessionMode = SessionMode.MANUAL,
				changeReason = "PROCESS_RECOVERY",
				lease = lease,
				serviceRunId = serviceRunId,
				acceptedCaptureSources = catalogDecisions.acceptedSources,
			)
			planStore.persistDesired(request.plan, DesiredPlanStatus.APPLYING)
			val continuation = requireNotNull(request.continuationAuthority)
			val previousRun = requireNotNull(
				database.sourceSessionDao().serviceRun(continuation.previousServiceRunId),
			) { "Continuation service run is missing" }
			check(previousRun.logicalTrackingId == current.logicalTrackingId) {
				"Continuation service run belongs to another logical session"
			}
			val incompleteRunIds = database.sourceSessionDao().incompleteServiceRuns(current.logicalTrackingId)
				.map(SourceServiceRunEntity::serviceRunId)
			if (previousRun.completedAtMs == null) {
				check(current.currentServiceRunId == previousRun.serviceRunId &&
					incompleteRunIds == listOf(previousRun.serviceRunId)) {
					"Continuation service run is not the current run"
				}
				database.sourceSessionDao().updateServiceRun(
					previousRun.copy(
						state = SessionLifecycleState.FINALIZED.name,
						completedAtMs = request.wallTimeMs,
						completionReason = "PROCESS_DEATH_RECOVERY",
						runtimeAcknowledgement = LifecycleActionStatus.TERMINAL_FAILURE.name,
						runtimeFailureCode = "PROCESS_DEATH_RECOVERY",
						runRevision = previousRun.runRevision + 1L,
					),
				)
			} else {
				check(current.currentServiceRunId == null && incompleteRunIds.isEmpty()) {
					"Finalized continuation cannot replace a current run"
				}
			}
			supersedePendingActions(session.logicalTrackingId, request.wallTimeMs, request.elapsedRealtimeNanos)
			database.sourceSessionDao().insertManifest(draft.manifest)
			database.sourceSessionDao().insertManifestSources(draft.bindings)
			database.sourceSessionDao().updateSession(
				current.copy(
					state = SessionLifecycleState.STARTING.name,
					lifecycleRevision = current.lifecycleRevision + 1,
					desiredPlanRevision = request.plan.revision,
					cutoffAtMs = null,
					cutoffElapsedNanos = null,
					completedAtMs = null,
					finalAdmissionOrdinal = null,
					failureCode = null,
					currentManifestRevision = manifestRevision,
					currentIntentRevision = intentRevision,
					currentServiceRunId = serviceRunId,
					lifecycleLeaseGeneration = lease.generation,
					lifecycleBootId = lease.bootId,
				),
			)
			val guardedDraft = dispatchSourceCallerDemands(
				draft = draft,
				sessionMode = SessionMode.MANUAL,
				startOrigin = SessionStartOrigin.RECOVERY,
				mutation = SessionDemandMutation.REPLACE_ACTIVE,
				lease = lease,
				bootId = request.clockDomainId,
				elapsedRealtimeNanos = request.elapsedRealtimeNanos,
				wallTimeMs = request.wallTimeMs,
			)
			database.sourceSessionDao().insertLifecycleIntent(guardedDraft.intent)
			database.sourceSessionDao().insertLifecycleActions(guardedDraft.actions)
			database.sourceSessionDao().insertServiceRun(
				SourceServiceRunEntity(
					serviceRunId = serviceRunId,
					logicalTrackingId = session.logicalTrackingId,
					state = SessionLifecycleState.STARTING.name,
					desiredPlanRevision = request.plan.revision,
					rolloutRevision = request.rolloutRevision,
					foregroundCapabilityFlags = request.foregroundCapabilityFlags,
					startedAtMs = request.wallTimeMs,
					startedElapsedNanos = request.elapsedRealtimeNanos,
					completedAtMs = null,
					completionReason = null,
					bootId = lease.bootId,
					leaseGeneration = lease.generation,
					startOrigin = request.origin.name,
					desiredForegroundCapabilityFlags = request.foregroundCapabilityFlags,
					appliedForegroundCapabilityFlags = null,
					runtimeAcknowledgement = LifecycleActionStatus.PENDING.name,
					runtimeFailureCode = null,
					runRevision = 1L,
				),
			)
			persisted = guardedDraft
			}
		} catch (rejection: SourceCallerDemandRejectedException) {
			callerFailure = rejection.failureCode
			if (rejection.disposition == TrackingStartFailureDisposition.TERMINAL) {
				finalizeInterruptedSession(
					session,
					lease,
					request.wallTimeMs,
					rejection.failureCode,
				)
			}
		}
		policyFailure?.let { return SessionStartResult.InvalidPolicy(it) }
		rolloutFailure?.let { return SessionStartResult.InvalidRollout(it) }
		callerFailure?.let { return SessionStartResult.InvalidIntent(it) }
		val lifecycleIntent = requireNotNull(persisted)
		val sink = sinkFactory.forSession(session.logicalTrackingId, serviceRunId)
		val executions = reconcileStartActions(
			request.plan,
			lifecycleIntent,
			sink,
			lease,
			request.wallTimeMs,
			request.elapsedRealtimeNanos,
			catalogDecisions,
		)
		var applied = executions.map(SourceActionExecution::applied)
		if (executions.any { execution -> execution.status == LifecycleActionStatus.CLEANUP_REQUIRED }) {
			planStore.updateStatus(request.plan.revision, DesiredPlanStatus.APPLYING)
			return SessionStartResult.Failed(
				session.logicalTrackingId,
				serviceRunId,
				applied,
				SOURCE_RUNTIME_CLEANUP_PENDING,
			)
		}
		if (validateSourcePolicy(request.plan) != null) {
			val rollback = rollbackStalePolicySources(
				request.plan,
				executions,
				request.elapsedRealtimeNanos,
				request.wallTimeMs,
			)
			applied = rollback.applied
			if (rollback.cleanupRequired) {
				planStore.updateStatus(request.plan.revision, DesiredPlanStatus.APPLYING)
				return SessionStartResult.Failed(
					session.logicalTrackingId,
					serviceRunId,
					applied,
					SOURCE_RUNTIME_CLEANUP_PENDING,
				)
			}
		}
		val effectiveStatus = applied.desiredStatus()
		planStore.updateStatus(request.plan.revision, effectiveStatus)
		val hasRunningSource = applied.any { state ->
			request.plan.plans[state.source]?.enabled == true &&
				state.status in setOf(SourceApplyStatus.APPLIED, SourceApplyStatus.DEGRADED)
		}
		return if (hasRunningSource) {
			val runningPolicyFailure = markRunningIfPolicyCurrent(
				session.logicalTrackingId,
				serviceRunId,
				request.plan,
				lifecycleIntent.manifest.manifestRevision,
				lease,
				request.foregroundCapabilityFlags,
			)
			if (runningPolicyFailure == null) {
				SessionStartResult.Started(
					session.logicalTrackingId,
					serviceRunId,
					applied,
					effectiveStatus,
					SourceCallerReplayReference(
						requireNotNull(lifecycleIntent.intent.sourceCallerAuthorityReference),
					),
				)
			} else {
				val rollback = rollbackStalePolicySources(
					request.plan,
					executions,
					request.elapsedRealtimeNanos,
					request.wallTimeMs,
				)
				applied = rollback.applied
				if (rollback.cleanupRequired) {
					planStore.updateStatus(request.plan.revision, DesiredPlanStatus.APPLYING)
					return SessionStartResult.Failed(
						session.logicalTrackingId,
						serviceRunId,
						applied,
						SOURCE_RUNTIME_CLEANUP_PENDING,
					)
				}
				planStore.updateStatus(request.plan.revision, DesiredPlanStatus.FAILED)
				markStartFailed(
					session.logicalTrackingId,
					serviceRunId,
					request.wallTimeMs,
					runningPolicyFailure,
					lease,
				)
				SessionStartResult.Failed(
					session.logicalTrackingId,
					serviceRunId,
					applied,
					runningPolicyFailure,
				)
			}
		} else {
			markStartFailed(
				session.logicalTrackingId,
				serviceRunId,
				request.wallTimeMs,
				"NO_SOURCE_RESTORED",
				lease,
			)
			SessionStartResult.Failed(
				session.logicalTrackingId,
				serviceRunId,
				applied,
				"NO_SOURCE_RESTORED",
			)
		}
	}

	suspend fun reconfigure(request: SessionReconfigureRequest): SessionReconfigureResult {
		validateCanonicalBootId(request.clockDomainId)?.let {
			return SessionReconfigureResult.InvalidIntent(it)
		}
		validateSourcePolicy(request.plan)?.let { return SessionReconfigureResult.InvalidPolicy(it) }
		validateControlDependencies(request.plan.sourcePolicyRevision, request.controlDependencies)?.let {
			return SessionReconfigureResult.InvalidPolicy(it)
		}
		val rollout = rolloutStore.load()
		val sessionForRollout = database.sourceSessionDao().activeSession()
			?: return SessionReconfigureResult.NoActiveSession
		val rolloutFailure = rollout.validateEventPlan(
			sessionForRollout.rolloutRevision,
			request.plan,
			SessionMode.valueOf(sessionForRollout.sessionMode).captureReachabilityModeOrNull(),
		)
		if (rolloutFailure != null) return SessionReconfigureResult.InvalidRollout(rolloutFailure)
		val catalogDecisions = runtimes.catalogPlanDecisions(
			request.plan,
			SourceAvailabilityTier.SESSION_ALREADY_FOREGROUND,
		)
		catalogDecisions.retryableFailure()?.let { debt ->
			return SessionReconfigureResult.Retryable(
				revision = request.plan.revision,
				failureCode = debt.failure.code,
				sources = debt.sources,
			)
		}
		val lease = acquireLease(request.ownerToken)
			?: return SessionReconfigureResult.Busy
		return try {
			validateSourcePolicy(request.plan)?.let { return SessionReconfigureResult.InvalidPolicy(it) }
			val session = database.sourceSessionDao().activeSession()
				?: return SessionReconfigureResult.NoActiveSession
			if (session.state != SessionLifecycleState.ACTIVE.name) {
				return SessionReconfigureResult.InvalidState(session.state)
			}
			var intentPolicyFailure: String? = null
			var intentValidationFailure: String? = null
			var persisted: PersistedLifecycleIntent? = null
			var boundTransition: BoundServiceRunTransition? = null
			try {
				database.withTransaction {
				requireLeaseInTransaction(lease)
				intentPolicyFailure = validateSourcePolicyInTransaction(request.plan)
				if (intentPolicyFailure != null) return@withTransaction
				val current = requireNotNull(database.sourceSessionDao().session(session.logicalTrackingId))
				check(current.lifecycleRevision == session.lifecycleRevision &&
					current.state == SessionLifecycleState.ACTIVE.name
				) { "Session changed during reconfiguration" }
				val currentServiceRunId = current.currentServiceRunId
				val currentManifestEnvelope = current.currentManifestRevision?.let { revision ->
					currentServiceRunId?.let { serviceRunId ->
						verifiedManifest(current.logicalTrackingId, revision, serviceRunId)
					}
				}

				if (currentManifestEnvelope == null) {
					intentValidationFailure = "CURRENT_MANIFEST_INTEGRITY_FAILED"
					return@withTransaction
				}
				val currentManifest = currentManifestEnvelope.manifest
				if (current.lifecycleBootId != request.clockDomainId ||
					currentManifest.effectiveBootId != request.clockDomainId
				) {
					intentValidationFailure = "RECONFIGURE_BOOT_DOMAIN_CHANGED"
					return@withTransaction
				}
				if (request.elapsedRealtimeNanos < currentManifest.effectiveElapsedRealtimeNanos) {
					intentValidationFailure = "RECONFIGURE_EFFECTIVE_TIME_REGRESSED"
					return@withTransaction
				}
				val manifestRevision = requireNotNull(current.currentManifestRevision) + 1L
				val intentRevision = requireNotNull(current.currentIntentRevision) + 1L
				val serviceRun = requireCurrentServiceRun(current)
				val draft = buildManifestDraft(
					logicalTrackingId = session.logicalTrackingId,
					manifestRevision = manifestRevision,
					intentRevision = intentRevision,
					plan = request.plan,
					rolloutRevision = current.rolloutRevision,
					origin = SessionStartOrigin.POLICY_RECONCILIATION,
					clockDomainId = request.clockDomainId,
					zoneId = request.zoneId,
					controlDependencies = request.controlDependencies,
					automaticTrigger = null,
					wallTimeMs = request.wallTimeMs,
					elapsedRealtimeNanos = request.elapsedRealtimeNanos,
					foregroundCapabilityFlags = request.foregroundCapabilityFlags,
					sessionMode = SessionMode.valueOf(current.sessionMode),
					changeReason = "POLICY_RECONCILIATION",
					lease = lease,
					serviceRunId = serviceRun.serviceRunId,
					acceptedCaptureSources = catalogDecisions.acceptedSources,
				)
				val priorManifestEnvelopes = database.sourceSessionDao()
					.manifestsForServiceRun(serviceRun.serviceRunId)
					.map { prior ->
						verifiedManifest(
							prior,
							serviceRun.serviceRunId,
						)
					}
				if (priorManifestEnvelopes.any { it == null }) {
					intentValidationFailure = "PRIOR_MANIFEST_INTEGRITY_FAILED"
					return@withTransaction
				}
				val establishedWriters = priorManifestEnvelopes
					.filterNotNull()
					.flatMap(VerifiedSessionManifest::bindings)
					.filter(SessionManifestSourceEntity::isSessionCaptureWriter)
					.groupBy(SessionManifestSourceEntity::sourceKind)
					.mapValues { (_, bindings) ->
						bindings.distinctBy(SessionManifestSourceEntity::writerProvenanceIdentity)
					}
				if (establishedWriters.values.any { writers -> writers.size > 1 }) {
					intentValidationFailure = "WRITER_PROVENANCE_INCONSISTENT_WITHIN_SERVICE_RUN"
					return@withTransaction
				}
				val proposedWriters = draft.bindings
					.filter(SessionManifestSourceEntity::isSessionCaptureWriter)
					.associateBy(SessionManifestSourceEntity::sourceKind)
				val changedWriter = establishedWriters.any { (sourceKind, writers) ->
					val proposed = proposedWriters[sourceKind]
					val established = writers.singleOrNull()
					established != null && proposed != null &&
						!established.hasSameWriterProvenance(proposed)
				}
				if (changedWriter) {
					intentValidationFailure = "WRITER_PROVENANCE_CHANGED_WITHIN_SERVICE_RUN"
					return@withTransaction
				}
				planStore.persistDesired(request.plan, DesiredPlanStatus.APPLYING)
				database.sourceSessionDao().insertManifest(draft.manifest)
				database.sourceSessionDao().insertManifestSources(draft.bindings)
				database.sourceSessionDao().updateSession(
					current.copy(
						state = SessionLifecycleState.RECONFIGURING.name,
						lifecycleRevision = current.lifecycleRevision + 1,
						desiredPlanRevision = request.plan.revision,
						currentManifestRevision = manifestRevision,
						currentIntentRevision = intentRevision,
						lifecycleLeaseGeneration = lease.generation,
						lifecycleBootId = lease.bootId,
					),
				)
				database.sourceSessionDao().updateServiceRun(
					serviceRun.copy(
						desiredPlanRevision = request.plan.revision,
						desiredForegroundCapabilityFlags = request.foregroundCapabilityFlags,
						leaseGeneration = lease.generation,
						bootId = lease.bootId,
						runRevision = serviceRun.runRevision + 1L,
					),
				)
				val guardedDraft = dispatchSourceCallerDemands(
					draft = draft,
					sessionMode = SessionMode.valueOf(current.sessionMode),
					startOrigin = SessionStartOrigin.POLICY_RECONCILIATION,
					mutation = SessionDemandMutation.REPLACE_ACTIVE,
					lease = lease,
					bootId = request.clockDomainId,
					elapsedRealtimeNanos = request.elapsedRealtimeNanos,
					wallTimeMs = request.wallTimeMs,
				)
				database.sourceSessionDao().insertLifecycleIntent(guardedDraft.intent)
				database.sourceSessionDao().insertLifecycleActions(guardedDraft.actions)
				persisted = guardedDraft
				boundTransition = BoundServiceRunTransition(
					session = current.copy(
						state = SessionLifecycleState.RECONFIGURING.name,
						lifecycleRevision = current.lifecycleRevision + 1,
						desiredPlanRevision = request.plan.revision,
						currentManifestRevision = manifestRevision,
						currentIntentRevision = intentRevision,
						lifecycleLeaseGeneration = lease.generation,
						lifecycleBootId = lease.bootId,
					),
					serviceRunId = serviceRun.serviceRunId,
					expectedRunRevision = serviceRun.runRevision + 1L,
				)
				}
			} catch (rejection: SourceCallerDemandRejectedException) {
				intentValidationFailure = rejection.failureCode
			}
			intentPolicyFailure?.let { return SessionReconfigureResult.InvalidPolicy(it) }
			intentValidationFailure?.let { return SessionReconfigureResult.InvalidIntent(it) }
			val bound = requireNotNull(boundTransition)
			val existing = database.sourcePlanStateDao().appliedStates().associateBy { it.sourceKind }
			val sink = sinkFactory.forSession(session.logicalTrackingId, bound.serviceRunId)
			val executions = reconcileReconfigureActions(
				plan = request.plan,
				intent = requireNotNull(persisted),
				existing = existing,
				sink = sink,
				lease = lease,
				wallTimeMs = request.wallTimeMs,
				elapsedRealtimeNanos = request.elapsedRealtimeNanos,
				catalogDecisions = catalogDecisions,
			)
			var applied = executions.map(SourceActionExecution::applied)
			if (executions.any { execution -> execution.status == LifecycleActionStatus.CLEANUP_REQUIRED }) {
				planStore.updateStatus(request.plan.revision, DesiredPlanStatus.APPLYING)
				return SessionReconfigureResult.Failed(
					request.plan.revision,
					applied,
					SOURCE_RUNTIME_CLEANUP_PENDING,
					requireNotNull(persisted).sourceCallerAuthorityReference(),
				)
			}
			val finalPolicyFailure = validateSourcePolicy(request.plan)
			if (finalPolicyFailure != null) {
				val rollback = rollbackStalePolicySources(
					request.plan,
					executions,
					request.elapsedRealtimeNanos,
					request.wallTimeMs,
				)
				applied = rollback.applied
				if (rollback.cleanupRequired) {
					planStore.updateStatus(request.plan.revision, DesiredPlanStatus.APPLYING)
					return SessionReconfigureResult.Failed(
						request.plan.revision,
						applied,
						SOURCE_RUNTIME_CLEANUP_PENDING,
						requireNotNull(persisted).sourceCallerAuthorityReference(),
					)
				}
				planStore.updateStatus(request.plan.revision, DesiredPlanStatus.FAILED)
				markStartFailed(
					session.logicalTrackingId,
					bound.serviceRunId,
					request.wallTimeMs,
					"SOURCE_POLICY_RECONFIGURE_STALE",
					lease,
				)
				return SessionReconfigureResult.InvalidPolicy(finalPolicyFailure)
			}
			val status = applied.desiredStatus()
			planStore.updateStatus(request.plan.revision, status)
			val hasRunningSource = applied.any { state ->
				request.plan.plans[state.source]?.enabled == true &&
					state.status in setOf(SourceApplyStatus.APPLIED, SourceApplyStatus.DEGRADED)
			}
			if (!hasRunningSource) {
				planStore.updateStatus(request.plan.revision, DesiredPlanStatus.FAILED)
				val failureCode = catalogDecisions.failureIfNoAcceptedSource()?.code
					?: "NO_SOURCE_ACTIVE_AFTER_RECONFIGURE"
				markStartFailed(
					session.logicalTrackingId,
					bound.serviceRunId,
					request.wallTimeMs,
					failureCode,
					lease,
				)
				return SessionReconfigureResult.Failed(
					request.plan.revision,
					applied,
					failureCode,
					requireNotNull(persisted).sourceCallerAuthorityReference(),
				)
			}
			val acceptanceFailure = markReconfiguredIfPolicyCurrent(
				bound = bound,
				plan = request.plan,
				manifestRevision = requireNotNull(persisted).manifest.manifestRevision,
				lease = lease,
				foregroundCapabilityFlags = request.foregroundCapabilityFlags,
			)
			if (acceptanceFailure != null) {
				val rollback = rollbackStalePolicySources(
					request.plan,
					executions,
					request.elapsedRealtimeNanos,
					request.wallTimeMs,
				)
				applied = rollback.applied
				if (rollback.cleanupRequired) {
					planStore.updateStatus(request.plan.revision, DesiredPlanStatus.APPLYING)
					return SessionReconfigureResult.Failed(
						request.plan.revision,
						applied,
						SOURCE_RUNTIME_CLEANUP_PENDING,
						requireNotNull(persisted).sourceCallerAuthorityReference(),
					)
				}
				planStore.updateStatus(request.plan.revision, DesiredPlanStatus.FAILED)
				markStartFailed(
					session.logicalTrackingId,
					bound.serviceRunId,
					request.wallTimeMs,
					acceptanceFailure,
					lease,
				)
				return SessionReconfigureResult.Failed(
					request.plan.revision,
					applied,
					acceptanceFailure,
					requireNotNull(persisted).sourceCallerAuthorityReference(),
				)
			}
			SessionReconfigureResult.Applied(
				request.plan.revision,
				applied,
				status,
				SourceCallerReplayReference(
					requireNotNull(persisted.intent.sourceCallerAuthorityReference),
				),
			)
		} finally {
			releaseLease(lease)
		}
	}

	internal suspend fun retireSupersededSourceCallerAuthority(
		logicalTrackingId: String,
		currentReference: SourceCallerReplayReference,
		supersededReference: SourceCallerReplayReference,
		wallTimeMs: Long,
	): SourceCallerAuthorityRetirementOutcome = try {
		database.withTransaction {
			if (currentReference == supersededReference || wallTimeMs < 0L) {
				return@withTransaction SourceCallerAuthorityRetirementOutcome.TerminalInvariant
			}
			val session = database.sourceSessionDao().session(logicalTrackingId)
				?: return@withTransaction SourceCallerAuthorityRetirementOutcome.TerminalMissing
			val intentRevision = session.currentIntentRevision
				?: return@withTransaction SourceCallerAuthorityRetirementOutcome.TerminalMissing
			val currentIntent = database.sourceSessionDao()
				.lifecycleIntent(logicalTrackingId, intentRevision)
				?: return@withTransaction SourceCallerAuthorityRetirementOutcome.TerminalMissing
			val intentReference = currentIntent.sourceCallerAuthorityReference
				?.takeIf(String::isNotBlank)
				?: return@withTransaction SourceCallerAuthorityRetirementOutcome.TerminalCorrupt
			if (intentReference != currentReference.value) {
				return@withTransaction SourceCallerAuthorityRetirementOutcome.Retryable(
					SourceCallerAuthorityRetirementRetryReason.CURRENT_AUTHORITY_CHANGED,
				)
			}
			sourceBroker.retireSupersededSessionAuthoritiesInTransaction(
				logicalTrackingId,
				currentReference,
				supersededReference,
				wallTimeMs,
			)
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (failure: Exception) {
		if (failure.isTrackingOperationalFailure()) {
			SourceCallerAuthorityRetirementOutcome.Retryable(
				SourceCallerAuthorityRetirementRetryReason.STORAGE_UNAVAILABLE,
			)
		} else {
			SourceCallerAuthorityRetirementOutcome.TerminalInvariant
		}
	}

	/**
	 * Resolves the exact current Room-owned caller authority for a recoverable physical run.
	 *
	 * The accepted authority is authenticated against the current manifest, policy, execution
	 * generation, and owner token before the caller may mirror it outside Room.
	 */
	internal suspend fun currentRecoverySourceCallerAuthority(
		logicalTrackingId: String,
		serviceRunId: String,
	): CurrentRecoverySourceCallerAuthorityResult {
		suspend fun readCandidate(): CurrentRecoverySourceCallerAuthority? =
			database.withTransaction {
				val dao = database.sourceSessionDao()
				val session = dao.session(logicalTrackingId)
				val manifestRevision = session?.currentManifestRevision
				val intentRevision = session?.currentIntentRevision
				currentRecoverySourceCallerAuthorityCandidate(
					logicalTrackingId = logicalTrackingId,
					serviceRunId = serviceRunId,
					session = session,
					run = dao.serviceRun(serviceRunId),
					manifest = manifestRevision
						?.let { revision -> dao.manifest(logicalTrackingId, revision) },
					bindings = manifestRevision
						?.let { revision -> dao.manifestSources(logicalTrackingId, revision) }
						.orEmpty(),
					intent = intentRevision
						?.let { revision -> dao.lifecycleIntent(logicalTrackingId, revision) },
				)
			}
		val candidate = try {
			readCandidate()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			val operational = failure.isTrackingOperationalFailure()
			return CurrentRecoverySourceCallerAuthorityResult.Rejected(
				failureCode = if (operational) {
					"RECOVERY_SOURCE_CALLER_ROOM_AUTHORITY_UNAVAILABLE"
				} else {
					"RECOVERY_SOURCE_CALLER_ROOM_AUTHORITY_INVALID"
				},
				disposition = if (operational) {
					TrackingStartFailureDisposition.RETRYABLE
				} else {
					TrackingStartFailureDisposition.TERMINAL
				},
			)
		} ?: return CurrentRecoverySourceCallerAuthorityResult.Rejected(
			failureCode = "RECOVERY_SOURCE_CALLER_ROOM_AUTHORITY_INVALID",
			disposition = TrackingStartFailureDisposition.TERMINAL,
		)
		return when (val authenticated = sourceCallerDemandDispatcher.authenticatePreparedSession(
			manifestIdentity = candidate.manifestIdentity,
			reference = candidate.reference,
			replayKind = SourceCallerReplayKind.PROCESS_RECOVERY,
		)) {
			is SourceCallerGuardResult.Permitted ->
				try {
					if (readCandidate() == candidate) {
						CurrentRecoverySourceCallerAuthorityResult.Available(candidate)
					} else {
						CurrentRecoverySourceCallerAuthorityResult.Rejected(
							failureCode = "RECOVERY_SOURCE_CALLER_ROOM_AUTHORITY_CHANGED",
							disposition = TrackingStartFailureDisposition.RETRYABLE,
						)
					}
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (failure: Exception) {
					val operational = failure.isTrackingOperationalFailure()
					CurrentRecoverySourceCallerAuthorityResult.Rejected(
						failureCode = if (operational) {
							"RECOVERY_SOURCE_CALLER_ROOM_AUTHORITY_UNAVAILABLE"
						} else {
							"RECOVERY_SOURCE_CALLER_ROOM_AUTHORITY_INVALID"
						},
						disposition = if (operational) {
							TrackingStartFailureDisposition.RETRYABLE
						} else {
							TrackingStartFailureDisposition.TERMINAL
						},
					)
				}
			is SourceCallerGuardResult.Rejected ->
				CurrentRecoverySourceCallerAuthorityResult.Rejected(
					failureCode =
						"RECOVERY_SOURCE_CALLER_${authenticated.rejection.reason.name}",
					disposition = if (authenticated.rejection.reason.isRetryable) {
						TrackingStartFailureDisposition.RETRYABLE
					} else {
						TrackingStartFailureDisposition.TERMINAL
					},
				)
		}
	}

	/** New runtime work must always be bound to one immutable, current policy revision. */
	private suspend fun validateSourcePolicy(plan: AcquisitionPlanRevision): String? =
		database.withTransaction { validateSourcePolicyInTransaction(plan) }

	private suspend fun validateSourcePolicyInTransaction(plan: AcquisitionPlanRevision): String? {
		val revision = plan.sourcePolicyRevision ?: return "SOURCE_POLICY_BINDING_MISSING"
		val dao = database.sourcePolicyDao()
		val authority = dao.authority() ?: return "SOURCE_POLICY_MISSING"
		if (authority.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE) {
			return "SOURCE_POLICY_NOT_ACTIVE"
		}
		if (authority.currentPolicyRevision != revision) {
			return "SOURCE_POLICY_REVISION_STALE"
		}
		val policies = dao.policiesAtRevision(revision)
		if (policies.size != SourceKind.entries.size ||
			policies.map(SourcePolicyEntity::sourceKind).toSet().size != SourceKind.entries.size
		) {
			return "SOURCE_POLICY_INCOMPLETE"
		}
		val policyBySource = policies.associateBy(SourcePolicyEntity::sourceKind)
		if (plan.plans.values.any { sourcePlan ->
				policyBySource[sourcePlan.source.stableCode]?.allows(sourcePlan) != true
			}) {
			return "SOURCE_PLAN_EXCEEDS_POLICY"
		}
		for (sourcePlan in plan.plans.values.filter(SourcePlan::enabled)) {
			val policy = requireNotNull(policyBySource[sourcePlan.source.stableCode])
			if (!dao.hasAuthoritativeConsentReference(
					policy = policy,
					purpose = POLICY_PURPOSE_CAPTURE,
					requirePersistenceEligible = true,
				)
			) {
				return "CAPTURE_CONSENT_MISSING:${sourcePlan.source.name}"
			}
		}
		return null
	}

	/**
	 * Closes the stale-preparation race with a source-writer cutover. The caller already owns the
	 * session coordinator lease; re-reading the durable rollout in the same transaction that creates
	 * the run guarantees a start either precedes the cutover or uses its new writer generation.
	 */
	private suspend fun validateRolloutInTransaction(
		expectedRevision: Long,
		plan: AcquisitionPlanRevision,
		sessionMode: SessionMode,
	): String? {
		val rollout = database.trackingRolloutStateDao().get()
			?.decodeCurrentModelOrNull()
			?: return "ROLLOUT_STATE_MISSING_OR_UNREADABLE"
		return rollout.validateEventPlan(
			expectedRevision,
			plan,
			sessionMode.captureReachabilityModeOrNull(),
		)
	}

	private fun validateStartIntent(request: SessionStartRequest): String? {
		validateCanonicalBootId(request.clockDomainId)?.let { return it }
		return when {
			request.zoneId.isBlank() -> "ZONE_ID_MISSING"
			request.serviceRunId?.isBlank() == true -> "SERVICE_RUN_ID_INVALID"
			request.serviceRunId == LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID -> "SERVICE_RUN_ID_RESERVED"
			request.origin == SessionStartOrigin.RECOVERY && request.logicalTrackingId == null ->
				"RECOVERY_ID_MISSING"
			request.continuationAuthority != null &&
				(request.logicalTrackingId == null || request.serviceRunId == null) ->
				"CONTINUATION_ID_MISSING"
			request.continuationAuthority != null &&
				request.continuationAuthority.previousServiceRunId == request.serviceRunId ->
				"CONTINUATION_RUN_ID_REUSED"
			request.continuationAuthority != null &&
				request.origin == SessionStartOrigin.AUTOMATIC_BACKGROUND_START ->
				"AUTOMATIC_CONTINUATION_FORBIDDEN"
			request.origin == SessionStartOrigin.POLICY_RECONCILIATION ->
				"POLICY_RECONCILIATION_START_FORBIDDEN"
			request.origin == SessionStartOrigin.AUTOMATIC_BACKGROUND_START &&
				SourceKind.ACTIVITY !in request.controlDependencies ->
				"AUTOMATIC_ACTIVITY_CONTROL_MISSING"
			request.origin == SessionStartOrigin.AUTOMATIC_BACKGROUND_START &&
				request.automaticTrigger == null -> "AUTOMATIC_TRIGGER_MISSING"
			request.origin != SessionStartOrigin.AUTOMATIC_BACKGROUND_START &&
				request.automaticTrigger != null -> "AUTOMATIC_TRIGGER_UNEXPECTED"
			request.automaticTrigger?.bootId?.let { it != request.clockDomainId } == true ->
				"AUTOMATIC_TRIGGER_BOOT_STALE"
			request.automaticTrigger?.let { trigger ->
				request.elapsedRealtimeNanos < trigger.receivedElapsedRealtimeNanos ||
					request.elapsedRealtimeNanos > trigger.expiresElapsedRealtimeNanos
			} == true -> "AUTOMATIC_TRIGGER_STALE"
			request.automaticTrigger?.let { trigger ->
				trigger.sourcePolicyRevision != request.plan.sourcePolicyRevision
			} == true -> "AUTOMATIC_TRIGGER_EPOCH_STALE"
			else -> null
		}
	}

	private fun validateCanonicalBootId(requestBootId: String): String? = when {
		requestBootId.isBlank() -> "BOOT_ID_MISSING"
		requestBootId != bootClockDomainProvider.current() -> "BOOT_ID_STALE"
		else -> null
	}

	private suspend fun validateControlDependencies(
		policyRevision: Long?,
		controlDependencies: Set<SourceKind>,
	): String? = database.withTransaction {
		val revision = policyRevision ?: return@withTransaction "SOURCE_POLICY_BINDING_MISSING"
		val policyDao = database.sourcePolicyDao()
		for (source in controlDependencies) {
			val policy = policyDao.policyAtRevision(revision, source.stableCode)
			if (policy == null || !policyDao.hasAuthoritativeConsentReference(
					policy = policy,
					purpose = POLICY_PURPOSE_CONTROL,
					requirePersistenceEligible = false,
				)
			) {
				return@withTransaction "CONTROL_CONSENT_MISSING:${source.name}"
			}
		}
		null
	}

	private suspend fun exactCandidateStepsBinding(
		rolloutRevision: Long,
		sessionMode: SessionMode,
	): ExecutableSourceLaneBinding {
		val rollout = requireNotNull(
			database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull(),
		) { "Steps rollout state is missing or unreadable" }
		check(rollout.revision == rolloutRevision) { "Steps rollout revision changed" }
		val lane = requireNotNull(
			database.sourceProjectionStateDao().activeProductLane(SourceKind.STEPS.stableCode),
		) { "Steps candidate product lane is missing" }
		val binding = requireNotNull(executableLaneCatalog.bindingFor(lane)) {
			"Steps candidate product lane is not executable"
		}
		check(binding.projectionId == StepsSessionFactProjectionLane.WRITER_ID &&
			binding.projectionVersion == StepsSessionFactProjectionLane.WRITER_VERSION
		) { "Steps candidate writer identity is unsupported" }
		val captureMode = requireNotNull(sessionMode.captureReachabilityModeOrNull()) {
			"Steps candidate session mode is not attributable"
		}
		check(captureMode in binding.captureModes) {
			"Steps candidate binding does not authorize ${captureMode.name}"
		}
		check(lane.isCanonicalCaptureAuthorizedBy(database, rollout, executableLaneCatalog)) {
			"Steps candidate product lane is not canonical capture authority"
		}
		return binding
	}

	private suspend fun exactCandidatePressureBinding(
		rolloutRevision: Long,
		sessionMode: SessionMode,
	): ExecutableSourceLaneBinding {
		check(sessionMode == SessionMode.MANUAL) {
			"Pressure candidate binding is manual-session-only"
		}
		val rollout = requireNotNull(
			database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull(),
		) { "Pressure rollout state is missing or unreadable" }
		check(rollout.revision == rolloutRevision) { "Pressure rollout revision changed" }
		val lane = requireNotNull(
			database.sourceProjectionStateDao().activeProductLane(SourceKind.PRESSURE.stableCode),
		) { "Pressure candidate product lane is missing" }
		val binding = requireNotNull(executableLaneCatalog.bindingFor(lane)) {
			"Pressure candidate product lane is not executable"
		}
		check(binding.hasSameWriterSemanticsAs(
			ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS,
		)) {
			"Pressure candidate writer binding is unsupported"
		}
		check(lane.isCanonicalCaptureAuthorizedBy(database, rollout, executableLaneCatalog)) {
			"Pressure candidate product lane is not canonical capture authority"
		}
		return binding
	}

	private suspend fun exactCandidateActivityBinding(
		rolloutRevision: Long,
		sessionMode: SessionMode,
	): ExecutableSourceLaneBinding {
		val rollout = requireNotNull(
			database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull(),
		) { "Activity rollout state is missing or unreadable" }
		check(rollout.revision == rolloutRevision) { "Activity rollout revision changed" }
		val lane = requireNotNull(
			database.sourceProjectionStateDao().activeProductLane(SourceKind.ACTIVITY.stableCode),
		) { "Activity candidate product lane is missing" }
		val binding = requireNotNull(executableLaneCatalog.bindingFor(lane)) {
			"Activity candidate product lane is not executable"
		}
		check(binding.hasSameWriterSemanticsAs(
			ExecutableSourceLaneCatalog.ACTIVITY_SESSION_FACTS,
		)) {
			"Activity candidate writer binding is unsupported"
		}
		val captureMode = requireNotNull(sessionMode.captureReachabilityModeOrNull()) {
			"Activity candidate session mode is not attributable"
		}
		check(captureMode in binding.captureModes) {
			"Activity candidate binding does not authorize ${captureMode.name}"
		}
		check(binding.projectionId == ActivityCapturedFactProjectionLane.WRITER_ID &&
			binding.projectionVersion == ActivityCapturedFactProjectionLane.WRITER_VERSION
		) { "Activity candidate writer identity is unsupported" }
		check(lane.isCanonicalCaptureAuthorizedBy(database, rollout, executableLaneCatalog)) {
			"Activity candidate product lane is not canonical capture authority"
		}
		return binding
	}

	private suspend fun exactCandidateWifiBinding(
		rolloutRevision: Long,
		sessionMode: SessionMode,
	): ExecutableSourceLaneBinding {
		check(sessionMode == SessionMode.MANUAL) {
			"Wi-Fi candidate binding is manual-session-only"
		}
		val rollout = requireNotNull(
			database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull(),
		) { "Wi-Fi rollout state is missing or unreadable" }
		check(rollout.revision == rolloutRevision) { "Wi-Fi rollout revision changed" }
		val lane = requireNotNull(
			database.sourceProjectionStateDao().activeProductLane(SourceKind.WIFI.stableCode),
		) { "Wi-Fi candidate product lane is missing" }
		val binding = requireNotNull(executableLaneCatalog.bindingFor(lane)) {
			"Wi-Fi candidate product lane is not executable"
		}
		check(binding.hasSameWriterSemanticsAs(ExecutableSourceLaneCatalog.WIFI_SESSION_FACTS) &&
			binding.projectionId == WifiSessionFactProjectionLane.WRITER_ID &&
			binding.projectionVersion == WifiSessionFactProjectionLane.WRITER_VERSION
		) { "Wi-Fi candidate writer binding is unsupported" }
		check(lane.isCanonicalCaptureAuthorizedBy(database, rollout, executableLaneCatalog)) {
			"Wi-Fi candidate product lane is not canonical capture authority"
		}
		return binding
	}

	private suspend fun exactCandidateCellBinding(
		rolloutRevision: Long,
		sessionMode: SessionMode,
	): ExecutableSourceLaneBinding {
		check(sessionMode == SessionMode.MANUAL) {
			"Cell candidate binding is manual-session-only"
		}
		val rollout = requireNotNull(
			database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull(),
		) { "Cell rollout state is missing or unreadable" }
		check(rollout.revision == rolloutRevision) { "Cell rollout revision changed" }
		val lane = requireNotNull(
			database.sourceProjectionStateDao().activeProductLane(SourceKind.CELL.stableCode),
		) { "Cell candidate product lane is missing" }
		val binding = requireNotNull(executableLaneCatalog.bindingFor(lane)) {
			"Cell candidate product lane is not executable"
		}
		check(binding.hasSameWriterSemanticsAs(ExecutableSourceLaneCatalog.CELL_SESSION_FACTS) &&
			binding.projectionId == CellSessionFactProjectionLane.WRITER_ID &&
			binding.projectionVersion == CellSessionFactProjectionLane.WRITER_VERSION
		) { "Cell candidate writer binding is unsupported" }
		check(lane.isCanonicalCaptureAuthorizedBy(database, rollout, executableLaneCatalog)) {
			"Cell candidate product lane is not canonical capture authority"
		}
		return binding
	}

	private suspend fun buildManifestDraft(
		logicalTrackingId: String,
		manifestRevision: Long,
		intentRevision: Long,
		request: SessionStartRequest,
		sessionMode: SessionMode,
		changeReason: String,
		lease: LifecycleLeaseToken,
		serviceRunId: String,
		acceptedCaptureSources: Set<SourceKind>,
	): PersistedLifecycleIntent = buildManifestDraft(
		logicalTrackingId = logicalTrackingId,
		manifestRevision = manifestRevision,
		intentRevision = intentRevision,
		plan = request.plan,
		rolloutRevision = request.rolloutRevision,
		origin = request.origin,
		clockDomainId = request.clockDomainId,
		zoneId = request.zoneId,
		controlDependencies = request.controlDependencies,
		automaticTrigger = request.automaticTrigger,
		wallTimeMs = request.wallTimeMs,
		elapsedRealtimeNanos = request.elapsedRealtimeNanos,
		foregroundCapabilityFlags = request.foregroundCapabilityFlags,
		sessionMode = sessionMode,
		changeReason = changeReason,
		lease = lease,
		serviceRunId = serviceRunId,
		acceptedCaptureSources = acceptedCaptureSources,
	)

	private suspend fun dispatchSourceCallerDemands(
		draft: PersistedLifecycleIntent,
		sessionMode: SessionMode,
		startOrigin: SessionStartOrigin,
		mutation: SessionDemandMutation,
		lease: LifecycleLeaseToken,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): PersistedLifecycleIntent {
		return when (val result = sourceCallerDemandDispatcher.dispatchSession(
			SessionSourceDemandDispatchRequest(
				manifest = draft.manifest,
				bindings = draft.bindings,
				sessionMode = sessionMode,
				startOrigin = startOrigin,
				mutation = mutation,
				lifecycleLeaseGeneration = lease.generation,
				bootId = bootId,
				elapsedRealtimeNanos = elapsedRealtimeNanos,
				wallTimeMs = wallTimeMs,
			),
		)) {
			is SessionSourceDemandDispatchResult.Permitted ->
				draft.withSourceCallerReceipt(result.receipt)
			is SessionSourceDemandDispatchResult.Rejected ->
				throw SourceCallerDemandRejectedException(result.rejection.reason)
		}
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
	private suspend fun buildManifestDraft(
		logicalTrackingId: String,
		manifestRevision: Long,
		intentRevision: Long,
		plan: AcquisitionPlanRevision,
		rolloutRevision: Long,
		origin: SessionStartOrigin,
		clockDomainId: String,
		zoneId: String,
		controlDependencies: Set<SourceKind>,
		automaticTrigger: AutomaticTrackingStartTrigger?,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
		foregroundCapabilityFlags: Long,
		sessionMode: SessionMode,
		changeReason: String,
		lease: LifecycleLeaseToken,
		serviceRunId: String,
		acceptedCaptureSources: Set<SourceKind>,
	): PersistedLifecycleIntent {
		require(acceptedCaptureSources.all { source -> plan.plans[source]?.enabled == true }) {
			"Accepted capture sources must be enabled in the desired plan"
		}
		val policyRevision = requireNotNull(plan.sourcePolicyRevision)
		val policyDao = database.sourcePolicyDao()
		val policies = policyDao.policiesAtRevision(policyRevision).associateBy(SourcePolicyEntity::sourceKind)
		val stepsOwner = plan.plans[SourceKind.STEPS]
			?.takeIf { it.enabled && SourceKind.STEPS in acceptedCaptureSources }
			?.let {
				requireNotNull(
					database.sourceDestinationOwnerDao().get(
						SourceDestinationOwnerEntity.SOURCE_STEPS,
						SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
					),
				) { "Steps destination owner is missing" }
			}
		check(stepsOwner == null ||
			stepsOwner.owner == SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS ||
			stepsOwner.owner == SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL
		) { "Unsupported Steps destination owner ${stepsOwner?.owner}" }
		val pressureOwner = plan.plans[SourceKind.PRESSURE]
			?.takeIf { it.enabled && SourceKind.PRESSURE in acceptedCaptureSources }
			?.let {
				requireNotNull(
					database.sourceDestinationOwnerDao().get(
						SourceDestinationOwnerEntity.SOURCE_PRESSURE,
						SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
					),
				) { "Pressure destination owner is missing" }
			}
		check(pressureOwner == null || when (pressureOwner.owner) {
			SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE ->
				pressureOwner.ownerGeneration == SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
			SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS ->
				com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
					.bindingGenerationForCanonicalOwner(pressureOwner.ownerGeneration) != null
			else -> false
		}) { "Unsupported Pressure destination owner ${pressureOwner?.owner}" }
		val activityOwner = plan.plans[SourceKind.ACTIVITY]
			?.takeIf { it.enabled && SourceKind.ACTIVITY in acceptedCaptureSources }
			?.let {
				requireNotNull(
					database.sourceDestinationOwnerDao().get(
						SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
						SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
					),
				) { "Activity destination owner is missing" }
			}
		check(activityOwner == null || when (activityOwner.owner) {
			SourceDestinationOwnerEntity.OWNER_LEGACY_ACTIVITY_SNAPSHOT ->
				activityOwner.ownerGeneration == SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
			SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS ->
				com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
					.bindingGenerationForCanonicalOwner(activityOwner.ownerGeneration) != null
			else -> false
		}) { "Unsupported Activity destination owner ${activityOwner?.owner}" }
		val wifiOwner = plan.plans[SourceKind.WIFI]
			?.takeIf { it.enabled && SourceKind.WIFI in acceptedCaptureSources }
			?.let {
				requireNotNull(
					database.sourceDestinationOwnerDao().get(
						SourceDestinationOwnerEntity.SOURCE_WIFI,
						SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI,
					),
				) { "Wi-Fi destination owner is missing" }
			}
		check(wifiOwner == null ||
			wifiOwner.owner == SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS &&
			com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
				.bindingGenerationForCanonicalOwner(wifiOwner.ownerGeneration) != null
		) { "Unsupported Wi-Fi destination owner ${wifiOwner?.owner}" }
		val cellOwner = plan.plans[SourceKind.CELL]
			?.takeIf { it.enabled && SourceKind.CELL in acceptedCaptureSources }
			?.let {
				requireNotNull(
					database.sourceDestinationOwnerDao().get(
						SourceDestinationOwnerEntity.SOURCE_CELL,
						SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
					),
				) { "Cell destination owner is missing" }
			}
		check(cellOwner == null ||
			cellOwner.owner == SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS &&
			com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
				.bindingGenerationForCanonicalOwner(cellOwner.ownerGeneration) != null
		) { "Unsupported Cell destination owner ${cellOwner?.owner}" }
		val candidateStepsBinding = stepsOwner
			?.takeIf { owner ->
				owner.owner == SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
			}
			?.let { exactCandidateStepsBinding(rolloutRevision, sessionMode) }
		val candidatePressureBinding = pressureOwner
			?.takeIf { owner ->
				owner.owner == SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS
			}
			?.let { exactCandidatePressureBinding(rolloutRevision, sessionMode) }
		val candidateActivityBinding = activityOwner
			?.takeIf { owner ->
				owner.owner == SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS
			}
			?.let { exactCandidateActivityBinding(rolloutRevision, sessionMode) }
		val candidateWifiBinding = wifiOwner
			?.let { exactCandidateWifiBinding(rolloutRevision, sessionMode) }
		val candidateCellBinding = cellOwner
			?.let { exactCandidateCellBinding(rolloutRevision, sessionMode) }
		val captureBindings = plan.plans.values
			.filter { sourcePlan ->
				sourcePlan.enabled && sourcePlan.source in acceptedCaptureSources
			}
			.map { sourcePlan ->
			val policy = requireNotNull(policies[sourcePlan.source.stableCode])
			val consentEpoch = requireNotNull(policy.captureConsentEpoch)
			check(
				policyDao.hasAuthoritativeConsentReference(
					policy = policy,
					purpose = POLICY_PURPOSE_CAPTURE,
					requirePersistenceEligible = true,
				),
			)
			val writerOwner = when (sourcePlan.source) {
				SourceKind.STEPS -> stepsOwner
				SourceKind.PRESSURE -> pressureOwner
				SourceKind.ACTIVITY -> activityOwner
				SourceKind.WIFI -> wifiOwner
				SourceKind.CELL -> cellOwner
				else -> null
			}
			val candidateWriter = when (sourcePlan.source) {
				SourceKind.STEPS -> candidateStepsBinding
				SourceKind.PRESSURE -> candidatePressureBinding
				SourceKind.ACTIVITY -> candidateActivityBinding
				SourceKind.WIFI -> candidateWifiBinding
				SourceKind.CELL -> candidateCellBinding
				else -> null
			}
			SessionManifestSourceEntity(
				logicalTrackingId = logicalTrackingId,
				manifestRevision = manifestRevision,
				sourceKind = sourcePlan.source.stableCode,
				purpose = SessionManifestPurpose.SESSION_CAPTURE.name,
				consentEpoch = consentEpoch,
				persistenceEligible = true,
				qosCode = policy.qosCode,
				outputDestination = writerOwner?.destination,
				writerOwner = writerOwner?.owner,
				writerOwnerGeneration = writerOwner?.ownerGeneration,
				writerProjectionId = candidateWriter?.projectionId,
				writerProjectionVersion = candidateWriter?.projectionVersion,
				writerBindingGeneration = candidateWriter?.bindingGeneration,
			)
		}
		val controlBindings = controlDependencies.map { source ->
			val policy = requireNotNull(policies[source.stableCode])
			val consentEpoch = requireNotNull(policy.controlConsentEpoch)
			check(
				policyDao.hasAuthoritativeConsentReference(
					policy = policy,
					purpose = POLICY_PURPOSE_CONTROL,
					requirePersistenceEligible = false,
				),
			)
			SessionManifestSourceEntity(
				logicalTrackingId = logicalTrackingId,
				manifestRevision = manifestRevision,
				sourceKind = source.stableCode,
				purpose = SessionManifestPurpose.CONTROL.name,
				consentEpoch = consentEpoch,
				persistenceEligible = policy.controlPersistenceEligible,
				qosCode = policy.qosCode,
			)
		}
		val bindings = (captureBindings + controlBindings).sortedWith(
			compareBy(SessionManifestSourceEntity::purpose, SessionManifestSourceEntity::sourceKind),
		)
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = logicalTrackingId,
			manifestRevision = manifestRevision,
			serviceRunId = serviceRunId,
			sessionMode = sessionMode.name,
			sourcePolicyRevision = policyRevision,
			acquisitionPlanRevision = plan.revision,
			rolloutRevision = rolloutRevision,
			startOrigin = origin.name,
			effectiveBootId = clockDomainId,
			effectiveElapsedRealtimeNanos = elapsedRealtimeNanos,
			effectiveWallTimeMs = wallTimeMs,
			zoneId = zoneId,
			automationEpoch = automaticTrigger?.automationEpoch,
			changeReason = changeReason,
			manifestChecksum = "",
		)
		val manifest = unsignedManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, bindings),
		)
		val intentChecksum = stableLifecycleChecksum(
			logicalTrackingId,
			intentRevision,
			manifestRevision,
			LifecycleDesiredState.ACTIVE,
			origin,
			clockDomainId,
			elapsedRealtimeNanos,
			wallTimeMs,
			automaticTrigger?.triggerId,
			automaticTrigger?.automationEpoch,
			automaticTrigger?.collectedDataEpoch,
			null,
		)
		val intent = SessionLifecycleIntentVersionEntity(
			logicalTrackingId = logicalTrackingId,
			intentRevision = intentRevision,
			manifestRevision = manifestRevision,
			desiredState = LifecycleDesiredState.ACTIVE.name,
			startOrigin = origin.name,
			requestBootId = clockDomainId,
			requestedElapsedRealtimeNanos = elapsedRealtimeNanos,
			requestedWallTimeMs = wallTimeMs,
			automationEpoch = automaticTrigger?.automationEpoch,
			triggerId = automaticTrigger?.triggerId,
			triggerKind = automaticTrigger?.kind,
			triggerBootId = automaticTrigger?.bootId,
			triggerObservedElapsedRealtimeNanos = automaticTrigger?.observedElapsedRealtimeNanos,
			triggerReceivedElapsedRealtimeNanos = automaticTrigger?.receivedElapsedRealtimeNanos,
			triggerExpiresElapsedRealtimeNanos = automaticTrigger?.expiresElapsedRealtimeNanos,
			stopReason = null,
			stopDeadlineBootId = null,
			stopDeadlineElapsedRealtimeNanos = null,
			intentChecksum = intentChecksum,
			triggerCollectedDataEpoch = automaticTrigger?.collectedDataEpoch,
			sourceCallerAuthorityReference = null,
		)
		var nextActionRevision = database.sourceSessionDao().maximumActionRevision(logicalTrackingId) + 1L
		val actions = plan.plans.values.sortedBy { it.source.stableCode }.mapNotNull { sourcePlan ->
			if (!sourcePlan.enabled && manifestRevision == 1L) return@mapNotNull null
			val accepted = sourcePlan.source in acceptedCaptureSources
			if (sourcePlan.enabled && !accepted && manifestRevision == 1L) return@mapNotNull null
			val binding = captureBindings.firstOrNull { it.sourceKind == sourcePlan.source.stableCode }
			val desiredState =
				if (sourcePlan.enabled && accepted) ACTION_DESIRED_STARTED else ACTION_DESIRED_STOPPED
			val actionRevision = nextActionRevision++
			LifecycleDesiredActionEntity(
				actionId = lifecycleActionIdentity(
					intentRevision,
					logicalTrackingId,
					serviceRunId,
					manifestRevision,
					actionRevision,
					LifecycleActionFamily.SOURCE_RUNTIME.name,
					sourcePlan.source.stableCode,
					desiredState,
					plan.revision,
					policyRevision,
					binding?.consentEpoch,
					origin.name,
					clockDomainId,
					lease.generation,
					wallTimeMs,
					elapsedRealtimeNanos,
				),
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				manifestRevision = manifestRevision,
				actionRevision = actionRevision,
				actionFamily = LifecycleActionFamily.SOURCE_RUNTIME.name,
				sourceKind = sourcePlan.source.stableCode,
				desiredState = desiredState,
				desiredPlanRevision = plan.revision,
				sourcePolicyRevision = policyRevision,
				consentEpoch = binding?.consentEpoch,
				startOrigin = origin.name,
				bootId = clockDomainId,
				leaseGeneration = lease.generation,
				requestedAtMs = wallTimeMs,
				requestedElapsedRealtimeNanos = elapsedRealtimeNanos,
				status = LifecycleActionStatus.PENDING.name,
				attemptCount = 0,
				acknowledgedAtMs = null,
				acknowledgedElapsedRealtimeNanos = null,
				failureCode = null,
				retryTrigger = null,
				sourceInstanceId = null,
				registrationGeneration = null,
			)
		}
		return PersistedLifecycleIntent(
			manifest,
			bindings,
			intent,
			actions,
			emptyList(),
			foregroundCapabilityFlags,
		)
	}

	@Suppress("LongParameterList")
	private fun lifecycleActionIdentity(
		intentRevision: Long,
		logicalTrackingId: String,
		serviceRunId: String,
		manifestRevision: Long,
		actionRevision: Long,
		actionFamily: String,
		sourceKind: Int,
		desiredState: String,
		desiredPlanRevision: Long,
		sourcePolicyRevision: Long,
		consentEpoch: Long?,
		startOrigin: String,
		bootId: String,
		leaseGeneration: Long,
		requestedAtMs: Long,
		requestedElapsedRealtimeNanos: Long,
	): String = if (sourceKind == SourceKind.STEPS.stableCode) {
		stableLifecycleChecksum(
			"STEPS_ACTION_V2",
			intentRevision,
			logicalTrackingId,
			serviceRunId,
			manifestRevision,
			actionRevision,
			actionFamily,
			sourceKind,
			desiredState,
			desiredPlanRevision,
			sourcePolicyRevision,
			consentEpoch,
			startOrigin,
			bootId,
			leaseGeneration,
			requestedAtMs,
			requestedElapsedRealtimeNanos,
		)
	} else {
		stableLifecycleChecksum(logicalTrackingId, intentRevision, sourceKind, desiredState)
	}

	private suspend fun captureAuthorizationCurrent(
		authorization: CaptureAuthorization,
		source: SourceKind,
		observedElapsedRealtimeNanos: Long? = null,
	): Boolean = database.withTransaction {
		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority() ?: return@withTransaction false
		val policy = policyDao.policyAtRevision(authorization.policyRevision, source.stableCode)
		val sessionDao = database.sourceSessionDao()
		val session = sessionDao.session(authorization.logicalTrackingId)
		val run = sessionDao.serviceRun(authorization.serviceRunId)
		val manifestEnvelope = verifiedManifest(
			authorization.logicalTrackingId,
			authorization.manifestRevision,
			authorization.serviceRunId,
		)
		val binding = manifestEnvelope?.bindings?.singleOrNull { candidate ->
			candidate.sourceKind == source.stableCode &&
				candidate.purpose == SessionManifestPurpose.SESSION_CAPTURE.name
		}
		val beforeOrAtCutoff = observedElapsedRealtimeNanos?.let { observed ->
			session?.cutoffElapsedNanos?.let { cutoff -> observed <= cutoff }
		} == true
		val lifecycleEligible = session?.state in setOf(
			SessionLifecycleState.STARTING.name,
			SessionLifecycleState.ACTIVE.name,
			SessionLifecycleState.RECONFIGURING.name,
		) && run?.state in setOf(SessionLifecycleState.STARTING.name, SessionLifecycleState.ACTIVE.name) ||
			(session?.state == SessionLifecycleState.STOPPING.name &&
				run?.state == SessionLifecycleState.STOPPING.name && beforeOrAtCutoff)
		authority.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
			authority.currentPolicyRevision == authorization.policyRevision &&
			policy?.enabled == true &&
			policy.capturePersistenceEligible &&
			policy.captureConsentEpoch == authorization.captureConsentEpoch &&
			lifecycleEligible &&
			session?.currentServiceRunId == authorization.serviceRunId &&
			session.currentManifestRevision == authorization.manifestRevision &&
			session.lifecycleLeaseGeneration == authorization.leaseGeneration &&
			run?.logicalTrackingId == authorization.logicalTrackingId &&
			run.desiredPlanRevision == manifestEnvelope?.manifest?.acquisitionPlanRevision &&
			run.leaseGeneration == authorization.leaseGeneration &&
			manifestEnvelope.manifest.sourcePolicyRevision == authorization.policyRevision &&
			binding?.consentEpoch == authorization.captureConsentEpoch &&
			binding.persistenceEligible
	}

	private suspend fun reconcileStartActions(
		plan: AcquisitionPlanRevision,
		intent: PersistedLifecycleIntent,
		sink: SourceEventSink,
		lease: LifecycleLeaseToken,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
		catalogDecisions: CatalogPlanDecisions,
	): List<SourceActionExecution> {
		val actionBySource = intent.actions.associateBy { it.sourceKind }
		val bindingBySource = intent.bindings
			.filter { it.purpose == SessionManifestPurpose.SESSION_CAPTURE.name }
			.associateBy { it.sourceKind }
		return plan.plans.values.sortedBy { it.source.stableCode }.map { sourcePlan ->
			val catalogDecision = catalogDecisions.getValue(sourcePlan.source)
			if (!sourcePlan.enabled) {
				val state = disabledApplied(sourcePlan, elapsedRealtimeNanos)
				planStore.saveApplied(state, wallTimeMs)
				return@map SourceActionExecution(state, LifecycleActionStatus.STOP_ACCEPTED)
			}
			if (catalogDecision is CatalogSourcePlanDecision.Blocked) {
				val execution = catalogDecision.toBlockedExecution(elapsedRealtimeNanos)
				val action = actionBySource[sourcePlan.source.stableCode]
				if (action == null) {
					planStore.saveApplied(execution.applied, wallTimeMs)
					return@map execution
				}
				val nowElapsed = monotonicNowAtLeast(elapsedRealtimeNanos)
				renewLease(lease)
				val claimed = claimLifecycleAction(action.actionId, lease, nowElapsed)
				if (claimed == null) {
					val failed = execution.copy(failureCode = "SOURCE_ACTION_CLAIM_FAILED")
					planStore.saveApplied(failed.applied, wallTimeMs)
					return@map failed
				}
				val acknowledged = acknowledgeLifecycleAction(
					claimed,
					execution,
					lease,
					wallTimeMs,
					monotonicNowAtLeast(nowElapsed),
				)
				val settled = if (acknowledged) {
					execution
				} else {
					execution.copy(failureCode = "SOURCE_ACTION_ACK_STALE")
				}
				planStore.saveApplied(settled.applied, wallTimeMs)
				return@map settled
			}
			val acceptedDecision = catalogDecision as CatalogSourcePlanDecision.Accepted
			val action = requireNotNull(actionBySource[sourcePlan.source.stableCode])
			val binding = requireNotNull(bindingBySource[sourcePlan.source.stableCode])
			val nowElapsed = monotonicNowAtLeast(elapsedRealtimeNanos)
			renewLease(lease)
			val claimed = claimLifecycleAction(action.actionId, lease, nowElapsed)
			if (claimed == null) {
				return@map SourceActionExecution(
					failedApplied(sourcePlan, nowElapsed, SourceApplyStatus.FAILED),
					LifecycleActionStatus.TERMINAL_FAILURE,
					"SOURCE_ACTION_CLAIM_FAILED",
				)
			}
			val runtimeClaim = claimed.toRuntimeClaim(sourcePlan.source)
			val authorization = CaptureAuthorization(
				logicalTrackingId = intent.manifest.logicalTrackingId,
				serviceRunId = action.serviceRunId,
				policyRevision = intent.manifest.sourcePolicyRevision,
				captureConsentEpoch = binding.consentEpoch,
				manifestRevision = intent.manifest.manifestRevision,
				leaseGeneration = lease.generation,
			)
			val execution = startSource(
				acceptedDecision.effectivePlan,
				sink,
				authorization,
				runtimeClaim,
				nowElapsed,
				wallTimeMs,
			).withCatalogDecision(acceptedDecision)
			planStore.saveApplied(execution.applied, wallTimeMs)
			val acknowledged = acknowledgeLifecycleAction(
				claimed,
				execution,
				lease,
				wallTimeMs,
				monotonicNowAtLeast(nowElapsed),
			)
			if (!acknowledged) {
				val staleExecution = settleRejectedRuntimeExecution(
					acceptedDecision.effectivePlan,
					authorization,
					runtimeClaim,
					nowElapsed,
					wallTimeMs,
					SourceActionExecution(
						failedApplied(sourcePlan, nowElapsed, SourceApplyStatus.FAILED),
						LifecycleActionStatus.TERMINAL_FAILURE,
						"SOURCE_ACTION_ACK_STALE",
						runtimeClaim = runtimeClaim,
						captureAuthorization = authorization,
					),
				)
				preserveCleanupRequiredAction(claimed, staleExecution, wallTimeMs, nowElapsed)
				staleExecution
			} else {
				execution
			}
		}
	}

	private suspend fun reconcileReconfigureActions(
		plan: AcquisitionPlanRevision,
		intent: PersistedLifecycleIntent,
		existing: Map<Int, com.adsamcik.tracker.shared.base.database.data.SourceAppliedPlanStateEntity>,
		sink: SourceEventSink,
		lease: LifecycleLeaseToken,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
		catalogDecisions: CatalogPlanDecisions,
	): List<SourceActionExecution> {
		val actionBySource = intent.actions.associateBy { it.sourceKind }
		val bindingBySource = intent.bindings
			.filter { it.purpose == SessionManifestPurpose.SESSION_CAPTURE.name }
			.associateBy { it.sourceKind }
		return plan.plans.values.sortedBy { it.source.stableCode }.map { sourcePlan ->
			val catalogDecision = catalogDecisions.getValue(sourcePlan.source)
			val action = requireNotNull(actionBySource[sourcePlan.source.stableCode])
			val nowElapsed = monotonicNowAtLeast(elapsedRealtimeNanos)
			renewLease(lease)
			val claimed = claimLifecycleAction(action.actionId, lease, nowElapsed)
			if (claimed == null) {
				return@map SourceActionExecution(
					failedApplied(sourcePlan, nowElapsed, SourceApplyStatus.FAILED),
					LifecycleActionStatus.TERMINAL_FAILURE,
					"SOURCE_ACTION_CLAIM_FAILED",
				)
			}
			val runtimeClaim = claimed.toRuntimeClaim(sourcePlan.source)
			val binding = bindingBySource[sourcePlan.source.stableCode]
			val authorization = CaptureAuthorization(
				logicalTrackingId = intent.manifest.logicalTrackingId,
				serviceRunId = action.serviceRunId,
				policyRevision = intent.manifest.sourcePolicyRevision,
				captureConsentEpoch = binding?.consentEpoch ?: 0L,
				manifestRevision = intent.manifest.manifestRevision,
				leaseGeneration = lease.generation,
			)
			val existingRuntime = existing[sourcePlan.source.stableCode]?.sourceInstanceId != null
			val execution = when (catalogDecision) {
				is CatalogSourcePlanDecision.Disabled -> {
					if (!existingRuntime) {
						startSource(sourcePlan, sink, authorization, runtimeClaim, nowElapsed, wallTimeMs)
					} else {
						reconfigureSource(
							sourcePlan,
							sink,
							authorization,
							runtimeClaim,
							nowElapsed,
							wallTimeMs,
						)
					}
				}
				is CatalogSourcePlanDecision.Accepted -> {
					val runtimePlan = catalogDecision.effectivePlan
					val applied = if (!existingRuntime) {
						startSource(runtimePlan, sink, authorization, runtimeClaim, nowElapsed, wallTimeMs)
					} else {
						reconfigureSource(
							runtimePlan,
							sink,
							authorization,
							runtimeClaim,
							nowElapsed,
							wallTimeMs,
						)
					}
					applied.withCatalogDecision(catalogDecision)
				}
				is CatalogSourcePlanDecision.Blocked -> {
					if (!existingRuntime) {
						catalogDecision.toBlockedExecution(nowElapsed)
					} else {
						val stopped = reconfigureSource(
							sourcePlan.disabledForCatalog(),
							sink,
							authorization,
							runtimeClaim,
							nowElapsed,
							wallTimeMs,
						)
						if (stopped.status == LifecycleActionStatus.STOP_ACCEPTED) {
							stopped.copy(
								applied = catalogDecision.blockedApplied(nowElapsed),
								failureCode = catalogDecision.failure.code,
							)
						} else {
							stopped.copy(
								applied = stopped.applied.copy(
									degradedReasons = stopped.applied.degradedReasons +
										catalogDecision.reasons,
								),
							)
						}
					}
				}
			}
			planStore.saveApplied(execution.applied, wallTimeMs)
			val acknowledged = acknowledgeLifecycleAction(
				claimed,
				execution,
				lease,
				wallTimeMs,
				monotonicNowAtLeast(nowElapsed),
			)
			if (!acknowledged) {
				val staleExecution = if (execution.runtimeClaim == null) {
					execution.copy(
						status = LifecycleActionStatus.TERMINAL_FAILURE,
						failureCode = "SOURCE_ACTION_ACK_STALE",
					)
				} else {
					settleRejectedRuntimeExecution(
						sourcePlan,
						authorization,
						runtimeClaim,
						nowElapsed,
						wallTimeMs,
						SourceActionExecution(
							failedApplied(sourcePlan, nowElapsed, SourceApplyStatus.FAILED),
							LifecycleActionStatus.TERMINAL_FAILURE,
							"SOURCE_ACTION_ACK_STALE",
							runtimeClaim = runtimeClaim,
							captureAuthorization = authorization,
						),
					)
				}
				preserveCleanupRequiredAction(claimed, staleExecution, wallTimeMs, nowElapsed)
				staleExecution
			} else {
				execution
			}
		}
	}

	private suspend fun claimLifecycleAction(
		actionId: String,
		lease: LifecycleLeaseToken,
		nowElapsedNanos: Long,
	): LifecycleDesiredActionEntity? = database.withTransaction {
		requireLeaseInTransaction(lease)
		val dao = database.sourceSessionDao()
		val action = dao.lifecycleAction(actionId) ?: return@withTransaction null
		val session = dao.session(action.logicalTrackingId) ?: return@withTransaction null
		if (action.status !in setOf(
				LifecycleActionStatus.PENDING.name,
				LifecycleActionStatus.TEMPORARILY_ILLEGAL.name,
			) ||
			action.leaseGeneration != lease.generation ||
			action.bootId != lease.bootId ||
			session.currentManifestRevision != action.manifestRevision ||
			session.currentServiceRunId != action.serviceRunId ||
			session.lifecycleLeaseGeneration != lease.generation ||
			session.state in TERMINAL_OR_STOPPING_STATES
		) return@withTransaction null
		val claimed = action.copy(
			status = LifecycleActionStatus.APPLYING.name,
			attemptCount = action.attemptCount + 1,
			acknowledgedElapsedRealtimeNanos = null,
			failureCode = null,
			retryTrigger = null,
		)
		check(dao.updateLifecycleAction(claimed) == 1)
		claimed
	}

	private fun LifecycleDesiredActionEntity.toRuntimeClaim(source: SourceKind): SourceRuntimeClaim {
		check(sourceKind == source.stableCode) { "Lifecycle action belongs to another source" }
		return SourceRuntimeClaim(
			source = source,
			actionId = actionId,
			attemptCount = attemptCount,
			leaseGeneration = leaseGeneration,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
	}

	private suspend fun acknowledgeLifecycleAction(
		action: LifecycleDesiredActionEntity,
		execution: SourceActionExecution,
		lease: LifecycleLeaseToken,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
	): Boolean = try {
		database.withTransaction {
			if (!leaseIsCurrentInTransaction(lease)) return@withTransaction false
			val dao = database.sourceSessionDao()
			val current = dao.lifecycleAction(action.actionId) ?: return@withTransaction false
			val session = dao.session(action.logicalTrackingId) ?: return@withTransaction false
			if (current.status != LifecycleActionStatus.APPLYING.name ||
				current.attemptCount != action.attemptCount ||
				current.leaseGeneration != lease.generation ||
				session.currentManifestRevision != action.manifestRevision ||
				session.currentServiceRunId != action.serviceRunId ||
				session.lifecycleLeaseGeneration != lease.generation
			) return@withTransaction false
			val stopAcknowledgementMatches = execution.stopAck?.hasMembership(
				action.logicalTrackingId,
				action.serviceRunId,
			) != false
			val acknowledgedExecution = if (stopAcknowledgementMatches) execution else execution.copy(
				status = LifecycleActionStatus.CLEANUP_REQUIRED,
				failureCode = "STOP_ACK_MEMBERSHIP_MISMATCH",
				retryTrigger = RUNTIME_CLEANUP_RETRY,
			)
			val acknowledged = dao.updateLifecycleAction(
				current.copy(
					status = acknowledgedExecution.status.name,
					acknowledgedAtMs = wallTimeMs,
					acknowledgedElapsedRealtimeNanos = elapsedRealtimeNanos,
					failureCode = acknowledgedExecution.failureCode,
					retryTrigger = acknowledgedExecution.retryTrigger,
					sourceInstanceId = acknowledgedExecution.applied.sourceInstanceId?.value,
					registrationGeneration = acknowledgedExecution.applied.registrationGeneration,
				),
			) == 1
			if (acknowledged) {
				acknowledgedExecution.stopAck?.takeIf { stopAcknowledgementMatches }?.let { ack ->
					saveCompleteness(action.logicalTrackingId, action.serviceRunId, ack, wallTimeMs)
						.requireStoredCompleteness()
				}
			}
			acknowledged
		}
	} catch (_: CompletenessAuthenticationBlockedException) {
		false
	}

	/** Retains attempt N as a cleanup obligation even when its normal acknowledgement CAS is stale. */
	private suspend fun preserveCleanupRequiredAction(
		action: LifecycleDesiredActionEntity,
		execution: SourceActionExecution,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
	) {
		if (execution.status != LifecycleActionStatus.CLEANUP_REQUIRED) return
		database.withTransaction {
			val dao = database.sourceSessionDao()
			val current = dao.lifecycleAction(action.actionId) ?: return@withTransaction
			if (current.attemptCount != action.attemptCount ||
				current.leaseGeneration != action.leaseGeneration ||
				current.status !in setOf(
					LifecycleActionStatus.APPLYING.name,
					LifecycleActionStatus.CLEANUP_REQUIRED.name,
				)
			) return@withTransaction
			check(dao.updateLifecycleAction(
				current.copy(
					status = LifecycleActionStatus.CLEANUP_REQUIRED.name,
					acknowledgedAtMs = wallTimeMs,
					acknowledgedElapsedRealtimeNanos = elapsedRealtimeNanos,
					failureCode = SOURCE_RUNTIME_CLEANUP_PENDING,
					retryTrigger = RUNTIME_CLEANUP_RETRY,
					sourceInstanceId = execution.applied.sourceInstanceId?.value,
					registrationGeneration = execution.applied.registrationGeneration,
				),
			) == 1)
		}
	}

	private suspend fun persistStopIntent(
		session: LogicalTrackingSessionEntity,
		request: SessionStopRequest,
		lease: LifecycleLeaseToken,
	): BoundServiceRunTransition {
		val dao = database.sourceSessionDao()
		val manifestRevision = requireNotNull(session.currentManifestRevision)
		val intentRevision = requireNotNull(session.currentIntentRevision) + 1L
		val run = requireCurrentServiceRun(session)
		val manifestEnvelope = requireNotNull(
			verifiedManifest(session.logicalTrackingId, manifestRevision, run.serviceRunId),
		) { "Stop manifest integrity failed" }
		val deadline = request.elapsedRealtimeNanos + request.gracePeriodMs * NANOS_PER_MILLISECOND
		val intent = stopIntent(
			session,
			manifestRevision,
			intentRevision,
			request.reason,
			request.clockDomainId,
			request.elapsedRealtimeNanos,
			request.wallTimeMs,
			deadline,
		)
		dao.insertLifecycleIntent(intent)
		sourceBroker.markSessionDemandsRetiring(
			session.logicalTrackingId,
			request.clockDomainId,
			request.elapsedRealtimeNanos,
			request.wallTimeMs,
		)
		dao.insertLifecycleActions(
			stopActions(
				session,
				manifestEnvelope,
				intentRevision,
				run.serviceRunId,
				lease,
				request.wallTimeMs,
				request.elapsedRealtimeNanos,
			),
		)
		val updated = session.copy(
			state = SessionLifecycleState.STOPPING.name,
			lifecycleRevision = session.lifecycleRevision + 1L,
			currentIntentRevision = intentRevision,
			lifecycleLeaseGeneration = lease.generation,
			lifecycleBootId = lease.bootId,
			cutoffAtMs = request.wallTimeMs,
			cutoffElapsedNanos = request.elapsedRealtimeNanos,
		)
		check(dao.updateSession(updated) == 1)
		val updatedRun = run.copy(
			state = SessionLifecycleState.STOPPING.name,
			leaseGeneration = lease.generation,
			bootId = lease.bootId,
			runtimeAcknowledgement = LifecycleActionStatus.PENDING.name,
			runRevision = run.runRevision + 1L,
		)
		check(
			dao.updateServiceRun(
				updatedRun,
			) == 1,
		)
		return BoundServiceRunTransition(updated, run.serviceRunId, updatedRun.runRevision)
	}

	/**
	 * Starts a distinct durable stop attempt after a prior STOPPING attempt returned without a
	 * terminal drain. The original cutoff remains immutable; only retry authority and action
	 * generation move forward.
	 */
	private suspend fun persistStopRetryIntent(
		session: LogicalTrackingSessionEntity,
		request: SessionStopRequest,
		lease: LifecycleLeaseToken,
	): BoundServiceRunTransition {
		check(session.state == SessionLifecycleState.STOPPING.name)
		val dao = database.sourceSessionDao()
		val manifestRevision = requireNotNull(session.currentManifestRevision)
		val run = requireCurrentServiceRun(session)
		val manifestEnvelope = requireNotNull(
			verifiedManifest(session.logicalTrackingId, manifestRevision, run.serviceRunId),
		) { "STOPPING session manifest integrity failed" }
		val intentRevision = requireNotNull(session.currentIntentRevision) + 1L
		val cutoffAtMs = requireNotNull(session.cutoffAtMs)
		val cutoffElapsedNanos = requireNotNull(session.cutoffElapsedNanos)
		val deadline = request.elapsedRealtimeNanos + request.gracePeriodMs * NANOS_PER_MILLISECOND
		dao.insertLifecycleIntent(
			stopIntent(
				session,
				manifestRevision,
				intentRevision,
				request.reason,
				request.clockDomainId,
				cutoffElapsedNanos,
				cutoffAtMs,
				deadline,
			),
		)
		dao.insertLifecycleActions(
			stopActions(
				session,
				manifestEnvelope,
				intentRevision,
				run.serviceRunId,
				lease,
				request.wallTimeMs,
				request.elapsedRealtimeNanos,
			),
		)
		val updated = session.copy(
			lifecycleRevision = session.lifecycleRevision + 1L,
			currentIntentRevision = intentRevision,
			lifecycleLeaseGeneration = lease.generation,
			lifecycleBootId = lease.bootId,
		)
		check(dao.updateSession(updated) == 1)
		val updatedRun = run.copy(
			leaseGeneration = lease.generation,
			bootId = lease.bootId,
			runtimeAcknowledgement = LifecycleActionStatus.PENDING.name,
			runtimeFailureCode = null,
			runRevision = run.runRevision + 1L,
		)
		check(dao.updateServiceRun(updatedRun) == 1)
		return BoundServiceRunTransition(updated, run.serviceRunId, updatedRun.runRevision)
	}

	private suspend fun persistServiceRunSuspendIntent(
		session: LogicalTrackingSessionEntity,
		request: SessionSuspendRequest,
		lease: LifecycleLeaseToken,
	): BoundServiceRunTransition {
		val dao = database.sourceSessionDao()
		val manifestRevision = requireNotNull(session.currentManifestRevision)
		val intentRevision = requireNotNull(session.currentIntentRevision) + 1L
		val run = requireCurrentServiceRun(session)
		val supersededAuthorityReference = requireNotNull(
			dao.lifecycleIntent(
				session.logicalTrackingId,
				requireNotNull(session.currentIntentRevision),
			)?.sourceCallerAuthorityReference,
		) { "Suspend intent requires current source-caller authority" }
		val manifestEnvelope = requireNotNull(
			verifiedManifest(session.logicalTrackingId, manifestRevision, run.serviceRunId),
		) { "Suspend manifest integrity failed" }
		val cutoffElapsedRealtimeNanos = session.cutoffElapsedNanos ?: request.elapsedRealtimeNanos
		val cutoffWallTimeMs = session.cutoffAtMs ?: request.wallTimeMs
		val updated = session.copy(
			currentIntentRevision = intentRevision,
			lifecycleLeaseGeneration = lease.generation,
			lifecycleBootId = lease.bootId,
			cutoffAtMs = cutoffWallTimeMs,
			cutoffElapsedNanos = cutoffElapsedRealtimeNanos,
		)
		check(dao.updateSession(updated) == 1)
		val authorityReference = when (val guarded =
			sourceCallerDemandDispatcher.dispatchSession(
				SessionSourceDemandDispatchRequest(
					manifest = manifestEnvelope.manifest,
					bindings = manifestEnvelope.bindings,
					sessionMode = SessionMode.valueOf(session.sessionMode),
					startOrigin = SessionStartOrigin.valueOf(manifestEnvelope.manifest.startOrigin),
					mutation = SessionDemandMutation.AUTHORITY_ONLY,
					lifecycleLeaseGeneration = lease.generation,
					bootId = request.clockDomainId,
					elapsedRealtimeNanos = request.elapsedRealtimeNanos,
					wallTimeMs = request.wallTimeMs,
				),
			)
		) {
			is SessionSourceDemandDispatchResult.Permitted -> guarded.receipt.reference
			is SessionSourceDemandDispatchResult.Rejected ->
				throw SourceCallerDemandRejectedException(guarded.rejection.reason)
		}
		check(authorityReference.value != supersededAuthorityReference) {
			"Suspend authority refresh must issue a new reference"
		}
		val intent = SessionLifecycleIntentVersionEntity(
			logicalTrackingId = session.logicalTrackingId,
			intentRevision = intentRevision,
			manifestRevision = manifestRevision,
			desiredState = LifecycleDesiredState.ACTIVE.name,
			startOrigin = SessionStartOrigin.RECOVERY.name,
			requestBootId = request.clockDomainId,
			requestedElapsedRealtimeNanos = cutoffElapsedRealtimeNanos,
			requestedWallTimeMs = cutoffWallTimeMs,
			automationEpoch = session.automationEpoch,
			triggerId = null,
			triggerKind = null,
			triggerBootId = null,
			triggerObservedElapsedRealtimeNanos = null,
			triggerReceivedElapsedRealtimeNanos = null,
			triggerExpiresElapsedRealtimeNanos = null,
			stopReason = request.reason,
			stopDeadlineBootId = null,
			stopDeadlineElapsedRealtimeNanos = null,
			intentChecksum = stableLifecycleChecksum(
				session.logicalTrackingId,
				intentRevision,
				manifestRevision,
				LifecycleDesiredState.ACTIVE,
				request.reason,
				request.clockDomainId,
				cutoffElapsedRealtimeNanos,
				authorityReference.value,
			),
			sourceCallerAuthorityReference = authorityReference.value,
		)
		dao.insertLifecycleIntent(intent)
		sourceBroker.markSessionDemandsRetiring(
			session.logicalTrackingId,
			request.clockDomainId,
			cutoffElapsedRealtimeNanos,
			cutoffWallTimeMs,
		)
		dao.insertLifecycleActions(
			stopActions(
				session,
				manifestEnvelope,
				intentRevision,
				run.serviceRunId,
				lease,
				request.wallTimeMs,
				request.elapsedRealtimeNanos,
			),
		)
		val updatedRun = run.copy(
			state = SessionLifecycleState.STOPPING.name,
			leaseGeneration = lease.generation,
			bootId = lease.bootId,
			runtimeAcknowledgement = LifecycleActionStatus.PENDING.name,
			runRevision = run.runRevision + 1L,
		)
		check(
			dao.updateServiceRun(
				updatedRun,
			) == 1,
		)
		return BoundServiceRunTransition(
			updated,
			run.serviceRunId,
			updatedRun.runRevision,
			authorityReference,
		)
	}

	private fun stopIntent(
		session: LogicalTrackingSessionEntity,
		manifestRevision: Long,
		intentRevision: Long,
		reason: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		deadlineElapsedRealtimeNanos: Long,
	) = SessionLifecycleIntentVersionEntity(
		logicalTrackingId = session.logicalTrackingId,
		intentRevision = intentRevision,
		manifestRevision = manifestRevision,
		desiredState = LifecycleDesiredState.FINALIZED.name,
		startOrigin = SessionStartOrigin.POLICY_RECONCILIATION.name,
		requestBootId = bootId,
		requestedElapsedRealtimeNanos = elapsedRealtimeNanos,
		requestedWallTimeMs = wallTimeMs,
		automationEpoch = session.automationEpoch,
		triggerId = null,
		triggerKind = null,
		triggerBootId = null,
		triggerObservedElapsedRealtimeNanos = null,
		triggerReceivedElapsedRealtimeNanos = null,
		triggerExpiresElapsedRealtimeNanos = null,
		stopReason = reason,
		stopDeadlineBootId = bootId,
		stopDeadlineElapsedRealtimeNanos = deadlineElapsedRealtimeNanos,
		intentChecksum = stableLifecycleChecksum(
			session.logicalTrackingId,
			intentRevision,
			manifestRevision,
			LifecycleDesiredState.FINALIZED,
			reason,
			bootId,
			elapsedRealtimeNanos,
			deadlineElapsedRealtimeNanos,
		),
	)

	private suspend fun stopActions(
		session: LogicalTrackingSessionEntity,
		manifestEnvelope: VerifiedSessionManifest,
		intentRevision: Long,
		serviceRunId: String,
		lease: LifecycleLeaseToken,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
	): List<LifecycleDesiredActionEntity> {
		val manifest = manifestEnvelope.manifest
		val bindings = manifestEnvelope.bindings.filter {
			it.purpose == SessionManifestPurpose.SESSION_CAPTURE.name
		}
		var actionRevision = database.sourceSessionDao().maximumActionRevision(session.logicalTrackingId) + 1L
		return bindings.map { binding ->
			val currentActionRevision = actionRevision++
			LifecycleDesiredActionEntity(
				actionId = lifecycleActionIdentity(
					intentRevision,
					session.logicalTrackingId,
					serviceRunId,
					manifest.manifestRevision,
					currentActionRevision,
					LifecycleActionFamily.SOURCE_RUNTIME.name,
					binding.sourceKind,
					ACTION_DESIRED_STOPPED,
					session.desiredPlanRevision,
					manifest.sourcePolicyRevision,
					binding.consentEpoch,
					SessionStartOrigin.POLICY_RECONCILIATION.name,
					lease.bootId,
					lease.generation,
					wallTimeMs,
					elapsedRealtimeNanos,
				),
				logicalTrackingId = session.logicalTrackingId,
				serviceRunId = serviceRunId,
				manifestRevision = manifest.manifestRevision,
				actionRevision = currentActionRevision,
				actionFamily = LifecycleActionFamily.SOURCE_RUNTIME.name,
				sourceKind = binding.sourceKind,
				desiredState = ACTION_DESIRED_STOPPED,
				desiredPlanRevision = session.desiredPlanRevision,
				sourcePolicyRevision = manifest.sourcePolicyRevision,
				consentEpoch = binding.consentEpoch,
				startOrigin = SessionStartOrigin.POLICY_RECONCILIATION.name,
				bootId = lease.bootId,
				leaseGeneration = lease.generation,
				requestedAtMs = wallTimeMs,
				requestedElapsedRealtimeNanos = elapsedRealtimeNanos,
				status = LifecycleActionStatus.PENDING.name,
				attemptCount = 0,
				acknowledgedAtMs = null,
				acknowledgedElapsedRealtimeNanos = null,
				failureCode = null,
				retryTrigger = null,
				sourceInstanceId = null,
				registrationGeneration = null,
			)
		}
	}

	private suspend fun markStopActionsApplying(
		logicalTrackingId: String,
		serviceRunId: String,
		lease: LifecycleLeaseToken,
	): Boolean {
		val dao = database.sourceSessionDao()
		val rawActions = dao.rawLifecycleActionsForServiceRunBounded(
			serviceRunId,
			MAX_RUN_RETIREMENT_ACTIONS + 1,
		)
		if (rawActions.size > MAX_RUN_RETIREMENT_ACTIONS) return false
		val actions = ArrayList<LifecycleDesiredActionEntity>(rawActions.size)
		for ((index, rawAction) in rawActions.withIndex()) {
			if (index % RETIREMENT_CANCELLATION_STRIDE == 0) {
				currentCoroutineContext().ensureActive()
			}
			val action = rawAction.validatedOrNull() ?: return false
			if (action.logicalTrackingId != logicalTrackingId ||
				action.serviceRunId != serviceRunId
			) {
				return false
			}
			actions += action
		}
		actions
			.filter { action ->
				action.desiredState == ACTION_DESIRED_STOPPED &&
					action.status == LifecycleActionStatus.PENDING.name &&
					action.leaseGeneration == lease.generation
			}
			.forEach { action ->
				check(
					dao.updateLifecycleAction(
						action.copy(
							status = LifecycleActionStatus.APPLYING.name,
							attemptCount = action.attemptCount + 1,
						),
					) == 1,
				)
			}
		return true
	}

	private suspend fun acknowledgeStopAction(
		logicalTrackingId: String,
		serviceRunId: String,
		ack: SourceStopAck,
		lease: LifecycleLeaseToken,
		request: SessionStopRequest,
	) = acknowledgeStopAction(
		logicalTrackingId,
		serviceRunId,
		ack,
		lease,
		request.wallTimeMs,
		request.elapsedRealtimeNanos,
	)

	private suspend fun acknowledgeSuspendAction(
		logicalTrackingId: String,
		serviceRunId: String,
		ack: SourceStopAck,
		lease: LifecycleLeaseToken,
		request: SessionSuspendRequest,
	) = acknowledgeStopAction(
		logicalTrackingId,
		serviceRunId,
		ack,
		lease,
		request.wallTimeMs,
		request.elapsedRealtimeNanos,
	)

	private suspend fun acknowledgeStopAction(
		logicalTrackingId: String,
		serviceRunId: String,
		ack: SourceStopAck,
		lease: LifecycleLeaseToken,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
	) {
		val dao = database.sourceSessionDao()
		val action = dao.lifecycleActions(logicalTrackingId).lastOrNull { candidate ->
			candidate.serviceRunId == serviceRunId &&
				candidate.sourceKind == ack.source.stableCode &&
				candidate.desiredState == ACTION_DESIRED_STOPPED &&
				candidate.leaseGeneration == lease.generation &&
				candidate.status == LifecycleActionStatus.APPLYING.name
		} ?: return
		val membershipMatches = ack.hasMembership(logicalTrackingId, serviceRunId)
		val accepted = membershipMatches && ack.hasTerminalLifecycleSettlement()
		check(
			dao.updateLifecycleAction(
				action.copy(
					status = if (accepted) {
						LifecycleActionStatus.STOP_ACCEPTED.name
					} else {
						LifecycleActionStatus.CLEANUP_REQUIRED.name
					},
					acknowledgedAtMs = wallTimeMs,
					acknowledgedElapsedRealtimeNanos = elapsedRealtimeNanos,
					failureCode = when {
						accepted && ack.status == SourceStopStatus.COMPLETE -> null
						accepted -> "STOP_${ack.status.name}"
						!membershipMatches -> "STOP_ACK_MEMBERSHIP_MISMATCH"
						else -> "STOP_${ack.status.name}"
					},
					retryTrigger = if (accepted) null else RUNTIME_CLEANUP_RETRY,
					sourceInstanceId = ack.sourceInstanceId.value
						.takeIf { ack.registrationGeneration > 0L },
					registrationGeneration = ack.registrationGeneration.takeIf { it > 0L },
				),
			) == 1,
		)
	}

	suspend fun stop(request: SessionStopRequest): SessionStopResult {
		validateCanonicalBootId(request.clockDomainId)?.let {
			return SessionStopResult.InvalidIntent(it)
		}
		val lease = acquireLease(request.ownerToken)
			?: return SessionStopResult.Busy
		return try {
			val session = database.sourceSessionDao().activeSession() ?: return SessionStopResult.NoActiveSession
			val bound = database.withTransaction {
				requireLeaseInTransaction(lease)
				val current = requireNotNull(database.sourceSessionDao().session(session.logicalTrackingId))
				if (current.state == SessionLifecycleState.STOPPING.name) {
					persistStopRetryIntent(current, request, lease)
				} else {
					check(current.state in setOf(SessionLifecycleState.STARTING.name, SessionLifecycleState.ACTIVE.name,
						SessionLifecycleState.RECONFIGURING.name)) { "Terminal session cannot stop again" }
					persistStopIntent(current, request, lease)
				}
			}
			val cutoffSession = bound.session
			val stopActionsAuthenticated = database.withTransaction {
				requireLeaseInTransaction(lease)
				requireBoundServiceRun(bound, SessionLifecycleState.STOPPING)
				markStopActionsApplying(cutoffSession.logicalTrackingId, bound.serviceRunId, lease)
			}
			if (!stopActionsAuthenticated) {
				return SessionStopResult.InvalidIntent(
					"RUN_RETIREMENT_ACTION_AUTHENTICATION_BLOCKED",
				)
			}
			val cutoff = SessionCutoff(
				logicalTrackingId = cutoffSession.logicalTrackingId,
				elapsedRealtimeNanos = requireNotNull(cutoffSession.cutoffElapsedNanos),
				wallTimeMs = requireNotNull(cutoffSession.cutoffAtMs),
				deadlineElapsedRealtimeNanos = request.elapsedRealtimeNanos + request.gracePeriodMs * NANOS_PER_MILLISECOND,
			)
			val retirement = when (
				val read = runRetirementTargets(cutoffSession, bound.serviceRunId)
			) {
				is RunRetirementTargetRead.Ready -> read
				is RunRetirementTargetRead.Blocked ->
					return SessionStopResult.InvalidIntent(read.reason)
			}
			val retirementTargets = retirement.targets
			persistRunRetirementIntents(retirementTargets, cutoff, request.wallTimeMs)
			val sourceRetirements =
				retireRunSources(retirementTargets, cutoff, request.perSourceTimeoutMs)
			val acks = sourceRetirements.flatMap(RunSourceRetirementOutcome::acknowledgements)
			val drainAuthority = retirement.drainAuthority.copy(
				retirementClaims = sourceRetirements.flatMap(
					RunSourceRetirementOutcome::retirementClaims,
				),
			)
			val completenessAuthenticated = try {
				database.withTransaction {
					requireLeaseInTransaction(lease)
					acks.forEach { ack ->
						if (ack.hasMembership(cutoffSession.logicalTrackingId, bound.serviceRunId)) {
							saveCompleteness(
								cutoffSession.logicalTrackingId,
								bound.serviceRunId,
								ack,
								request.wallTimeMs,
							).requireStoredCompleteness()
						}
						acknowledgeStopAction(
							cutoffSession.logicalTrackingId,
							bound.serviceRunId,
							ack,
							lease,
							request,
						)
					}
					true
				}
			} catch (_: CompletenessAuthenticationBlockedException) {
				false
			}
			if (!completenessAuthenticated) {
				return SessionStopResult.InvalidIntent(
					"SOURCE_COMPLETENESS_AUTHENTICATION_BLOCKED",
				)
			}
			val observedHighWaterOrdinal = durableSourceHighWater()
			val finalOrdinal = freezeSettlementHighWater(
				bound,
				cutoff,
				observedHighWaterOrdinal,
				lease,
			)
			when (val drain = eventCoordinator.drainAvailable("${request.ownerToken}:projection")) {
				is CoordinatorDrainResult.Complete -> {
					if (drain.lastCompletedOrdinal < finalOrdinal) {
						return SessionStopResult.DrainPending(cutoffSession.logicalTrackingId, finalOrdinal)
					}
				}
				else -> return SessionStopResult.DrainPending(cutoffSession.logicalTrackingId, finalOrdinal)
			}
			val sourceDrain = drainSettledSourceProducts(
				cutoffSession.logicalTrackingId,
				bound.serviceRunId,
				cutoff,
				finalOrdinal,
				drainAuthority,
			)
			val incomplete = terminalRetirementIncomplete(
				acks,
				cutoffSession.logicalTrackingId,
				bound.serviceRunId,
			)
			if (!incomplete && sourceDrain is SettledSourceDrainBatch.Pending) {
				return SessionStopResult.DrainPending(
					logicalTrackingId = cutoffSession.logicalTrackingId,
					requiredOrdinal = finalOrdinal,
					source = sourceDrain.failedSource,
					reason = sourceDrain.reason,
					sourceResults = sourceDrain.results,
					sourceMemberships = sourceDrain.memberships,
				)
			}
			database.withTransaction {
				requireLeaseInTransaction(lease)
				sourceBroker.retireSessionDemands(
					cutoffSession.logicalTrackingId,
					lease.bootId,
					request.elapsedRealtimeNanos,
					request.wallTimeMs,
				)
				val serviceRun = requireBoundServiceRun(bound, SessionLifecycleState.STOPPING)
				val finalizing = requireNotNull(database.sourceSessionDao().session(cutoffSession.logicalTrackingId))
				check(finalizing.state == SessionLifecycleState.STOPPING.name)
				val partial = acks.any { it.status != SourceStopStatus.COMPLETE }
				if (incomplete) {
					check(database.sourceSessionDao().updateSession(
						finalizing.copy(
							lifecycleRevision = finalizing.lifecycleRevision + 1L,
							failureCode = "STOP_INCOMPLETE",
						),
					) == 1)
					check(database.sourceSessionDao().updateServiceRun(
						serviceRun.copy(
							runtimeAcknowledgement = LifecycleActionStatus.CLEANUP_REQUIRED.name,
							runtimeFailureCode = "STOP_INCOMPLETE",
							runRevision = serviceRun.runRevision + 1L,
						),
					) == 1)
					return@withTransaction
				}
				resolveCleanupRequiredActions(
					cutoffSession.logicalTrackingId,
					bound.serviceRunId,
					request.wallTimeMs,
					request.elapsedRealtimeNanos,
				)
				database.sourceSessionDao().updateSession(
					finalizing.copy(
						state = SessionLifecycleState.FINALIZED.name,
						lifecycleRevision = finalizing.lifecycleRevision + 1,
						finalAdmissionOrdinal = finalOrdinal,
						completedAtMs = request.wallTimeMs,
						failureCode = "STOP_PARTIAL".takeIf { partial },
						currentServiceRunId = null,
					),
				)
				database.sourceSessionDao().updateServiceRun(
						serviceRun.copy(
							state = SessionLifecycleState.FINALIZED.name,
							completedAtMs = request.wallTimeMs,
							completionReason = request.reason,
							runtimeAcknowledgement = LifecycleActionStatus.STOP_ACCEPTED.name,
							runtimeFailureCode = "STOP_PARTIAL".takeIf { partial },
							runRevision = serviceRun.runRevision + 1L,
						),
					)
			}
			if (incomplete) {
				return SessionStopResult.CleanupPending(cutoffSession.logicalTrackingId, finalOrdinal, acks)
			}
			SessionStopResult.Stopped(cutoffSession.logicalTrackingId, finalOrdinal, acks)
		} finally {
			releaseLease(lease)
		}
	}

	/**
	 * Ends one Android service run while preserving the durable logical session for watchdog
	 * recovery. Sources are fenced and projections drained before the run is marked closed.
	 */
	suspend fun suspendForRestart(request: SessionSuspendRequest): SessionSuspendResult {
		validateCanonicalBootId(request.clockDomainId)?.let {
			return SessionSuspendResult.InvalidIntent(it)
		}
		val lease = acquireLease(request.ownerToken)
			?: return SessionSuspendResult.Busy
		return try {
			val session = database.sourceSessionDao().activeSession()
				?: return SessionSuspendResult.NoActiveSession
			if (session.state != SessionLifecycleState.ACTIVE.name) {
				return SessionSuspendResult.NoActiveSession
			}
			val bound = database.withTransaction {
				requireLeaseInTransaction(lease)
				persistServiceRunSuspendIntent(session, request, lease)
			}
			val durableSession = bound.session
			val stopActionsAuthenticated = database.withTransaction {
				requireLeaseInTransaction(lease)
				requireBoundServiceRun(bound, SessionLifecycleState.STOPPING)
				markStopActionsApplying(durableSession.logicalTrackingId, bound.serviceRunId, lease)
			}
			if (!stopActionsAuthenticated) {
				return SessionSuspendResult.InvalidIntent(
					"RUN_RETIREMENT_ACTION_AUTHENTICATION_BLOCKED",
				)
			}
			val cutoff = SessionCutoff(
				logicalTrackingId = durableSession.logicalTrackingId,
				elapsedRealtimeNanos = requireNotNull(durableSession.cutoffElapsedNanos),
				wallTimeMs = requireNotNull(durableSession.cutoffAtMs),
				deadlineElapsedRealtimeNanos = request.elapsedRealtimeNanos +
					request.gracePeriodMs * NANOS_PER_MILLISECOND,
			)
			val retirement = when (
				val read = runRetirementTargets(durableSession, bound.serviceRunId)
			) {
				is RunRetirementTargetRead.Ready -> read
				is RunRetirementTargetRead.Blocked ->
					return SessionSuspendResult.InvalidIntent(read.reason)
			}
			val retirementTargets = retirement.targets
			persistRunRetirementIntents(retirementTargets, cutoff, request.wallTimeMs)
			val sourceRetirements =
				retireRunSources(retirementTargets, cutoff, request.perSourceTimeoutMs)
			val acks = sourceRetirements.flatMap(RunSourceRetirementOutcome::acknowledgements)
			val drainAuthority = retirement.drainAuthority.copy(
				retirementClaims = sourceRetirements.flatMap(
					RunSourceRetirementOutcome::retirementClaims,
				),
			)
			val completenessAuthenticated = try {
				database.withTransaction {
					requireLeaseInTransaction(lease)
					acks.forEach { ack ->
						if (ack.hasMembership(durableSession.logicalTrackingId, bound.serviceRunId)) {
							saveCompleteness(
								durableSession.logicalTrackingId,
								bound.serviceRunId,
								ack,
								request.wallTimeMs,
							).requireStoredCompleteness()
						}
						acknowledgeSuspendAction(
							durableSession.logicalTrackingId,
							bound.serviceRunId,
							ack,
							lease,
							request,
						)
					}
					true
				}
			} catch (_: CompletenessAuthenticationBlockedException) {
				false
			}
			if (!completenessAuthenticated) {
				return SessionSuspendResult.InvalidIntent(
					"SOURCE_COMPLETENESS_AUTHENTICATION_BLOCKED",
				)
			}
			val observedHighWaterOrdinal = durableSourceHighWater()
			val finalOrdinal = freezeSettlementHighWater(
				bound,
				cutoff,
				observedHighWaterOrdinal,
				lease,
			)
			when (val drain = eventCoordinator.drainAvailable("${request.ownerToken}:projection")) {
				is CoordinatorDrainResult.Complete -> if (drain.lastCompletedOrdinal < finalOrdinal) {
					return SessionSuspendResult.DrainPending(
						durableSession.logicalTrackingId,
						finalOrdinal,
						sourceCallerAuthorityReference = bound.sourceCallerAuthorityReference,
					)
				}
				else -> return SessionSuspendResult.DrainPending(
					durableSession.logicalTrackingId,
					finalOrdinal,
					sourceCallerAuthorityReference = bound.sourceCallerAuthorityReference,
				)
			}
			val sourceDrain = drainSettledSourceProducts(
				durableSession.logicalTrackingId,
				bound.serviceRunId,
				cutoff,
				finalOrdinal,
				drainAuthority,
			)
			val incomplete = terminalRetirementIncomplete(
				acks,
				durableSession.logicalTrackingId,
				bound.serviceRunId,
			)
			if (!incomplete && sourceDrain is SettledSourceDrainBatch.Pending) {
				return SessionSuspendResult.DrainPending(
					logicalTrackingId = durableSession.logicalTrackingId,
					requiredOrdinal = finalOrdinal,
					source = sourceDrain.failedSource,
					reason = sourceDrain.reason,
					sourceResults = sourceDrain.results,
					sourceMemberships = sourceDrain.memberships,
					sourceCallerAuthorityReference = bound.sourceCallerAuthorityReference,
				)
			}
			database.withTransaction {
				requireLeaseInTransaction(lease)
				val serviceRun = requireBoundServiceRun(bound, SessionLifecycleState.STOPPING)
				val latest = requireNotNull(database.sourceSessionDao().session(durableSession.logicalTrackingId))
				check(latest.state == SessionLifecycleState.ACTIVE.name)
				val partial = acks.any { it.status != SourceStopStatus.COMPLETE }
				if (incomplete) {
					check(database.sourceSessionDao().updateSession(
						latest.copy(
							lifecycleRevision = latest.lifecycleRevision + 1L,
							failureCode = "STOP_INCOMPLETE",
						),
					) == 1)
					check(database.sourceSessionDao().updateServiceRun(
						serviceRun.copy(
							runtimeAcknowledgement = LifecycleActionStatus.CLEANUP_REQUIRED.name,
							runtimeFailureCode = "STOP_INCOMPLETE",
							runRevision = serviceRun.runRevision + 1L,
						),
					) == 1)
					return@withTransaction
				}
				sourceBroker.retireSessionDemands(
					durableSession.logicalTrackingId,
					lease.bootId,
					cutoff.elapsedRealtimeNanos,
					cutoff.wallTimeMs,
					retireCallerAuthority = false,
				)
				resolveCleanupRequiredActions(
					durableSession.logicalTrackingId,
					bound.serviceRunId,
					request.wallTimeMs,
					request.elapsedRealtimeNanos,
				)
				database.sourceSessionDao().updateSession(
					latest.copy(
						currentServiceRunId = null,
						lifecycleRevision = latest.lifecycleRevision + 1L,
						failureCode = "STOP_PARTIAL".takeIf { partial },
						cutoffAtMs = null,
						cutoffElapsedNanos = null,
						finalAdmissionOrdinal = null,
					),
				)
				database.sourceSessionDao().updateServiceRun(
						serviceRun.copy(
							state = SessionLifecycleState.FINALIZED.name,
							completedAtMs = request.wallTimeMs,
							completionReason = request.reason,
							runtimeAcknowledgement = LifecycleActionStatus.STOP_ACCEPTED.name,
							runtimeFailureCode = "STOP_PARTIAL".takeIf { partial },
							runRevision = serviceRun.runRevision + 1L,
						),
					)
			}
			if (incomplete) {
				return SessionSuspendResult.CleanupPending(
					durableSession.logicalTrackingId,
					finalOrdinal,
					acks,
					bound.sourceCallerAuthorityReference,
				)
			}
			SessionSuspendResult.Suspended(
				durableSession.logicalTrackingId,
				finalOrdinal,
				acks,
				acks.any { it.status != SourceStopStatus.COMPLETE },
				bound.sourceCallerAuthorityReference,
			)
		} catch (rejection: SourceCallerDemandRejectedException) {
			if (rejection.disposition == TrackingStartFailureDisposition.RETRYABLE) {
				SessionSuspendResult.Retryable(rejection.failureCode)
			} else {
				SessionSuspendResult.InvalidIntent(rejection.failureCode)
			}
		} finally {
			releaseLease(lease)
		}
	}

	private suspend fun startSource(
		plan: SourcePlan,
		sink: SourceEventSink,
		authorization: CaptureAuthorization,
		runtimeClaim: SourceRuntimeClaim,
		elapsedNanos: Long,
		wallTimeMs: Long,
	): SourceActionExecution {
		if (!plan.enabled) {
			return SourceActionExecution(
				disabledApplied(plan, elapsedNanos),
				LifecycleActionStatus.STOP_ACCEPTED,
			)
		}
		if (plan.source !in runtimes.registeredSources()) {
			return SourceActionExecution(
				failedApplied(plan, elapsedNanos, SourceApplyStatus.BLOCKED),
				LifecycleActionStatus.TERMINAL_FAILURE,
				"SOURCE_RUNTIME_UNAVAILABLE",
			)
		}
		val execution = try {
			runtimes.start(runtimeClaim, plan, registrationFencedSink(sink, plan.source))
				.toExecution()
				.copy(runtimeClaim = runtimeClaim, captureAuthorization = authorization)
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			return settleRejectedRuntimeExecution(
				plan,
				authorization,
				runtimeClaim,
				elapsedNanos,
				wallTimeMs,
				SourceActionExecution(
					failedApplied(plan, elapsedNanos, SourceApplyStatus.FAILED),
					LifecycleActionStatus.TERMINAL_FAILURE,
					"SOURCE_START_EXCEPTION",
					runtimeClaim = runtimeClaim,
					captureAuthorization = authorization,
				),
			)
		}
		if (execution.status !in LIVE_RUNTIME_ACTION_STATUSES) {
			return settleRejectedRuntimeExecution(
				plan,
				authorization,
				runtimeClaim,
				elapsedNanos,
				wallTimeMs,
				execution,
			)
		}
		return if (captureAuthorizationCurrent(authorization, plan.source)) execution else {
			settleRejectedRuntimeExecution(
				plan,
				authorization,
				runtimeClaim,
				elapsedNanos,
				wallTimeMs,
				SourceActionExecution(
				failedApplied(
					plan,
					elapsedNanos,
					SourceApplyStatus.FAILED,
				),
				LifecycleActionStatus.TERMINAL_FAILURE,
				"SOURCE_AUTHORIZATION_STALE",
				runtimeClaim = runtimeClaim,
				captureAuthorization = authorization,
				),
			)
		}
	}

	private suspend fun reconfigureSource(
		plan: SourcePlan,
		sink: SourceEventSink,
		authorization: CaptureAuthorization,
		runtimeClaim: SourceRuntimeClaim,
		elapsedNanos: Long,
		wallTimeMs: Long,
	): SourceActionExecution {
		if (!plan.enabled && plan.source !in runtimes.registeredSources()) {
			return SourceActionExecution(
				disabledApplied(plan, elapsedNanos),
				LifecycleActionStatus.STOP_ACCEPTED,
			)
		}
		if (plan.source !in runtimes.registeredSources()) {
			return SourceActionExecution(
				failedApplied(plan, elapsedNanos, SourceApplyStatus.BLOCKED),
				LifecycleActionStatus.TERMINAL_FAILURE,
				"SOURCE_RUNTIME_UNAVAILABLE",
			)
		}
		val execution = try {
			runtimes.reconfigure(
				runtimeClaim,
				plan,
				registrationFencedSink(sink, plan.source),
			).toExecution(plan.enabled)
				.copy(runtimeClaim = runtimeClaim, captureAuthorization = authorization)
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			return settleRejectedRuntimeExecution(
				plan,
				authorization,
				runtimeClaim,
				elapsedNanos,
				wallTimeMs,
				SourceActionExecution(
					failedApplied(plan, elapsedNanos, SourceApplyStatus.FAILED),
					LifecycleActionStatus.TERMINAL_FAILURE,
					"SOURCE_RECONFIGURE_EXCEPTION",
					runtimeClaim = runtimeClaim,
					captureAuthorization = authorization,
				),
			)
		}
		if (execution.status !in LIVE_RUNTIME_ACTION_STATUSES) {
			return settleRejectedRuntimeExecution(
				plan,
				authorization,
				runtimeClaim,
				elapsedNanos,
				wallTimeMs,
				execution,
			)
		}
		return if (!plan.enabled || captureAuthorizationCurrent(authorization, plan.source)) execution else {
			settleRejectedRuntimeExecution(
				plan,
				authorization,
				runtimeClaim,
				elapsedNanos,
				wallTimeMs,
				SourceActionExecution(
				failedApplied(
					plan,
					elapsedNanos,
					SourceApplyStatus.FAILED,
				),
				LifecycleActionStatus.TERMINAL_FAILURE,
				"SOURCE_AUTHORIZATION_STALE",
				runtimeClaim = runtimeClaim,
				captureAuthorization = authorization,
				),
			)
		}
	}

	/**
	 * Settles every runtime result that did not establish an accepted live state. Attempt N may
	 * publish Android work before returning a failure, so N is not retryable or terminal until its
	 * exact claim is either released or durably marked for cleanup.
	 */
	private suspend fun settleRejectedRuntimeExecution(
		plan: SourcePlan,
		authorization: CaptureAuthorization,
		runtimeClaim: SourceRuntimeClaim,
		elapsedNanos: Long,
		wallTimeMs: Long,
		execution: SourceActionExecution,
	): SourceActionExecution {
		check(execution.status !in LIVE_RUNTIME_ACTION_STATUSES)
		val shutdown = shutdownFailedMaterialization(
			plan,
			authorization,
			runtimeClaim,
			elapsedNanos,
			wallTimeMs,
		)
		val predecessorCleanupRequired =
			execution.stopAck?.hasIncompleteTerminalRetirement() == true
		val cleanupRequired = shutdown.cleanupRequired || predecessorCleanupRequired
		return execution.copy(
			applied = if (shutdown.closed && !predecessorCleanupRequired) {
				execution.applied.copy(status = SourceApplyStatus.ROLLED_BACK)
			} else {
				execution.applied
			},
			status = if (cleanupRequired) {
				LifecycleActionStatus.CLEANUP_REQUIRED
			} else {
				execution.status
			},
			failureCode = if (cleanupRequired) {
				SOURCE_RUNTIME_CLEANUP_PENDING
			} else {
				execution.failureCode
			},
			retryTrigger = if (cleanupRequired) {
				RUNTIME_CLEANUP_RETRY
			} else {
				execution.retryTrigger
			},
			stopAck = shutdown.stopAck ?: execution.stopAck,
			runtimeClaim = runtimeClaim,
			captureAuthorization = authorization,
		)
	}

	private suspend fun shutdownFailedMaterialization(
		plan: SourcePlan,
		authorization: CaptureAuthorization,
		runtimeClaim: SourceRuntimeClaim,
		elapsedNanos: Long,
		wallTimeMs: Long,
	): FailedMaterializationShutdown {
		val cutoff = SessionCutoff(
			logicalTrackingId = authorization.logicalTrackingId,
			elapsedRealtimeNanos = elapsedNanos,
			wallTimeMs = wallTimeMs,
			deadlineElapsedRealtimeNanos = elapsedNanos +
				ROLLBACK_QUIESCE_TIMEOUT_MS * NANOS_PER_MILLISECOND,
		)
		val shutdown = withTimeoutOrNull(ROLLBACK_QUIESCE_TIMEOUT_MS) {
			runCatchingNonCancellation { runtimes.shutdownIfOwned(runtimeClaim, cutoff) }
				.getOrElse { OwnedSourceShutdown.Incomplete(provider = null, stopAck = null) }
		} ?: OwnedSourceShutdown.Incomplete(provider = null, stopAck = null)
		val provider = when (shutdown) {
			is OwnedSourceShutdown.Released -> shutdown.provider
			is OwnedSourceShutdown.Incomplete -> shutdown.provider
			OwnedSourceShutdown.NotOwned -> null
		}
		val acknowledgement = when (shutdown) {
			is OwnedSourceShutdown.Released -> shutdown.stopAck
			is OwnedSourceShutdown.Incomplete -> shutdown.stopAck
			OwnedSourceShutdown.NotOwned -> null
		}
		val providerMatchesAcknowledgement = provider == null || acknowledgement?.providerKeyOrNull() == null ||
			provider == acknowledgement.providerKeyOrNull()
		val acknowledgementMembershipMatches = acknowledgement == null || acknowledgement.hasMembership(
			authorization.logicalTrackingId,
			authorization.serviceRunId,
		)
		val trustedAcknowledgement = acknowledgement?.takeIf { ack ->
			ack.source == plan.source && providerMatchesAcknowledgement &&
				acknowledgementMembershipMatches
		}
		val completenessStored = trustedAcknowledgement == null ||
			saveOwnedShutdownCompleteness(authorization, trustedAcknowledgement, wallTimeMs)
		return FailedMaterializationShutdown(
			closed = shutdown is OwnedSourceShutdown.Released && providerMatchesAcknowledgement &&
				acknowledgementMembershipMatches && completenessStored &&
				(acknowledgement == null || acknowledgement.source == plan.source),
			cleanupRequired = shutdown is OwnedSourceShutdown.Incomplete || !providerMatchesAcknowledgement ||
				!acknowledgementMembershipMatches || !completenessStored ||
				(acknowledgement != null && acknowledgement.source != plan.source),
			stopAck = trustedAcknowledgement.takeIf { completenessStored },
		)
	}

	private fun registrationFencedSink(
		delegate: SourceEventSink,
		source: SourceKind,
	) = SourceEventSink { candidate ->
		if (candidate.source != source) {
			SourceAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.SOURCE_POLICY_STALE)
		} else {
			// Purpose, consent, policy, and manifest are resolved durably at the observation's
			// boot/elapsed time. A mutable session sink must never relabel a delayed callback.
			delegate.admit(candidate)
		}
	}

	private suspend fun rollbackStalePolicySources(
		plan: AcquisitionPlanRevision,
		executions: List<SourceActionExecution>,
		elapsedNanos: Long,
		wallTimeMs: Long,
	): RuntimeRollbackOutcome {
		requireNotNull(plan.sourcePolicyRevision)
		var cleanupRequired = false
		val rolledBack = executions.map { execution ->
			val state = execution.applied
			val sourcePlan = requireNotNull(plan.plans[state.source])
			if (!sourcePlan.enabled) state else {
				val claim = execution.runtimeClaim
				val authorization = execution.captureAuthorization
				val shutdown = if (claim != null && authorization != null &&
					state.source in runtimes.registeredSources()
				) {
					shutdownFailedMaterialization(
						sourcePlan,
						authorization,
						claim,
						elapsedNanos,
						wallTimeMs,
					)
				} else {
					FailedMaterializationShutdown(closed = false, cleanupRequired = false, stopAck = null)
				}
				if (shutdown.cleanupRequired && claim != null) {
					cleanupRequired = true
					database.sourceSessionDao().lifecycleAction(claim.actionId)?.let { action ->
						preserveCleanupRequiredAction(
							action,
							execution.copy(
								status = LifecycleActionStatus.CLEANUP_REQUIRED,
								failureCode = SOURCE_RUNTIME_CLEANUP_PENDING,
								retryTrigger = RUNTIME_CLEANUP_RETRY,
								stopAck = shutdown.stopAck ?: execution.stopAck,
							),
							wallTimeMs,
							elapsedNanos,
						)
					}
				}
				failedApplied(
					sourcePlan,
					elapsedNanos,
					if (shutdown.closed) SourceApplyStatus.ROLLED_BACK else SourceApplyStatus.FAILED,
				)
			}
		}
		rolledBack.forEach { state -> planStore.saveApplied(state, wallTimeMs) }
		return RuntimeRollbackOutcome(rolledBack, cleanupRequired)
	}

	/**
	 * Resolves only provider joins that can be proven to belong to this exact service run.
	 * Historical accepted or attempted claims cover a source omitted by a later plan; a later
	 * accepted stop suppresses that predecessor. Unrelated process-local runtimes are deliberately
	 * excluded. APPLYING is ownership-bearing because the runtime may cross a provider side-effect
	 * boundary before cancellation leaves the durable action unsettled.
	 */
	@Suppress("ComplexCondition", "LongMethod", "ReturnCount")
	private suspend fun runRetirementTargets(
		session: LogicalTrackingSessionEntity,
		serviceRunId: String,
	): RunRetirementTargetRead {
		val dao = database.sourceSessionDao()
		val rawActions = dao.rawLifecycleActionsForServiceRunBounded(
			serviceRunId,
			MAX_RUN_RETIREMENT_ACTIONS + 1,
		)
		if (rawActions.size > MAX_RUN_RETIREMENT_ACTIONS) {
			return RunRetirementTargetRead.Blocked("RUN_RETIREMENT_ACTION_HISTORY_OVERFLOW")
		}
		val actions = ArrayList<LifecycleDesiredActionEntity>(rawActions.size)
		for ((index, rawAction) in rawActions.withIndex()) {
			if (index % RETIREMENT_CANCELLATION_STRIDE == 0) {
				currentCoroutineContext().ensureActive()
			}
			val action = rawAction.validatedOrNull()
				?: return RunRetirementTargetRead.Blocked(
					"RUN_RETIREMENT_ACTION_AUTHENTICATION_BLOCKED",
				)
			if (action.logicalTrackingId != session.logicalTrackingId ||
				action.serviceRunId != serviceRunId
			) {
				return RunRetirementTargetRead.Blocked(
					"RUN_RETIREMENT_ACTION_AUTHENTICATION_BLOCKED",
				)
			}
			actions += action
		}
		val currentRevision = session.currentManifestRevision
			?: return RunRetirementTargetRead.Blocked(
				"RUN_RETIREMENT_CURRENT_MANIFEST_MISSING",
			)
		val terminalOwnershipStatuses = setOf(
			LifecycleActionStatus.APPLYING.name,
			LifecycleActionStatus.START_ACCEPTED.name,
			LifecycleActionStatus.CLEANUP_REQUIRED.name,
			LifecycleActionStatus.STOP_ACCEPTED.name,
		)
		val retirementAuthorityActions = actions.filter { action ->
			action.attemptCount > 0 &&
				action.desiredState in setOf(ACTION_DESIRED_STARTED, ACTION_DESIRED_STOPPED) &&
				action.status in terminalOwnershipStatuses
		}
		val runManifestRevisions = when (
			val read = runManifestRevisions(
				logicalTrackingId = session.logicalTrackingId,
				serviceRunId = serviceRunId,
			)
		) {
			is RunManifestRevisionRead.Ready -> read.revisions
			is RunManifestRevisionRead.Blocked ->
				return RunRetirementTargetRead.Blocked(read.reason)
		}
		val referencedManifestRevisions = buildSet {
			add(currentRevision)
			retirementAuthorityActions.forEach { action -> add(action.manifestRevision) }
		}
		if (!runManifestRevisions.containsAll(referencedManifestRevisions)) {
			return RunRetirementTargetRead.Blocked(
				"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED",
			)
		}
		val envelopesByRevision = when (val read = retirementManifestEnvelopes(
			logicalTrackingId = session.logicalTrackingId,
			serviceRunId = serviceRunId,
			manifestRevisions = runManifestRevisions,
		)) {
			is RunRetirementManifestRead.Ready -> read.envelopesByRevision
			is RunRetirementManifestRead.Blocked ->
				return RunRetirementTargetRead.Blocked(read.reason)
		}
		val current = envelopesByRevision[currentRevision]
			?: return RunRetirementTargetRead.Blocked(
				"RUN_RETIREMENT_CURRENT_MANIFEST_MISSING",
			)
		val actionManifestMismatch = retirementAuthorityActions.any { action ->
			val manifest = envelopesByRevision[action.manifestRevision]?.manifest
			manifest == null ||
				action.desiredPlanRevision != manifest.acquisitionPlanRevision ||
				action.sourcePolicyRevision != manifest.sourcePolicyRevision
		}
		if (actionManifestMismatch) {
			return RunRetirementTargetRead.Blocked("RUN_RETIREMENT_ACTION_MANIFEST_MISMATCH")
		}
		currentCoroutineContext().ensureActive()
		val productDrainBindings = envelopesByRevision.values.asSequence()
			.flatMap { envelope -> envelope.bindings.asSequence() }
			.filter { binding ->
				binding.purpose == SessionManifestPurpose.SESSION_CAPTURE.name &&
					binding.persistenceEligible
			}
			.groupBy(SessionManifestSourceEntity::sourceKind)
			.mapValues { (_, bindings) ->
				bindings.distinctBy { binding ->
					listOf(
						binding.manifestRevision,
						binding.sourceKind,
						binding.purpose,
						binding.outputDestination,
						binding.writerOwner,
						binding.writerOwnerGeneration,
						binding.writerProjectionId,
						binding.writerProjectionVersion,
						binding.writerBindingGeneration,
					)
				}.sortedWith(
					compareBy(
						SessionManifestSourceEntity::manifestRevision,
						SessionManifestSourceEntity::sourceKind,
						SessionManifestSourceEntity::purpose,
					),
				)
			}
		if (productDrainBindings.keys.any { sourceKind ->
				SourceKind.entries.none { source -> source.stableCode == sourceKind }
			}
		) {
			return RunRetirementTargetRead.Blocked("RUN_RETIREMENT_SOURCE_KIND_INVALID")
		}
		val currentCaptureSources = current.bindings.asSequence()
			.filter { binding ->
				binding.purpose == SessionManifestPurpose.SESSION_CAPTURE.name &&
					binding.persistenceEligible
			}
			.map(SessionManifestSourceEntity::sourceKind)
			.toMutableSet()
		val latestOwnershipOutcomeBySource = retirementAuthorityActions.asSequence()
			.groupBy { action -> requireNotNull(action.sourceKind) }
			.mapValues { (_, sourceActions) -> sourceActions.maxBy(LifecycleDesiredActionEntity::actionRevision) }
		latestOwnershipOutcomeBySource.forEach { (sourceKind, latest) ->
			if (latest.status != LifecycleActionStatus.STOP_ACCEPTED.name) currentCaptureSources += sourceKind
		}
		val targets = mutableListOf<RunRetirementTarget>()
		for (sourceCode in currentCaptureSources.sorted()) {
			currentCoroutineContext().ensureActive()
			val source = SourceKind.entries.singleOrNull { candidate ->
				candidate.stableCode == sourceCode
			} ?: return RunRetirementTargetRead.Blocked("RUN_RETIREMENT_SOURCE_KIND_INVALID")
			val claims = retirementAuthorityActions.asSequence()
				.filter { action ->
					action.sourceKind == sourceCode &&
						action.status in setOf(
							LifecycleActionStatus.APPLYING.name,
							LifecycleActionStatus.START_ACCEPTED.name,
							LifecycleActionStatus.CLEANUP_REQUIRED.name,
						)
				}
				.sortedByDescending(LifecycleDesiredActionEntity::actionRevision)
				.map { action ->
					RunRetirementClaim(
						action = action,
						runtimeClaim = action.toRuntimeClaim(source),
						provider = action.sourceInstanceId?.let { instanceId ->
							action.registrationGeneration?.let { generation ->
								SourceProviderKey(
									SourceInstanceId(instanceId),
									generation,
								)
							}
						},
						manifestEnvelope = requireNotNull(
							envelopesByRevision[action.manifestRevision],
						),
					)
				}
				.toList()
			val captureBindings = buildList {
				addAll(current.bindings)
				claims.forEach { claim -> addAll(claim.manifestEnvelope.bindings) }
			}.asSequence()
				.filter { binding ->
					binding.sourceKind == sourceCode &&
						binding.purpose == SessionManifestPurpose.SESSION_CAPTURE.name &&
						binding.persistenceEligible
				}
				.distinctBy { binding ->
					listOf(
						binding.manifestRevision,
						binding.sourceKind,
						binding.purpose,
					)
				}
				.toList()
			if (captureBindings.isEmpty()) {
				return RunRetirementTargetRead.Blocked("RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED")
			}
			targets += RunRetirementTarget(
				source,
				serviceRunId,
				claims,
				actions,
				captureBindings,
			)
		}
		return RunRetirementTargetRead.Ready(
			targets = targets,
			drainAuthority = SourceProductDrainAuthority(productDrainBindings),
		)
	}

	@Suppress("ReturnCount")
	private suspend fun runManifestRevisions(
		logicalTrackingId: String,
		serviceRunId: String,
	): RunManifestRevisionRead {
		val revisions = ArrayList<Long>()
		var afterRevision = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			val rawPage = database.sourceSessionDao().rawManifestsForServiceRunAfterRevision(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				afterRevision = afterRevision,
				limit = RUN_MANIFEST_REVISION_PAGE_SIZE,
			)
			if (rawPage.isEmpty()) break
			for (raw in rawPage) {
				val manifest = raw.validatedOrNull()
					?: return RunManifestRevisionRead.Blocked(
						"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED",
					)
				if (
					manifest.logicalTrackingId != logicalTrackingId ||
					manifest.serviceRunId != serviceRunId ||
					manifest.manifestRevision <= afterRevision ||
					revisions.lastOrNull()?.let { prior ->
						manifest.manifestRevision <= prior
					} == true
				) {
					return RunManifestRevisionRead.Blocked(
						"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED",
					)
				}
				revisions += manifest.manifestRevision
				if (revisions.size > MAX_RUN_DRAIN_MANIFEST_REVISIONS) {
					return RunManifestRevisionRead.Blocked(
						"RUN_RETIREMENT_MANIFEST_HISTORY_OVERFLOW",
					)
				}
			}
			afterRevision = revisions.last()
			if (rawPage.size < RUN_MANIFEST_REVISION_PAGE_SIZE) break
		}
		return if (revisions.isEmpty()) {
			RunManifestRevisionRead.Blocked("RUN_RETIREMENT_CURRENT_MANIFEST_MISSING")
		} else {
			RunManifestRevisionRead.Ready(revisions)
		}
	}

	@Suppress("ComplexCondition", "LongMethod", "ReturnCount")
	private suspend fun retirementManifestEnvelopes(
		logicalTrackingId: String,
		serviceRunId: String,
		manifestRevisions: List<Long>,
	): RunRetirementManifestRead {
		val dao = database.sourceSessionDao()
		val envelopesByRevision = linkedMapOf<Long, VerifiedSessionManifest>()
		for (revisionChunk in manifestRevisions.chunked(RETIREMENT_MANIFEST_QUERY_CHUNK)) {
			currentCoroutineContext().ensureActive()
			val rawManifests = dao.rawManifestsForServiceRunRevisions(
				serviceRunId,
				revisionChunk,
				revisionChunk.size + 1,
			)
			if (rawManifests.size > revisionChunk.size) {
				return RunRetirementManifestRead.Blocked(
					"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED",
				)
			}
			val manifests = rawManifests.map { raw ->
				raw.validatedOrNull()
					?: return RunRetirementManifestRead.Blocked(
						"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED",
					)
			}
			if (
				manifests.any { manifest ->
					manifest.logicalTrackingId != logicalTrackingId ||
						manifest.serviceRunId != serviceRunId ||
						manifest.manifestRevision !in revisionChunk
				}
			) {
				return RunRetirementManifestRead.Blocked(
					"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED",
				)
			}
			val bindingLimit = Math.addExact(
				Math.multiplyExact(revisionChunk.size, MAX_RUN_RETIREMENT_MANIFEST_SOURCES),
				1,
			)
			val rawBindings = dao.rawManifestSourcesForRevisions(
				logicalTrackingId,
				revisionChunk,
				bindingLimit,
			)
			if (rawBindings.size >= bindingLimit) {
				return RunRetirementManifestRead.Blocked(
					"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED",
				)
			}
			val bindingsByRevision = linkedMapOf<Long, MutableList<SessionManifestSourceEntity>>()
			for ((index, rawBinding) in rawBindings.withIndex()) {
				if (index % RETIREMENT_CANCELLATION_STRIDE == 0) {
					currentCoroutineContext().ensureActive()
				}
				val binding = rawBinding.validatedOrNull()
					?: return RunRetirementManifestRead.Blocked(
						"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED",
					)
				if (binding.logicalTrackingId != logicalTrackingId ||
					binding.manifestRevision !in revisionChunk
				) {
					return RunRetirementManifestRead.Blocked(
						"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED",
					)
				}
				val bindings = bindingsByRevision.getOrPut(binding.manifestRevision) {
					mutableListOf()
				}
				bindings += binding
				if (bindings.size > MAX_RUN_RETIREMENT_MANIFEST_SOURCES) {
					return RunRetirementManifestRead.Blocked(
						"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED",
					)
				}
			}
			if (bindingsByRevision.keys.any { revision ->
					manifests.none { manifest -> manifest.manifestRevision == revision }
				}
			) {
				return RunRetirementManifestRead.Blocked(
					"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED",
				)
			}
			for ((index, manifest) in manifests.withIndex()) {
				if (index % RETIREMENT_CANCELLATION_STRIDE == 0) {
					currentCoroutineContext().ensureActive()
				}
				val bindings = bindingsByRevision[manifest.manifestRevision].orEmpty()
				if (!SessionManifestIntegrity.verify(manifest, bindings) ||
					envelopesByRevision.put(
						manifest.manifestRevision,
						VerifiedSessionManifest(manifest, bindings),
					) != null
				) {
					return RunRetirementManifestRead.Blocked(
						"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED",
					)
				}
			}
		}
		if (envelopesByRevision.keys != manifestRevisions.toSet()) {
			return RunRetirementManifestRead.Blocked(
				"RUN_RETIREMENT_MANIFEST_INTEGRITY_FAILED",
			)
		}
		return RunRetirementManifestRead.Ready(envelopesByRevision)
	}

	private suspend fun retireRunSources(
		targets: List<RunRetirementTarget>,
		cutoff: SessionCutoff,
		perSourceTimeoutMs: Long,
	): List<RunSourceRetirementOutcome> = coroutineScope {
		targets.map { target ->
			async {
				withTimeoutOrNull(perSourceTimeoutMs) {
					retireRunSource(target, cutoff)
				} ?: RunSourceRetirementOutcome(
					target.source,
					listOf(
						runRetirementAck(
							target.source,
							cutoff.logicalTrackingId,
							target.serviceRunId,
							SourceStopStatus.TIMED_OUT,
						),
					),
					retirementClaims = target.drainRetirementClaims(emptySet()),
				)
			}
		}.awaitAll()
	}

	private suspend fun persistRunRetirementIntents(
		targets: List<RunRetirementTarget>,
		cutoff: SessionCutoff,
		updatedAtMs: Long,
	) = database.withTransaction {
		var processedClaims = 0
		for (target in targets) {
			for (owned in target.claims) {
				if (processedClaims % RETIREMENT_CANCELLATION_STRIDE == 0) {
					currentCoroutineContext().ensureActive()
				}
				processedClaims += 1
				val provider = owned.provider ?: continue
				database.sourceSessionDao().insertRunRetirementIntent(
					SourceRunRetirementEntity(
						logicalTrackingId = cutoff.logicalTrackingId,
						serviceRunId = target.serviceRunId,
						sourceKind = target.source.stableCode,
						sourceInstanceId = provider.sourceInstanceId.value,
						registrationGeneration = provider.registrationGeneration,
						actionId = owned.runtimeClaim.actionId,
						attemptCount = owned.runtimeClaim.attemptCount,
						leaseGeneration = owned.runtimeClaim.leaseGeneration,
						cutoffElapsedRealtimeNanos = cutoff.elapsedRealtimeNanos,
						cutoffWallTimeMs = cutoff.wallTimeMs,
						state = SourceRunRetirementEntity.STATE_REQUESTED,
						appliedRevision = null,
						callbackEntryBarrierSequence = null,
						lastSourceSequence = null,
						lastAdmissionOrdinal = null,
						failedAdmissionCount = null,
						unresolvedSequenceStart = null,
						unresolvedSequenceEnd = null,
						registrationRemovalOutcome = null,
						providerFlushOutcome = null,
						providerCoverage = null,
						appDrainComplete = null,
						stopStatus = null,
						updatedAtMs = updatedAtMs,
					),
				)
			}
		}
	}

	@Suppress("ComplexCondition", "LongMethod", "ReturnCount")
	private suspend fun retireRunSource(
		target: RunRetirementTarget,
		cutoff: SessionCutoff,
	): RunSourceRetirementOutcome {
		val replay = when (val replay = replayedRetirementAcknowledgements(target, cutoff)) {
			is RunRetirementReplay.Ready -> replay
			RunRetirementReplay.AuthenticationBlocked ->
				return RunSourceRetirementOutcome(
					target.source,
					listOf(blockedRequestedRetirementAcknowledgement(target, cutoff)),
					retirementClaims = target.drainRetirementClaims(emptySet()),
				)
		}
		val acknowledgements = replay.acknowledgements.toMutableList()
		val cleanupOnlyProviders = replay.cleanupOnlyProviders.toMutableSet()
		val resolvedProviders = cleanupOnlyProviders.toMutableSet()
		replay.acknowledgements.mapNotNullTo(resolvedProviders) { acknowledgement ->
			acknowledgement.providerKeyOrNull()
		}
		val unresolvedClaims = replay.unresolvedClaims.filter { owned ->
			owned.provider !in resolvedProviders
		}
		if (target.source !in runtimes.registeredSources()) {
			for (owned in unresolvedClaims.distinctBy(RunRetirementClaim::provider)) {
				val provider = owned.provider ?: continue
				val acknowledgement = interruptedRetirementAcknowledgement(target, provider, cutoff)
				persistRunRetirementReceipt(target, owned, acknowledgement)
				acknowledgements += acknowledgement
				resolvedProviders += provider
			}
			if (
				acknowledgements.isEmpty() &&
				(
					target.claims.any { claim ->
						claim.provider == null &&
							claim.action.desiredState == ACTION_DESIRED_STARTED
					} ||
						(cleanupOnlyProviders.isEmpty() &&
							target.claims.none { claim -> claim.provider != null })
				)
			) {
				acknowledgements += runRetirementAck(
					target.source,
					cutoff.logicalTrackingId,
					target.serviceRunId,
					SourceStopStatus.COMPLETE,
				)
			}
			return RunSourceRetirementOutcome(
				target.source,
				acknowledgements,
				retirementClaims = target.drainRetirementClaims(cleanupOnlyProviders),
			)
		}
		val stillUnresolvedClaims = mutableListOf<RunRetirementClaim>()
		for ((index, owned) in unresolvedClaims.withIndex()) {
			if (index % RETIREMENT_CANCELLATION_STRIDE == 0) {
				currentCoroutineContext().ensureActive()
			}
			if (owned.provider in resolvedProviders) continue
			val claim = owned.runtimeClaim
			when (val shutdown = runtimes.shutdownIfOwned(claim, cutoff)) {
				OwnedSourceShutdown.NotOwned -> stillUnresolvedClaims += owned
				is OwnedSourceShutdown.Released -> {
					val acknowledgement = shutdown.stopAck
					if (acknowledgement == null) {
						val provider = shutdown.provider ?: owned.provider
						if (target.source == SourceKind.STEPS) {
							if (provider == null || provider != owned.provider) {
								return RunSourceRetirementOutcome(
									target.source,
									listOf(blockedRequestedRetirementAcknowledgement(target, cutoff)),
									retirementClaims = target.drainRetirementClaims(emptySet()),
								)
							}
							persistRunCleanupOnlyReceipt(target, owned, provider)
						}
						provider?.let {
							cleanupOnlyProviders += it
							resolvedProviders += it
						}
						continue
					}
					persistRunRetirementReceipt(target, owned, acknowledgement)
					acknowledgements += acknowledgement
					owned.provider?.let(resolvedProviders::add)
				}
				is OwnedSourceShutdown.Incomplete -> {
					val acknowledgement = shutdown.stopAck ?: owned.provider?.let { provider ->
						interruptedRetirementAcknowledgement(target, provider, cutoff)
					} ?: runRetirementAck(
							target.source,
							cutoff.logicalTrackingId,
							claim.serviceRunId,
							SourceStopStatus.PROVIDER_FAILED,
						)
					persistRunRetirementReceipt(target, owned, acknowledgement)
					acknowledgements += acknowledgement
					owned.provider?.let(resolvedProviders::add)
				}
			}
		}
		for (owned in stillUnresolvedClaims.distinctBy(RunRetirementClaim::provider)) {
			val provider = owned.provider ?: continue
			if (provider in resolvedProviders) continue
			val acknowledgement = interruptedRetirementAcknowledgement(target, provider, cutoff)
			persistRunRetirementReceipt(target, owned, acknowledgement)
			acknowledgements += acknowledgement
			resolvedProviders += provider
		}
		if (
			acknowledgements.isEmpty() &&
			(
				target.claims.any { claim ->
					claim.provider == null &&
						claim.action.desiredState == ACTION_DESIRED_STARTED
				} ||
					(cleanupOnlyProviders.isEmpty() &&
						target.claims.none { claim -> claim.provider != null })
			)
		) {
			acknowledgements += runRetirementAck(
				target.source,
				cutoff.logicalTrackingId,
				target.serviceRunId,
				SourceStopStatus.COMPLETE,
			)
		}
		return RunSourceRetirementOutcome(
			source = target.source,
			acknowledgements = acknowledgements,
			retirementClaims = target.drainRetirementClaims(cleanupOnlyProviders),
		)
	}

	@Suppress("LongMethod", "ReturnCount")
	private suspend fun replayedRetirementAcknowledgements(
		target: RunRetirementTarget,
		cutoff: SessionCutoff,
	): RunRetirementReplay {
		val dao = database.sourceSessionDao()
		val providerClaims = target.claims.mapNotNull { claim ->
			claim.ownershipKey()?.let { ownership -> ownership to claim }
		}
		val claimsByOwnership = providerClaims.toMap()
		if (claimsByOwnership.size != providerClaims.size) {
			return RunRetirementReplay.AuthenticationBlocked
		}
		val rawReceipts = dao.rawRunRetirements(
			cutoff.logicalTrackingId,
			target.serviceRunId,
			target.source.stableCode,
			MAX_RUN_RETIREMENT_RECEIPTS + 1,
		)
		if (rawReceipts.size > MAX_RUN_RETIREMENT_RECEIPTS) {
			return RunRetirementReplay.AuthenticationBlocked
		}
		val receiptsByOwnership = mutableMapOf<RunRetirementOwnershipKey, SourceRunRetirementEntity>()
		for ((index, rawReceipt) in rawReceipts.withIndex()) {
			if (index % RETIREMENT_CANCELLATION_STRIDE == 0) {
				currentCoroutineContext().ensureActive()
			}
			val receipt = rawReceipt.validatedOrNull()
				?: return RunRetirementReplay.AuthenticationBlocked
			val ownership = receipt.ownershipKey()
			if (ownership !in claimsByOwnership ||
				receiptsByOwnership.put(ownership, receipt) != null
			) {
				return RunRetirementReplay.AuthenticationBlocked
			}
		}
		val acknowledgements = mutableListOf<SourceStopAck>()
		val cleanupOnlyProviders = mutableSetOf<SourceProviderKey>()
		val unresolvedClaims = mutableListOf<RunRetirementClaim>()
		for ((index, owned) in target.claims.withIndex()) {
			if (index % RETIREMENT_CANCELLATION_STRIDE == 0) {
				currentCoroutineContext().ensureActive()
			}
			val ownership = owned.ownershipKey()
			if (ownership == null) {
				unresolvedClaims += owned
				continue
			}
			val receipt = receiptsByOwnership[ownership]
			if (receipt == null) {
				unresolvedClaims += owned
				continue
			}
			val provider = requireNotNull(owned.provider)
			if (receipt.state == SourceRunRetirementEntity.STATE_CLEANUP_ONLY_COMPLETED) {
				if (
					target.source != SourceKind.STEPS ||
					!authenticateCleanupOnlyStepsRetirement(target, owned, receipt)
				) {
					return RunRetirementReplay.AuthenticationBlocked
				}
				cleanupOnlyProviders += provider
				continue
			}
			if (receipt.state == SourceRunRetirementEntity.STATE_REQUESTED) {
				if (target.source != SourceKind.STEPS) {
					unresolvedClaims += owned
					continue
				}
				when (val recovery = recoverRequestedStepsRetirement(
					target,
					owned,
					receipt,
				)) {
					is RequestedStepsRetirementRecovery.Authenticated ->
						acknowledgements += recovery.acknowledgement
					RequestedStepsRetirementRecovery.CleanupOnlyCompleted ->
						cleanupOnlyProviders += provider
					RequestedStepsRetirementRecovery.Absent -> unresolvedClaims += owned
					RequestedStepsRetirementRecovery.Blocked ->
						return RunRetirementReplay.AuthenticationBlocked
				}
				continue
			}
			if (target.source == SourceKind.STEPS) {
				when (val authentication = recoverRequestedStepsRetirement(
					target,
					owned,
					receipt,
				)) {
					is RequestedStepsRetirementRecovery.Authenticated ->
						acknowledgements += authentication.acknowledgement
					RequestedStepsRetirementRecovery.CleanupOnlyCompleted,
					RequestedStepsRetirementRecovery.Absent,
					RequestedStepsRetirementRecovery.Blocked,
					-> return RunRetirementReplay.AuthenticationBlocked
				}
				continue
			}
			val acknowledgement = runCatchingNonCancellation {
				receipt.toStopAckOrNull()
			}.getOrNull() ?: return RunRetirementReplay.AuthenticationBlocked
			acknowledgements += acknowledgement
		}
		val resolvedProviders = cleanupOnlyProviders.toMutableSet()
		acknowledgements.mapNotNullTo(resolvedProviders) { acknowledgement ->
			acknowledgement.providerKeyOrNull()
		}
		return RunRetirementReplay.Ready(
			acknowledgements = acknowledgements,
			cleanupOnlyProviders = cleanupOnlyProviders,
			unresolvedClaims = unresolvedClaims.filter { owned ->
				owned.provider !in resolvedProviders
			},
		)
	}

	private suspend fun persistRunCleanupOnlyReceipt(
		target: RunRetirementTarget,
		owned: RunRetirementClaim,
		provider: SourceProviderKey,
	) = withContext(NonCancellable) {
		check(target.source == SourceKind.STEPS)
		check(owned.provider == provider)
		database.withTransaction {
			val current = database.sourceSessionDao().rawRunRetirement(
				owned.runtimeClaim.logicalTrackingId,
				owned.runtimeClaim.serviceRunId,
				target.source.stableCode,
				provider.sourceInstanceId.value,
				provider.registrationGeneration,
			).singleOrNull()?.validatedOrNull()
				?: error("Cleanup-only retirement intent is missing or malformed")
			check(current.ownershipKey() == owned.ownershipKey())
			check(
				current.state in setOf(
					SourceRunRetirementEntity.STATE_REQUESTED,
					SourceRunRetirementEntity.STATE_CLEANUP_ONLY_COMPLETED,
				),
			)
			check(authenticateCleanupOnlyStepsRetirementInTransaction(target, owned, current))
			if (current.state == SourceRunRetirementEntity.STATE_REQUESTED) {
				check(database.sourceSessionDao().updateRunRetirement(
					current.copy(
						state = SourceRunRetirementEntity.STATE_CLEANUP_ONLY_COMPLETED,
						updatedAtMs = maxOf(
							current.updatedAtMs,
							System.currentTimeMillis().coerceAtLeast(0L),
						),
					),
				) == 1)
			}
		}
	}

	private suspend fun authenticateCleanupOnlyStepsRetirement(
		target: RunRetirementTarget,
		owned: RunRetirementClaim,
		receipt: SourceRunRetirementEntity,
	): Boolean = database.withTransaction {
		val current = database.sourceSessionDao().rawRunRetirement(
			receipt.logicalTrackingId,
			receipt.serviceRunId,
			receipt.sourceKind,
			receipt.sourceInstanceId,
			receipt.registrationGeneration,
		).singleOrNull()?.validatedOrNull() ?: return@withTransaction false
		current == receipt &&
			authenticateCleanupOnlyStepsRetirementInTransaction(target, owned, current)
	}

	@Suppress("ComplexCondition")
	private suspend fun authenticateCleanupOnlyStepsRetirementInTransaction(
		target: RunRetirementTarget,
		owned: RunRetirementClaim,
		receipt: SourceRunRetirementEntity,
	): Boolean {
		val provider = owned.provider ?: return false
		if (
			target.source != SourceKind.STEPS ||
			receipt.state !in setOf(
				SourceRunRetirementEntity.STATE_REQUESTED,
				SourceRunRetirementEntity.STATE_CLEANUP_ONLY_COMPLETED,
			) ||
			receipt.ownershipKey() != owned.ownershipKey() ||
			target.serviceRunId != receipt.serviceRunId ||
			provider.sourceInstanceId.value != receipt.sourceInstanceId ||
			provider.registrationGeneration != receipt.registrationGeneration ||
			authenticateRequestedStepsRetirementAction(target, owned, receipt)
				?.isProvisionalCleanupOwner() != true
		) {
			return false
		}
		return hasAuthenticatedCleanupOnlyStepsResolution(receipt)
	}

	private suspend fun hasAuthenticatedCleanupOnlyStepsResolution(
		receipt: SourceRunRetirementEntity,
	): Boolean {
		val registration = database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			receipt.registrationGeneration,
		) ?: return false
		if (
			registration.sourceInstanceId != receipt.sourceInstanceId ||
			registration.status !=
			com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity.STATUS_RETIRED ||
			registration.retiredAtMs == null ||
			registration.retiredElapsedRealtimeNanos == null ||
			registration.failureCode.isNullOrBlank() ||
			!com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
				.isCanonicalOwnerScope(SourceKind.STEPS.stableCode, registration.ownerScope)
		) {
			return false
		}
		if (
			StepsCountDomainStore(database).authenticateTerminalSessionCompleteness(
				logicalTrackingId = receipt.logicalTrackingId,
				serviceRunId = receipt.serviceRunId,
				sourceInstanceId = receipt.sourceInstanceId,
				registrationGeneration = receipt.registrationGeneration,
			) != StepsTerminalCompletenessAuthentication.Absent
		) {
			return false
		}
		return !hasTerminalOrMalformedStepsCheckpoint(receipt)
	}

	private fun RunRetirementTarget.drainRetirementClaims(
		cleanupOnlyProviders: Set<SourceProviderKey>,
	): List<SourceDrainRetirementClaim> {
		val retirementClaims = claims.mapNotNull { owned ->
			val provider = owned.provider ?: return@mapNotNull null
			SourceDrainRetirementClaim(
				source = source,
				sourceInstanceId = provider.sourceInstanceId.value,
				registrationGeneration = provider.registrationGeneration,
				actionId = owned.runtimeClaim.actionId,
				attemptCount = owned.runtimeClaim.attemptCount,
				leaseGeneration = owned.runtimeClaim.leaseGeneration,
				cleanupOnly = provider in cleanupOnlyProviders,
			)
		}.sortedWith(
			compareBy(
				SourceDrainRetirementClaim::registrationGeneration,
				SourceDrainRetirementClaim::sourceInstanceId,
				SourceDrainRetirementClaim::actionId,
			),
		)
		check(cleanupOnlyProviders.all { provider ->
			retirementClaims.any { claim ->
				claim.sourceInstanceId == provider.sourceInstanceId.value &&
					claim.registrationGeneration == provider.registrationGeneration
			}
		})
		return retirementClaims
	}

	private fun RunRetirementClaim.ownershipKey(): RunRetirementOwnershipKey? {
		val exactProvider = provider ?: return null
		return RunRetirementOwnershipKey(
			sourceKind = runtimeClaim.source.stableCode,
			logicalTrackingId = runtimeClaim.logicalTrackingId,
			serviceRunId = runtimeClaim.serviceRunId,
			actionId = runtimeClaim.actionId,
			attemptCount = runtimeClaim.attemptCount,
			leaseGeneration = runtimeClaim.leaseGeneration,
			sourceInstanceId = exactProvider.sourceInstanceId.value,
			registrationGeneration = exactProvider.registrationGeneration,
		)
	}

	private fun SourceRunRetirementEntity.ownershipKey(): RunRetirementOwnershipKey =
		RunRetirementOwnershipKey(
			sourceKind = sourceKind,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			actionId = actionId,
			attemptCount = attemptCount,
			leaseGeneration = leaseGeneration,
			sourceInstanceId = sourceInstanceId,
			registrationGeneration = registrationGeneration,
		)

	@Suppress("ComplexCondition", "LongMethod", "ReturnCount")
	private suspend fun recoverRequestedStepsRetirement(
		target: RunRetirementTarget,
		owned: RunRetirementClaim,
		receipt: SourceRunRetirementEntity,
	): RequestedStepsRetirementRecovery = database.withTransaction {
		val dao = database.sourceSessionDao()
		val rawCurrentRows = dao.rawRunRetirement(
			receipt.logicalTrackingId,
			receipt.serviceRunId,
			receipt.sourceKind,
			receipt.sourceInstanceId,
			receipt.registrationGeneration,
		)
		if (rawCurrentRows.isEmpty()) return@withTransaction RequestedStepsRetirementRecovery.Absent
		val current = rawCurrentRows.singleOrNull()?.validatedOrNull()
			?: return@withTransaction RequestedStepsRetirementRecovery.Blocked
		val provider = owned.provider
			?: return@withTransaction RequestedStepsRetirementRecovery.Blocked
		if (current != receipt ||
			target.source != SourceKind.STEPS ||
			target.serviceRunId != current.serviceRunId ||
			provider.sourceInstanceId.value != current.sourceInstanceId ||
			provider.registrationGeneration != current.registrationGeneration ||
			owned.runtimeClaim.actionId != current.actionId ||
			owned.runtimeClaim.attemptCount != current.attemptCount ||
			owned.runtimeClaim.leaseGeneration != current.leaseGeneration ||
			owned.runtimeClaim.logicalTrackingId != current.logicalTrackingId ||
			owned.runtimeClaim.serviceRunId != current.serviceRunId
		) {
			return@withTransaction RequestedStepsRetirementRecovery.Blocked
		}
		val action = authenticateRequestedStepsRetirementAction(target, owned, current)
			?: return@withTransaction RequestedStepsRetirementRecovery.Blocked
		if (
			current.state == SourceRunRetirementEntity.STATE_REQUESTED &&
			action.isProvisionalCleanupOwner() &&
			hasAuthenticatedCleanupOnlyStepsResolution(current)
		) {
			check(dao.updateRunRetirement(
				current.copy(
					state = SourceRunRetirementEntity.STATE_CLEANUP_ONLY_COMPLETED,
					updatedAtMs = maxOf(
						current.updatedAtMs,
						System.currentTimeMillis().coerceAtLeast(0L),
					),
				),
			) == 1)
			return@withTransaction RequestedStepsRetirementRecovery.CleanupOnlyCompleted
		}
		val authentication = StepsCountDomainStore(database).authenticateTerminalSessionCompleteness(
			logicalTrackingId = current.logicalTrackingId,
			serviceRunId = current.serviceRunId,
			sourceInstanceId = current.sourceInstanceId,
			registrationGeneration = current.registrationGeneration,
		)
		if (authentication == StepsTerminalCompletenessAuthentication.Absent) {
			return@withTransaction if (
				current.state != SourceRunRetirementEntity.STATE_REQUESTED ||
				hasTerminalOrMalformedStepsCheckpoint(current)
			) {
				RequestedStepsRetirementRecovery.Blocked
			} else {
				RequestedStepsRetirementRecovery.Absent
			}
		}
		val authenticated =
			authentication as? StepsTerminalCompletenessAuthentication.Authenticated
				?: return@withTransaction RequestedStepsRetirementRecovery.Blocked
		val registration = database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			current.registrationGeneration,
		) ?: return@withTransaction RequestedStepsRetirementRecovery.Blocked
		if (registration.sourceInstanceId != current.sourceInstanceId ||
			registration.status !=
			com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity.STATUS_RETIRED ||
			authenticated.countDomainCollectedDataEpoch?.let { epoch ->
				epoch != registration.collectedDataEpoch
			} == true ||
			!com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
				.isCanonicalOwnerScope(SourceKind.STEPS.stableCode, registration.ownerScope)
		) {
			return@withTransaction RequestedStepsRetirementRecovery.Blocked
		}
		val state = database.sourceRuntimeStateDao().get(
			SourceKind.STEPS.stableCode,
			registration.ownerScope,
		) ?: return@withTransaction RequestedStepsRetirementRecovery.Blocked
		if (state.stateVersion != SENSOR_RUNTIME_CHECKPOINT_VERSION ||
			state.sourceInstanceId != current.sourceInstanceId ||
			state.registrationGeneration != current.registrationGeneration ||
			state.clockDomainId != registration.clockDomainId ||
			state.lastProviderSequence < 0L
		) {
			return@withTransaction RequestedStepsRetirementRecovery.Blocked
		}
		val checkpoint = decodeSensorRuntimeCheckpoint(state, legacyComponentStateVersion = 1)
			?: return@withTransaction RequestedStepsRetirementRecovery.Blocked
		val completeness = authenticated.completeness
		val expectedLifecycle = if (completeness.appDrainComplete) {
			RuntimeCheckpointLifecycle.QUIESCED
		} else {
			RuntimeCheckpointLifecycle.TIMED_OUT
		}
		val metrics = checkpoint.metrics
		val sequenceHighWater = listOfNotNull(
			metrics.lastDurablyAdmittedSequence,
			metrics.unresolvedSequenceEndInclusive,
		).maxOrNull() ?: 0L
		if (checkpoint.lifecycle != expectedLifecycle ||
			metrics.lastDurablyAdmittedSequence != completeness.lastSourceSequence ||
			metrics.lastAdmissionOrdinal != completeness.lastAdmissionOrdinal ||
			metrics.unresolvedSequenceStart != completeness.unresolvedSequenceStart ||
			metrics.unresolvedSequenceEndInclusive != completeness.unresolvedSequenceEnd ||
			metrics.failedAdmissionCount < 0L ||
			(metrics.unresolvedSequenceStart == null) !=
				(metrics.unresolvedSequenceEndInclusive == null) ||
			metrics.unresolvedSequenceStart?.let { start ->
				start <= 0L || start > requireNotNull(metrics.unresolvedSequenceEndInclusive)
			} == true ||
			state.lastProviderSequence < sequenceHighWater ||
			completeness.stopStatus !in setOf(
				SourceStopStatus.COMPLETE.name,
				SourceStopStatus.PARTIAL_UNOBSERVABLE.name,
				SourceStopStatus.PROCESS_RESTARTED.name,
			)
		) {
			return@withTransaction RequestedStepsRetirementRecovery.Blocked
		}
		val acknowledgement = runCatchingNonCancellation {
			SourceStopAck(
				source = SourceKind.STEPS,
				sourceInstanceId = SourceInstanceId(current.sourceInstanceId),
				registrationGeneration = current.registrationGeneration,
				appliedRevision = action.desiredPlanRevision,
				callbackEntryBarrierSequence = state.lastProviderSequence,
				lastDurablyAdmittedSequence = metrics.lastDurablyAdmittedSequence,
				lastAdmissionOrdinal = metrics.lastAdmissionOrdinal,
				failedAdmissionCount = metrics.failedAdmissionCount,
				unresolvedSequenceStart = metrics.unresolvedSequenceStart,
				unresolvedSequenceEndInclusive = metrics.unresolvedSequenceEndInclusive,
				registrationRemovalOutcome = RegistrationRemovalOutcome.valueOf(
					authenticated.retirementEvidence.registrationRemovalOutcome,
				),
				providerFlushOutcome = ProviderFlushOutcome.valueOf(
					authenticated.retirementEvidence.providerFlushOutcome,
				),
				providerCoverage = ProviderCoverage.valueOf(completeness.providerCoverage),
				appDrainComplete = completeness.appDrainComplete,
				status = SourceStopStatus.valueOf(completeness.stopStatus),
				logicalTrackingId = current.logicalTrackingId,
				serviceRunId = current.serviceRunId,
			)
		}.getOrNull() ?: return@withTransaction RequestedStepsRetirementRecovery.Blocked
		if (!acknowledgement.hasTerminalStepsRetirement()) {
			return@withTransaction RequestedStepsRetirementRecovery.Blocked
		}
		if (current.state == SourceRunRetirementEntity.STATE_REQUESTED) {
			val persisted = current.withRetirementAcknowledgement(
				acknowledgement,
				maxOf(current.updatedAtMs, System.currentTimeMillis().coerceAtLeast(0L)),
			)
			check(dao.updateRunRetirement(persisted) == 1)
		} else {
			val expectedState = if (acknowledgement.status == SourceStopStatus.PROCESS_RESTARTED) {
				SourceRunRetirementEntity.STATE_INTERRUPTED
			} else {
				SourceRunRetirementEntity.STATE_ACKNOWLEDGED
			}
			val storedAcknowledgement = runCatchingNonCancellation {
				current.toStopAckOrNullForReplay()
			}.getOrNull()
			if (current.state != expectedState || storedAcknowledgement != acknowledgement) {
				return@withTransaction RequestedStepsRetirementRecovery.Blocked
			}
		}
		RequestedStepsRetirementRecovery.Authenticated(acknowledgement)
	}

	private fun LifecycleDesiredActionEntity.isProvisionalCleanupOwner(): Boolean =
		desiredState == ACTION_DESIRED_STARTED &&
			(
				status == LifecycleActionStatus.APPLYING.name ||
					(status == LifecycleActionStatus.CLEANUP_REQUIRED.name &&
						failureCode == SOURCE_RUNTIME_CLEANUP_PENDING &&
						retryTrigger == RUNTIME_CLEANUP_RETRY)
			)

	@Suppress("ComplexCondition", "LongMethod", "ReturnCount")
	private suspend fun authenticateRequestedStepsRetirementAction(
		target: RunRetirementTarget,
		owned: RunRetirementClaim,
		retirement: SourceRunRetirementEntity,
	): LifecycleDesiredActionEntity? {
		val dao = database.sourceSessionDao()
		val action = owned.action
		val session = dao.session(retirement.logicalTrackingId) ?: return null
		val run = dao.serviceRun(retirement.serviceRunId) ?: return null
		val manifestEnvelope = owned.manifestEnvelope
		val actionSourceKind = action.sourceKind ?: return null
		val manifest = manifestEnvelope.manifest
		val binding = manifestEnvelope.bindings.singleOrNull { candidate ->
			candidate.sourceKind == SourceKind.STEPS.stableCode &&
				candidate.purpose == SessionManifestPurpose.SESSION_CAPTURE.name
		} ?: return null
		val manifestActions = target.actions.filter { candidate ->
			candidate.logicalTrackingId == retirement.logicalTrackingId &&
				candidate.serviceRunId == retirement.serviceRunId &&
				candidate.manifestRevision == action.manifestRevision &&
				candidate.sourceKind == SourceKind.STEPS.stableCode &&
				candidate.actionFamily == LifecycleActionFamily.SOURCE_RUNTIME.name &&
				candidate.desiredState == ACTION_DESIRED_STARTED
		}
		if (manifestActions.singleOrNull()?.actionId != action.actionId) return null
		val rawIntents = dao.rawLifecycleIntentsForManifestBounded(
			retirement.logicalTrackingId,
			action.manifestRevision,
			MAX_RUN_RETIREMENT_INTENTS_PER_MANIFEST + 1,
		)
		if (rawIntents.size > MAX_RUN_RETIREMENT_INTENTS_PER_MANIFEST) return null
		val intents = rawIntents.map { raw -> raw.validatedOrNull() ?: return null }
		if (intents.any { intent ->
				intent.logicalTrackingId != retirement.logicalTrackingId ||
					intent.manifestRevision != action.manifestRevision
			}
		) return null
		val intent = intents.singleOrNull { candidate ->
			candidate.hasAuthenticStartEnvelope(manifest) &&
				action.actionId == lifecycleActionIdentity(
					candidate.intentRevision,
					action.logicalTrackingId,
					action.serviceRunId,
					action.manifestRevision,
					action.actionRevision,
					action.actionFamily,
					actionSourceKind,
					action.desiredState,
					action.desiredPlanRevision,
					action.sourcePolicyRevision,
					action.consentEpoch,
					action.startOrigin,
					action.bootId,
					action.leaseGeneration,
					action.requestedAtMs,
					action.requestedElapsedRealtimeNanos,
				)
		} ?: return null
		val plan = runCatchingNonCancellation {
			planStore.load(manifest.acquisitionPlanRevision)
		}.getOrNull() ?: return null
		val stepsPlan = plan.plans[SourceKind.STEPS] as? StepsPlan ?: return null
		if (
			action.actionId != retirement.actionId ||
			action.logicalTrackingId != retirement.logicalTrackingId ||
			action.serviceRunId != retirement.serviceRunId ||
			action.actionRevision <= 0L ||
			action.actionFamily != LifecycleActionFamily.SOURCE_RUNTIME.name ||
			action.sourceKind != SourceKind.STEPS.stableCode ||
			action.desiredState != ACTION_DESIRED_STARTED ||
			action.desiredPlanRevision != manifest.acquisitionPlanRevision ||
			action.sourcePolicyRevision != manifest.sourcePolicyRevision ||
			action.consentEpoch != binding.consentEpoch ||
			action.startOrigin != manifest.startOrigin ||
			action.bootId != manifest.effectiveBootId ||
			action.requestedAtMs != manifest.effectiveWallTimeMs ||
			action.requestedElapsedRealtimeNanos != manifest.effectiveElapsedRealtimeNanos ||
			intent.logicalTrackingId != action.logicalTrackingId ||
			intent.manifestRevision != action.manifestRevision ||
			action.attemptCount != retirement.attemptCount ||
			action.attemptCount <= 0 ||
			action.leaseGeneration != retirement.leaseGeneration ||
			action.leaseGeneration <= 0L ||
			action.sourceInstanceId != retirement.sourceInstanceId ||
			action.registrationGeneration != retirement.registrationGeneration ||
			action.status !in setOf(
				LifecycleActionStatus.APPLYING.name,
				LifecycleActionStatus.START_ACCEPTED.name,
				LifecycleActionStatus.CLEANUP_REQUIRED.name,
			) ||
			session.logicalTrackingId != retirement.logicalTrackingId ||
			session.currentServiceRunId != retirement.serviceRunId ||
			session.currentManifestRevision == null ||
			session.currentManifestRevision < action.manifestRevision ||
			session.state != SessionLifecycleState.STOPPING.name ||
			session.cutoffElapsedNanos != retirement.cutoffElapsedRealtimeNanos ||
			session.cutoffAtMs != retirement.cutoffWallTimeMs ||
			run.logicalTrackingId != retirement.logicalTrackingId ||
			run.serviceRunId != retirement.serviceRunId ||
			run.state != SessionLifecycleState.STOPPING.name ||
			run.completedAtMs != null ||
			run.desiredPlanRevision != session.desiredPlanRevision ||
			run.rolloutRevision != session.rolloutRevision ||
			run.bootId != session.lifecycleBootId ||
			manifest.logicalTrackingId != retirement.logicalTrackingId ||
			manifest.serviceRunId != retirement.serviceRunId ||
			manifest.manifestRevision != action.manifestRevision ||
			manifest.rolloutRevision != run.rolloutRevision ||
			manifest.sessionMode != session.sessionMode ||
			manifest.effectiveBootId != run.bootId ||
			manifest.effectiveWallTimeMs < run.startedAtMs ||
			manifest.effectiveElapsedRealtimeNanos < run.startedElapsedNanos ||
			plan.revision != manifest.acquisitionPlanRevision ||
			plan.sourcePolicyRevision != manifest.sourcePolicyRevision ||
			stepsPlan.revision != manifest.acquisitionPlanRevision ||
			!stepsPlan.enabled ||
			!binding.persistenceEligible
		) {
			return null
		}
		return action
	}

	private fun SessionLifecycleIntentVersionEntity.hasAuthenticStartEnvelope(
		manifest: SessionManifestVersionEntity,
	): Boolean = logicalTrackingId == manifest.logicalTrackingId &&
		manifestRevision == manifest.manifestRevision &&
		desiredState == LifecycleDesiredState.ACTIVE.name &&
		startOrigin == manifest.startOrigin &&
		requestBootId == manifest.effectiveBootId &&
		requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
		requestedWallTimeMs == manifest.effectiveWallTimeMs &&
		automationEpoch == manifest.automationEpoch &&
		stopReason == null &&
		stopDeadlineBootId == null &&
		stopDeadlineElapsedRealtimeNanos == null &&
		intentChecksum == stableLifecycleChecksum(
			logicalTrackingId,
			intentRevision,
			manifestRevision,
			LifecycleDesiredState.ACTIVE,
			startOrigin,
			requestBootId,
			requestedElapsedRealtimeNanos,
			requestedWallTimeMs,
			triggerId,
			automationEpoch,
			triggerCollectedDataEpoch,
		)

	private suspend fun hasTerminalOrMalformedStepsCheckpoint(
		receipt: SourceRunRetirementEntity,
	): Boolean {
		val registration = database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			receipt.registrationGeneration,
		) ?: return false
		if (registration.sourceInstanceId != receipt.sourceInstanceId ||
			registration.status !=
			com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity.STATUS_RETIRED
		) return false
		if (!com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
				.isCanonicalOwnerScope(SourceKind.STEPS.stableCode, registration.ownerScope)
		) return true
		val state = database.sourceRuntimeStateDao().get(
			SourceKind.STEPS.stableCode,
			registration.ownerScope,
		) ?: return false
		if (state.sourceInstanceId != receipt.sourceInstanceId ||
			state.registrationGeneration != receipt.registrationGeneration
		) {
			return false
		}
		if (state.stateVersion != SENSOR_RUNTIME_CHECKPOINT_VERSION) return true
		val checkpoint = decodeSensorRuntimeCheckpoint(state, legacyComponentStateVersion = 1)
			?: return true
		return checkpoint.lifecycle != RuntimeCheckpointLifecycle.ACTIVE
	}

	private fun blockedRequestedRetirementAcknowledgement(
		target: RunRetirementTarget,
		cutoff: SessionCutoff,
	): SourceStopAck {
		val provider = target.claims.firstNotNullOfOrNull(RunRetirementClaim::provider)
		return SourceStopAck(
			source = target.source,
			sourceInstanceId = provider?.sourceInstanceId
				?: SourceInstanceId("unverifiable-${target.source.name.lowercase()}"),
			registrationGeneration = provider?.registrationGeneration ?: 0L,
			appliedRevision = null,
			callbackEntryBarrierSequence = 0L,
			lastDurablyAdmittedSequence = null,
			lastAdmissionOrdinal = null,
			failedAdmissionCount = 1L,
			unresolvedSequenceStart = null,
			unresolvedSequenceEndInclusive = null,
			registrationRemovalOutcome = RegistrationRemovalOutcome.UNOBSERVABLE,
			providerFlushOutcome = ProviderFlushOutcome.NOT_REQUESTED,
			providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
			appDrainComplete = false,
			status = SourceStopStatus.PROVIDER_FAILED,
			logicalTrackingId = cutoff.logicalTrackingId,
			serviceRunId = target.serviceRunId,
		)
	}

	private suspend fun interruptedRetirementAcknowledgement(
		target: RunRetirementTarget,
		provider: SourceProviderKey,
		cutoff: SessionCutoff,
	): SourceStopAck {
		val registration = database.sourceBrokerDao().registration(
			target.source.stableCode,
			provider.registrationGeneration,
		)
		val highWater = database.sourceBrokerDao().runCaptureAdmissionHighWater(
			sourceKind = target.source.stableCode,
			sourceInstanceId = provider.sourceInstanceId.value,
			registrationGeneration = provider.registrationGeneration,
			logicalTrackingId = cutoff.logicalTrackingId,
			serviceRunId = target.serviceRunId,
			capturePurposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		)
		if (registration == null ||
			registration.sourceInstanceId != provider.sourceInstanceId.value
		) {
			return SourceStopAck(
				source = target.source,
				sourceInstanceId = provider.sourceInstanceId,
				registrationGeneration = provider.registrationGeneration,
				appliedRevision = null,
				callbackEntryBarrierSequence = highWater.lastSourceSequence,
				lastDurablyAdmittedSequence = highWater.lastSourceSequence,
				lastAdmissionOrdinal = highWater.lastAdmissionOrdinal,
				failedAdmissionCount = 1L,
				unresolvedSequenceStart = 1L.takeIf { highWater.lastSourceSequence > 0L },
				unresolvedSequenceEndInclusive =
					highWater.lastSourceSequence.takeIf { it > 0L },
				registrationRemovalOutcome = RegistrationRemovalOutcome.UNOBSERVABLE,
				providerFlushOutcome = ProviderFlushOutcome.NOT_REQUESTED,
				providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
				appDrainComplete = false,
				status = SourceStopStatus.PROVIDER_FAILED,
				logicalTrackingId = cutoff.logicalTrackingId,
				serviceRunId = target.serviceRunId,
			)
		}
		val retired = registration.status ==
			com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity.STATUS_RETIRED
		return SourceStopAck(
			source = target.source,
			sourceInstanceId = provider.sourceInstanceId,
			registrationGeneration = provider.registrationGeneration,
			appliedRevision = null,
			callbackEntryBarrierSequence = highWater.lastSourceSequence,
			lastDurablyAdmittedSequence = highWater.lastSourceSequence,
			lastAdmissionOrdinal = highWater.lastAdmissionOrdinal,
			failedAdmissionCount = if (highWater.lastSourceSequence > 0L) 1L else 0L,
			unresolvedSequenceStart = 1L.takeIf { highWater.lastSourceSequence > 0L },
			unresolvedSequenceEndInclusive =
				highWater.lastSourceSequence.takeIf { it > 0L },
			registrationRemovalOutcome = if (retired) {
				RegistrationRemovalOutcome.REMOVED
			} else {
				RegistrationRemovalOutcome.UNOBSERVABLE
			},
			providerFlushOutcome = ProviderFlushOutcome.NOT_REQUESTED,
			providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
			appDrainComplete = false,
			status = if (retired) {
				SourceStopStatus.PROCESS_RESTARTED
			} else {
				SourceStopStatus.PROVIDER_FAILED
			},
			logicalTrackingId = cutoff.logicalTrackingId,
			serviceRunId = target.serviceRunId,
		)
	}

	private suspend fun persistRunRetirementReceipt(
		target: RunRetirementTarget,
		owned: RunRetirementClaim,
		acknowledgement: SourceStopAck,
	) = withContext(NonCancellable) {
		val provider = owned.provider ?: acknowledgement.providerKeyOrNull() ?: return@withContext
		database.withTransaction {
			val dao = database.sourceSessionDao()
			val current = dao.rawRunRetirement(
				acknowledgement.logicalTrackingId ?: return@withTransaction,
				target.serviceRunId,
				target.source.stableCode,
				provider.sourceInstanceId.value,
				provider.registrationGeneration,
			).singleOrNull()?.validatedOrNull() ?: return@withTransaction
			check(acknowledgement.source == target.source)
			check(acknowledgement.sourceInstanceId == provider.sourceInstanceId)
			check(acknowledgement.registrationGeneration == provider.registrationGeneration)
			val updatedAtMs =
				maxOf(current.updatedAtMs, System.currentTimeMillis().coerceAtLeast(0L))
			val persisted = current.withRetirementAcknowledgement(acknowledgement, updatedAtMs)
			check(dao.updateRunRetirement(
				persisted,
			) == 1)
		}
	}

	private fun SourceRunRetirementEntity.toStopAckOrNull(): SourceStopAck? =
		toStopAckOrNullForReplay()

	private suspend fun saveCompleteness(
		logicalTrackingId: String,
		serviceRunId: String,
		ack: SourceStopAck,
		nowMs: Long,
	): CompletenessPersistenceResult {
		check(ack.hasMembership(logicalTrackingId, serviceRunId)) {
			"Completeness acknowledgement lacks exact service-run membership"
		}
		if (ack.source == SourceKind.STEPS && !ack.hasTerminalLifecycleSettlement()) {
			return CompletenessPersistenceResult.Stored
		}
		if (nowMs !in 0L until Long.MAX_VALUE) {
			return CompletenessPersistenceResult.AuthenticationBlocked
		}
		val candidate = SourceSessionCompletenessEntity(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceKind = ack.source.stableCode,
			sourceInstanceId = ack.sourceInstanceId.value,
			registrationGeneration = ack.registrationGeneration,
			lastAdmissionOrdinal = ack.lastAdmissionOrdinal,
			lastSourceSequence = ack.lastDurablyAdmittedSequence,
			appDrainComplete = ack.appDrainComplete,
			providerCoverage = ack.providerCoverage.name,
			stopStatus = ack.status.name,
			unresolvedSequenceStart = ack.unresolvedSequenceStart,
			unresolvedSequenceEnd = ack.unresolvedSequenceEndInclusive,
			updatedAtMs = nowMs,
		)
		val countDomainStore = StepsCountDomainStore(database)
		val completeness = if (ack.source == SourceKind.STEPS && countDomainStore.isInstalled()) {
			val rawExisting = database.sourceSessionDao().rawSourceCompletenessForServiceRun(
				serviceRunId = serviceRunId,
				sourceKind = SourceKind.STEPS.stableCode,
				limit = MAX_STEPS_COMPLETENESS_ROWS + 1,
			)
			if (rawExisting.size > MAX_STEPS_COMPLETENESS_ROWS) {
				return CompletenessPersistenceResult.AuthenticationBlocked
			}
			val existingRows = rawExisting.map { raw ->
				val existing = raw.validatedOrNull()
					?: return CompletenessPersistenceResult.AuthenticationBlocked
				if (
					existing.logicalTrackingId != logicalTrackingId ||
					existing.serviceRunId != serviceRunId ||
					existing.sourceKind != SourceKind.STEPS.stableCode
				) {
					return CompletenessPersistenceResult.AuthenticationBlocked
				}
				existing
			}
			val exactRows = existingRows.filter {
				it.sourceInstanceId == candidate.sourceInstanceId &&
					it.registrationGeneration == candidate.registrationGeneration
			}
			if (exactRows.size > 1) return CompletenessPersistenceResult.AuthenticationBlocked
			try {
				candidate.withMonotonicStepsCountDomainRevision(exactRows.singleOrNull())
			} catch (_: IllegalArgumentException) {
				return CompletenessPersistenceResult.AuthenticationBlocked
			}
		} else {
			candidate
		}
		database.sourceSessionDao().saveCompleteness(completeness)
		if (ack.source == SourceKind.STEPS) {
			val countDomainResult = countDomainStore.recordSessionCompleteness(
				completeness,
				StepsCountDomainRetirementEvidence(
					providerFlushOutcome = ack.providerFlushOutcome.name,
					registrationRemovalOutcome = ack.registrationRemovalOutcome.name,
				),
			)
			when (countDomainResult) {
				StepsCountDomainWriteResult.INSERTED,
				StepsCountDomainWriteResult.EXACT_REPLAY,
				StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE,
				StepsCountDomainWriteResult.NOT_APPLICABLE,
				-> Unit
				StepsCountDomainWriteResult.AUTHORITY_PENDING,
				StepsCountDomainWriteResult.UNPROVEN,
				StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE,
				StepsCountDomainWriteResult.IDENTITY_CONFLICT,
				StepsCountDomainWriteResult.REVISION_GAP,
				StepsCountDomainWriteResult.TERMINAL_OWNER,
				-> return CompletenessPersistenceResult.AuthenticationBlocked
			}
			if (countDomainResult == StepsCountDomainWriteResult.INSERTED) {
				publishStepsCountDomainEvidenceRevisionAtWallTime(database, completeness.updatedAtMs)
			}
		}
		return CompletenessPersistenceResult.Stored
	}

	private fun CompletenessPersistenceResult.requireStoredCompleteness() {
		if (this == CompletenessPersistenceResult.AuthenticationBlocked) {
			throw CompletenessAuthenticationBlockedException()
		}
	}

	private suspend fun freezeSettlementHighWater(
		bound: BoundServiceRunTransition,
		cutoff: SessionCutoff,
		observedHighWaterOrdinal: Long,
		lease: LifecycleLeaseToken,
	): Long = database.withTransaction {
		require(observedHighWaterOrdinal >= 0L)
		requireLeaseInTransaction(lease)
		requireBoundServiceRun(bound, SessionLifecycleState.STOPPING)
		val current = requireNotNull(database.sourceSessionDao().session(bound.session.logicalTrackingId))
		check(current.currentServiceRunId == bound.serviceRunId)
		check(current.cutoffElapsedNanos == cutoff.elapsedRealtimeNanos)
		check(current.cutoffAtMs == cutoff.wallTimeMs)
		current.finalAdmissionOrdinal?.also { frozen ->
			check(frozen <= observedHighWaterOrdinal) {
				"Frozen settlement high-water cannot exceed the durable WAL high-water"
			}
			return@withTransaction frozen
		}
		check(
			database.sourceSessionDao().updateSession(
				current.copy(
					lifecycleRevision = current.lifecycleRevision + 1L,
					finalAdmissionOrdinal = observedHighWaterOrdinal,
				),
			) == 1,
		)
		observedHighWaterOrdinal
	}

	private suspend fun durableSourceHighWater(): Long = maxOf(
		database.sourceEventWalDao().maximumAdmissionOrdinal() ?: 0L,
		database.sourceEvidenceStateDao().get()?.deletedSourceEventHighWaterOrdinal ?: 0L,
	)

	private suspend fun drainSettledSourceProducts(
		logicalTrackingId: String,
		serviceRunId: String,
		cutoff: SessionCutoff,
		settlementHighWaterOrdinal: Long,
		authority: SourceProductDrainAuthority,
	): SettledSourceDrainBatch {
		val plan = buildSourceProductDrainPlan(
			database = database,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			cutoffElapsedRealtimeNanos = cutoff.elapsedRealtimeNanos,
			cutoffWallTimeMs = cutoff.wallTimeMs,
			settlementHighWaterAdmissionOrdinal = settlementHighWaterOrdinal,
			authenticatedAuthority = authority,
		)
		if (plan is SourceProductDrainPlan.Failed) {
			return SettledSourceDrainBatch.Pending(
				results = emptyList(),
				failedSource = plan.source,
				reason = plan.reason,
				memberships = plan.memberships,
			)
		}
		plan as SourceProductDrainPlan.Ready
		val results = plan.settledResults + plan.requests.map { request ->
			sourceProductDrainRouter.drainThrough(request)
		}
		val pending = results.firstOrNull { result ->
			result !is SourceProductDrainResult.Complete &&
				result !is SourceProductDrainResult.Unavailable
		}
		return if (pending == null) {
			SettledSourceDrainBatch.Complete(results)
		} else {
			SettledSourceDrainBatch.Pending(
				results = results,
				failedSource = pending.request.source,
				reason = pending.pendingReason(),
				memberships = pending.request.memberships,
			)
		}
	}

	private suspend fun saveOwnedShutdownCompleteness(
		authorization: CaptureAuthorization,
		ack: SourceStopAck,
		nowMs: Long,
	): Boolean = try {
		database.withTransaction {
			check(ack.hasMembership(authorization.logicalTrackingId, authorization.serviceRunId)) {
				"Owned runtime shutdown acknowledgement lacks exact service-run membership"
			}
			val run = requireNotNull(database.sourceSessionDao().serviceRun(authorization.serviceRunId)) {
				"Owned runtime shutdown refers to a missing service run"
			}
			check(run.logicalTrackingId == authorization.logicalTrackingId) {
				"Owned runtime shutdown refers to another logical session"
			}
			saveCompleteness(
				authorization.logicalTrackingId,
				authorization.serviceRunId,
				ack,
				nowMs,
			).requireStoredCompleteness()
			true
		}
	} catch (_: CompletenessAuthenticationBlockedException) {
		false
	}

	private suspend fun markReconfiguredIfPolicyCurrent(
		bound: BoundServiceRunTransition,
		plan: AcquisitionPlanRevision,
		manifestRevision: Long,
		lease: LifecycleLeaseToken,
		foregroundCapabilityFlags: Long,
	): String? {
		var failure: String? = null
		database.withTransaction {
			requireLeaseInTransaction(lease)
			failure = validateSourcePolicyInTransaction(plan)
			if (failure != null) return@withTransaction
			val dao = database.sourceSessionDao()
			val session = dao.session(bound.session.logicalTrackingId)
			val run = dao.serviceRun(bound.serviceRunId)
			val manifestEnvelope = verifiedManifest(
				bound.session.logicalTrackingId,
				manifestRevision,
				bound.serviceRunId,
			)
			if (session == null || run == null || manifestEnvelope == null ||
				session.currentServiceRunId != bound.serviceRunId ||
				session.currentManifestRevision != manifestRevision ||
				session.lifecycleLeaseGeneration != lease.generation ||
				session.state != SessionLifecycleState.RECONFIGURING.name ||
				run.logicalTrackingId != session.logicalTrackingId ||
				run.completedAtMs != null ||
				run.state !in setOf(SessionLifecycleState.ACTIVE.name, SessionLifecycleState.RECONFIGURING.name) ||
				run.runRevision != bound.expectedRunRevision ||
				run.leaseGeneration != lease.generation ||
				run.desiredPlanRevision != plan.revision ||
				manifestEnvelope.manifest.acquisitionPlanRevision != plan.revision ||
				manifestEnvelope.manifest.sourcePolicyRevision != plan.sourcePolicyRevision
			) {
				failure = "LIFECYCLE_RECONFIGURE_INTENT_STALE"
				return@withTransaction
			}
			val exactActions = dao.lifecycleActions(session.logicalTrackingId).filter { action ->
				action.serviceRunId == bound.serviceRunId && action.manifestRevision == manifestRevision
			}
			if (exactActions.any { action -> action.status == LifecycleActionStatus.CLEANUP_REQUIRED.name }) {
				failure = SOURCE_RUNTIME_CLEANUP_PENDING
				return@withTransaction
			}
			val acceptedSources = exactActions.filter { action ->
				action.status == LifecycleActionStatus.START_ACCEPTED.name
			}.mapNotNull(LifecycleDesiredActionEntity::sourceKind).toSet()
			val enabledSources = manifestEnvelope.bindings.filter { binding ->
				binding.purpose == SessionManifestPurpose.SESSION_CAPTURE.name && binding.persistenceEligible
			}.map(SessionManifestSourceEntity::sourceKind).toSet()
			if (enabledSources.isNotEmpty() && acceptedSources.intersect(enabledSources).isEmpty()) {
				failure = "NO_SOURCE_START_ACCEPTED"
				return@withTransaction
			}
			check(dao.updateSession(
				session.copy(
					state = SessionLifecycleState.ACTIVE.name,
					lifecycleRevision = session.lifecycleRevision + 1L,
				),
			) == 1)
			check(dao.updateServiceRun(
				run.copy(
					state = SessionLifecycleState.ACTIVE.name,
					appliedForegroundCapabilityFlags = foregroundCapabilityFlags,
					runtimeAcknowledgement = LifecycleActionStatus.START_ACCEPTED.name,
					runtimeFailureCode = null,
					runRevision = run.runRevision + 1L,
				),
			) == 1)
		}
		return failure
	}

	private suspend fun markRunningIfPolicyCurrent(
		logicalTrackingId: String,
		serviceRunId: String,
		plan: AcquisitionPlanRevision,
		manifestRevision: Long,
		lease: LifecycleLeaseToken,
		foregroundCapabilityFlags: Long,
	): String? {
		var policyFailure: String? = null
		database.withTransaction {
			requireLeaseInTransaction(lease)
			policyFailure = validateSourcePolicyInTransaction(plan)
			if (policyFailure != null) return@withTransaction
			val session = requireNotNull(database.sourceSessionDao().session(logicalTrackingId))
			val manifest = verifiedManifest(logicalTrackingId, manifestRevision, serviceRunId)?.manifest
			val run = database.sourceSessionDao().serviceRun(serviceRunId)
			if (session.currentServiceRunId != serviceRunId ||
				session.currentManifestRevision != manifestRevision ||
				session.lifecycleLeaseGeneration != lease.generation ||
				manifest?.serviceRunId != serviceRunId ||
				run == null || run.logicalTrackingId != logicalTrackingId ||
				run.completedAtMs != null || run.state != SessionLifecycleState.STARTING.name ||
				run.desiredPlanRevision != plan.revision ||
				session.state !in setOf(
					SessionLifecycleState.STARTING.name,
					SessionLifecycleState.RECONFIGURING.name,
				)
			) {
				policyFailure = "LIFECYCLE_INTENT_STALE"
				return@withTransaction
			}
			val exactActions = database.sourceSessionDao().lifecycleActions(logicalTrackingId).filter { action ->
				action.serviceRunId == serviceRunId &&
					action.manifestRevision == manifestRevision
			}
			if (exactActions.any { action -> action.status == LifecycleActionStatus.CLEANUP_REQUIRED.name }) {
				policyFailure = SOURCE_RUNTIME_CLEANUP_PENDING
				return@withTransaction
			}
			val accepted = exactActions.any { action -> action.status == LifecycleActionStatus.START_ACCEPTED.name }
			if (!accepted) {
				policyFailure = "NO_SOURCE_START_ACCEPTED"
				return@withTransaction
			}
			database.sourceSessionDao().updateSession(
				session.copy(state = SessionLifecycleState.ACTIVE.name, lifecycleRevision = session.lifecycleRevision + 1),
			)
			database.sourceSessionDao().updateServiceRun(
				requireNotNull(run).copy(
					state = SessionLifecycleState.ACTIVE.name,
					appliedForegroundCapabilityFlags = foregroundCapabilityFlags,
					runtimeAcknowledgement = LifecycleActionStatus.START_ACCEPTED.name,
					runtimeFailureCode = null,
					runRevision = run.runRevision + 1L,
				),
			)
		}
		return policyFailure
	}

	private suspend fun markStartFailed(
		logicalTrackingId: String,
		serviceRunId: String,
		completedAtMs: Long,
		failureCode: String,
		lease: LifecycleLeaseToken,
	) {
		database.withTransaction {
			requireLeaseInTransaction(lease)
			val session = requireNotNull(database.sourceSessionDao().session(logicalTrackingId))
			val terminalElapsedNanos = monotonicNowAtLeast(0L)
			val manifestRevision = requireNotNull(session.currentManifestRevision)
			check(database.sourceSessionDao().lifecycleActions(logicalTrackingId).none { action ->
				action.serviceRunId == serviceRunId &&
					action.manifestRevision == manifestRevision &&
					action.status == LifecycleActionStatus.CLEANUP_REQUIRED.name
			}) { "Runtime cleanup must finish before a service run becomes terminal" }
			val terminalIntentRevision = requireNotNull(session.currentIntentRevision) + 1L
			sourceBroker.retireSessionDemands(
				logicalTrackingId,
				lease.bootId,
				terminalElapsedNanos,
				completedAtMs,
			)
			database.sourceSessionDao().insertLifecycleIntent(
				stopIntent(
					session = session,
					manifestRevision = manifestRevision,
					intentRevision = terminalIntentRevision,
					reason = failureCode,
					bootId = lease.bootId,
					elapsedRealtimeNanos = terminalElapsedNanos,
					wallTimeMs = completedAtMs,
					deadlineElapsedRealtimeNanos = terminalElapsedNanos,
				),
			)
			supersedePendingActions(logicalTrackingId, completedAtMs, terminalElapsedNanos)
			database.sourceSessionDao().updateSession(
				session.copy(
					state = SessionLifecycleState.FAILED.name,
					lifecycleRevision = session.lifecycleRevision + 1,
					currentIntentRevision = terminalIntentRevision,
					lifecycleLeaseGeneration = lease.generation,
					lifecycleBootId = lease.bootId,
					completedAtMs = completedAtMs,
					failureCode = failureCode,
					currentServiceRunId = null,
				),
			)
			val run = requireNotNull(database.sourceSessionDao().serviceRun(serviceRunId))
			check(run.logicalTrackingId == logicalTrackingId && session.currentServiceRunId == serviceRunId) {
				"Failed start does not own the current service run"
			}
			database.sourceSessionDao().updateServiceRun(
				run.copy(
					state = SessionLifecycleState.FAILED.name,
					completedAtMs = completedAtMs,
					completionReason = failureCode,
					runtimeAcknowledgement = LifecycleActionStatus.TERMINAL_FAILURE.name,
					runtimeFailureCode = failureCode,
					runRevision = run.runRevision + 1L,
				),
			)
		}
	}

	private suspend fun acquireLease(
		ownerToken: String,
	): LifecycleLeaseToken? = database.withTransaction {
		val bootId = bootClockDomainProvider.current()
		val leaseNowElapsedNanos = clock.elapsedRealtimeNanos()
		val nowMs = clock.currentTimeMillis()
		val invalidClockSample = bootId.isBlank() || leaseNowElapsedNanos < 0L || nowMs < 0L
		val leaseWouldOverflow = leaseNowElapsedNanos > Long.MAX_VALUE - LEASE_DURATION_NANOS ||
			nowMs > Long.MAX_VALUE - LEASE_DURATION_MILLIS
		if (invalidClockSample || leaseWouldOverflow) {
			return@withTransaction null
		}
		val dao = database.sourceProjectionStateDao()
		val expires = leaseNowElapsedNanos + LEASE_DURATION_NANOS
		val expiresAtMs = nowMs + LEASE_DURATION_MILLIS
		val inserted = dao.insertLeaseIfAbsent(
			SourceCoordinatorLeaseEntity(
				leaseName = SESSION_LEASE,
				ownerToken = ownerToken,
				acquiredAtMs = nowMs,
				expiresAtMs = expiresAtMs,
				bootId = bootId,
				generation = 1L,
				acquiredElapsedRealtimeNanos = leaseNowElapsedNanos,
				expiresElapsedRealtimeNanos = expires,
			),
		)
		if (inserted < 0L && dao.acquireOrRenewLease(
				SESSION_LEASE,
				ownerToken,
				bootId,
				nowMs,
				expiresAtMs,
				leaseNowElapsedNanos,
				expires,
			) != 1
		) return@withTransaction null
		val current = requireNotNull(dao.lease(SESSION_LEASE))
		if (current.ownerToken != ownerToken || current.bootId != bootId) return@withTransaction null
		LifecycleLeaseToken(SESSION_LEASE, ownerToken, bootId, current.generation)
	}

	private suspend fun renewLease(lease: LifecycleLeaseToken) {
		val bootId = bootClockDomainProvider.current()
		val leaseNowElapsedNanos = clock.elapsedRealtimeNanos()
		val nowMs = clock.currentTimeMillis()
		check(bootId.isNotBlank() && bootId == lease.bootId && leaseNowElapsedNanos >= 0L && nowMs >= 0L &&
			leaseNowElapsedNanos <= Long.MAX_VALUE - LEASE_DURATION_NANOS &&
			nowMs <= Long.MAX_VALUE - LEASE_DURATION_MILLIS
		) { "Session coordinator lease clock unavailable" }
		check(
			database.sourceProjectionStateDao().acquireOrRenewLease(
				lease.leaseName,
				lease.ownerToken,
				lease.bootId,
				nowMs,
				nowMs + LEASE_DURATION_MILLIS,
				leaseNowElapsedNanos,
				leaseNowElapsedNanos + LEASE_DURATION_NANOS,
			) == 1,
		) { "Session coordinator lease lost" }
		check(database.sourceProjectionStateDao().lease(lease.leaseName)?.generation == lease.generation) {
			"Session coordinator lease generation changed"
		}
	}

	private suspend fun releaseLease(lease: LifecycleLeaseToken) {
		database.sourceProjectionStateDao().releaseLease(
			lease.leaseName,
			lease.ownerToken,
			lease.bootId,
			lease.generation,
			clock.currentTimeMillis().coerceAtLeast(0L),
			clock.elapsedRealtimeNanos().coerceAtLeast(0L),
		)
	}

	private suspend fun requireLeaseInTransaction(lease: LifecycleLeaseToken) {
		check(leaseIsCurrentInTransaction(lease)) { "Session coordinator lease lost" }
	}

	private suspend fun leaseIsCurrentInTransaction(lease: LifecycleLeaseToken): Boolean {
		val current = database.sourceProjectionStateDao().lease(lease.leaseName) ?: return false
		val bootId = bootClockDomainProvider.current()
		val nowElapsedNanos = clock.elapsedRealtimeNanos()
		return bootId.isNotBlank() && bootId == lease.bootId && nowElapsedNanos >= 0L &&
			current.ownerToken == lease.ownerToken &&
			current.bootId == lease.bootId &&
			current.generation == lease.generation &&
			current.expiresElapsedRealtimeNanos > nowElapsedNanos
	}

	private fun monotonicNowAtLeast(floorNanos: Long): Long =
		maxOf(floorNanos, clock.elapsedRealtimeNanos())

	/** Loads one exact immutable manifest envelope and rejects any persisted provenance drift. */
	private suspend fun verifiedManifest(
		logicalTrackingId: String,
		manifestRevision: Long,
		expectedServiceRunId: String,
	): VerifiedSessionManifest? {
		val dao = database.sourceSessionDao()
		val manifest = dao.rawManifestByServiceRunRevision(
			expectedServiceRunId,
			manifestRevision,
		).singleOrNull()?.validatedOrNull() ?: return null
		if (
			manifest.logicalTrackingId != logicalTrackingId ||
			manifest.manifestRevision != manifestRevision
		) return null
		return verifiedManifest(manifest, expectedServiceRunId)
	}

	private suspend fun verifiedManifest(
		manifest: SessionManifestVersionEntity,
		expectedServiceRunId: String,
	): VerifiedSessionManifest? {
		if (manifest.serviceRunId != expectedServiceRunId) return null
		val rawBindings = database.sourceSessionDao().rawManifestSources(
			manifest.logicalTrackingId,
			manifest.manifestRevision,
			MAX_RUN_RETIREMENT_MANIFEST_SOURCES + 1,
		)
		if (rawBindings.size > MAX_RUN_RETIREMENT_MANIFEST_SOURCES) return null
		val bindings = ArrayList<SessionManifestSourceEntity>(rawBindings.size)
		for (rawBinding in rawBindings) {
			bindings += rawBinding.validatedOrNull() ?: return null
		}
		if (!SessionManifestIntegrity.verify(manifest, bindings)) return null
		return VerifiedSessionManifest(manifest, bindings)
	}

	private suspend fun requireCurrentServiceRun(session: LogicalTrackingSessionEntity): SourceServiceRunEntity {
		val dao = database.sourceSessionDao()
		val serviceRunId = requireNotNull(session.currentServiceRunId) {
			"Active session has no exact current service run"
		}
		val run = requireNotNull(dao.serviceRun(serviceRunId)) { "Current service run is missing" }
		check(run.logicalTrackingId == session.logicalTrackingId) {
			"Current service run belongs to another logical session"
		}
		check(run.completedAtMs == null && run.state !in TERMINAL_STATES) {
			"Current service run is terminal"
		}
		return run
	}

	private suspend fun bindCurrentServiceRun(session: LogicalTrackingSessionEntity): BoundServiceRunTransition {
		val run = requireCurrentServiceRun(session)
		return BoundServiceRunTransition(session, run.serviceRunId, run.runRevision)
	}

	private suspend fun requireBoundServiceRun(
		bound: BoundServiceRunTransition,
		vararg allowedStates: SessionLifecycleState,
	): SourceServiceRunEntity {
		val dao = database.sourceSessionDao()
		val currentSession = requireNotNull(dao.session(bound.session.logicalTrackingId)) {
			"Bound logical session is missing"
		}
		val run = requireNotNull(dao.serviceRun(bound.serviceRunId)) { "Bound service run is missing" }
		check(run.logicalTrackingId == currentSession.logicalTrackingId) {
			"Bound service run belongs to another logical session"
		}
		check(currentSession.currentServiceRunId == run.serviceRunId) {
			"Bound service run is no longer current"
		}
		check(run.runRevision == bound.expectedRunRevision) { "Bound service run changed" }
		check(run.completedAtMs == null) { "Bound service run is already complete" }
		check(run.state in allowedStates.map(SessionLifecycleState::name)) {
			"Bound service run is in unexpected state ${run.state}"
		}
		return run
	}

	private suspend fun isStalePriorSession(
		session: LogicalTrackingSessionEntity,
		request: SessionStartRequest,
	): Boolean {
		val currentRun = session.currentServiceRunId
		val currentRunHasUnretiredProviderClaim = currentRun != null && database.sourceSessionDao()
			.lifecycleActions(session.logicalTrackingId)
			.asSequence()
			.filter { action ->
				action.serviceRunId == currentRun && action.sourceKind != null && action.attemptCount > 0 &&
					action.desiredState in setOf(ACTION_DESIRED_STARTED, ACTION_DESIRED_STOPPED) &&
					action.status in setOf(
						LifecycleActionStatus.APPLYING.name,
						LifecycleActionStatus.START_ACCEPTED.name,
						LifecycleActionStatus.CLEANUP_REQUIRED.name,
						LifecycleActionStatus.STOP_ACCEPTED.name,
					)
			}
			.groupBy { action -> requireNotNull(action.sourceKind) }
			.values
			.map { sourceActions -> sourceActions.maxBy(LifecycleDesiredActionEntity::actionRevision) }
			.any { latest -> latest.status != LifecycleActionStatus.STOP_ACCEPTED.name }
		if (currentRunHasUnretiredProviderClaim) {
			// A newer automatic request is not proof that the old provider registration is gone.
			// Recovery/finalization must first retire the latest exact ownership claim for every
			// source in this run. Historical claims followed by an accepted stop do not block.
			return false
		}
		if (session.currentManifestRevision == null || session.currentIntentRevision == null) return true
		val automatic = session.sessionMode == SessionMode.AUTOMATIC.name ||
			session.startOrigin == "AUTOMATIC_ACTIVITY_TRANSITION"
		// An ordinary duplicate start is not authority to replace a live manual session. The
		// service may receive the same shortcut/widget command more than once, and replacement
		// requires a separate explicit user action rather than a newly generated logical ID.
		if (!automatic) return false
		val requestedRun = request.serviceRunId
		return requestedRun == null || currentRun != requestedRun
	}

	private suspend fun finalizeInterruptedSession(
		session: LogicalTrackingSessionEntity,
		lease: LifecycleLeaseToken,
		wallTimeMs: Long,
		reason: String,
	) {
		database.withTransaction {
			requireLeaseInTransaction(lease)
			val dao = database.sourceSessionDao()
			val current = dao.session(session.logicalTrackingId) ?: return@withTransaction
			if (current.state in TERMINAL_STATES) return@withTransaction
			sourceBroker.retireSessionDemands(
				current.logicalTrackingId,
				lease.bootId,
				monotonicNowAtLeast(0L),
				wallTimeMs,
			)
			supersedePendingActions(current.logicalTrackingId, wallTimeMs, monotonicNowAtLeast(0L))
			val incompleteRuns = dao.incompleteServiceRuns(current.logicalTrackingId)
			val currentRun = current.currentServiceRunId?.let { runId ->
				requireNotNull(dao.serviceRun(runId)) { "Current service run is missing" }
			}
			check((currentRun == null && incompleteRuns.isEmpty()) ||
				(currentRun != null && incompleteRuns.map(SourceServiceRunEntity::serviceRunId) ==
					listOf(currentRun.serviceRunId))) {
				"Interrupted session has more than one incomplete service run"
			}
			currentRun?.let { run ->
				dao.updateServiceRun(
					run.copy(
						state = SessionLifecycleState.FINALIZED.name,
						completedAtMs = wallTimeMs,
						completionReason = reason,
						runtimeAcknowledgement = LifecycleActionStatus.TERMINAL_FAILURE.name,
						runtimeFailureCode = reason,
						runRevision = run.runRevision + 1L,
					),
				)
			}
			val manifestRevision = current.currentManifestRevision
			val currentIntentRevision = current.currentIntentRevision
			val nextIntentRevision = currentIntentRevision?.plus(1L)
			if (manifestRevision != null && nextIntentRevision != null) {
				dao.insertLifecycleIntent(
					stopIntent(
						current,
						manifestRevision,
						nextIntentRevision,
						reason,
						lease.bootId,
						monotonicNowAtLeast(0L),
						wallTimeMs,
						monotonicNowAtLeast(0L),
					),
				)
			}
			dao.updateSession(
				current.copy(
					state = SessionLifecycleState.FINALIZED.name,
					lifecycleRevision = current.lifecycleRevision + 1L,
					currentIntentRevision = nextIntentRevision ?: current.currentIntentRevision,
					lifecycleLeaseGeneration = lease.generation,
					lifecycleBootId = lease.bootId,
					completedAtMs = wallTimeMs,
					failureCode = reason,
					currentServiceRunId = null,
				),
			)
		}
	}

	private suspend fun supersedePendingActions(
		logicalTrackingId: String,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
	) {
		val dao = database.sourceSessionDao()
		dao.lifecycleActions(logicalTrackingId).filter { action ->
			action.status in setOf(
				LifecycleActionStatus.AWAITING_FOREGROUND.name,
				LifecycleActionStatus.PENDING.name,
				LifecycleActionStatus.APPLYING.name,
				LifecycleActionStatus.TEMPORARILY_ILLEGAL.name,
			)
		}.forEach { action ->
			dao.updateLifecycleAction(
				action.copy(
					status = LifecycleActionStatus.SUPERSEDED.name,
					acknowledgedAtMs = wallTimeMs,
					acknowledgedElapsedRealtimeNanos = elapsedRealtimeNanos,
					failureCode = "INTENT_SUPERSEDED",
				),
			)
		}
	}

	/** A successful whole-run retirement proves every older exact-attempt ownership obligation closed. */
	private suspend fun resolveCleanupRequiredActions(
		logicalTrackingId: String,
		serviceRunId: String,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
	) {
		val dao = database.sourceSessionDao()
		dao.lifecycleActions(logicalTrackingId).filter { action ->
			action.serviceRunId == serviceRunId &&
				(
					action.status == LifecycleActionStatus.CLEANUP_REQUIRED.name ||
						(action.status == LifecycleActionStatus.APPLYING.name && action.attemptCount > 0)
				)
		}.forEach { action ->
			check(dao.updateLifecycleAction(
				action.copy(
					status = LifecycleActionStatus.SUPERSEDED.name,
					acknowledgedAtMs = wallTimeMs,
					acknowledgedElapsedRealtimeNanos = elapsedRealtimeNanos,
					failureCode = "RUNTIME_CLEANUP_CONFIRMED_BY_STOP",
					retryTrigger = null,
				),
			) == 1)
		}
	}

	private fun preparedStartOwner(token: PreparedTrackingStartToken): String =
		"prepared-start:${token.value}"

	private fun serviceRunIdFor(request: SessionStartRequest): String =
		(request.serviceRunId ?: UUID.randomUUID().toString()).also { serviceRunId ->
			check(serviceRunId.isNotBlank()) { "Blank service-run identity cannot be admitted" }
			check(serviceRunId != LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID) {
				"Reserved legacy service-run identity cannot be admitted"
			}
		}

	private companion object {
		const val SESSION_LEASE = "tracking-session-coordinator"
		const val LEASE_DURATION_NANOS = 30_000L * 1_000_000L
		const val LEASE_DURATION_MILLIS = 30_000L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val ROLLBACK_QUIESCE_TIMEOUT_MS = 5_000L
		const val POLICY_PURPOSE_CAPTURE = "SESSION_CAPTURE"
		const val POLICY_PURPOSE_CONTROL = "CONTROL"
		const val ACTION_DESIRED_STARTED = "STARTED"
		const val ACTION_DESIRED_STOPPED = "STOPPED"
		const val RETIREMENT_CANCELLATION_STRIDE = 64
		const val RETIREMENT_MANIFEST_QUERY_CHUNK = 100
		const val MAX_STEPS_COMPLETENESS_ROWS = 64
		val TERMINAL_STATES = setOf(SessionLifecycleState.FINALIZED.name, SessionLifecycleState.FAILED.name)
		val TERMINAL_OR_STOPPING_STATES = TERMINAL_STATES + SessionLifecycleState.STOPPING.name
	}
}

private fun SessionManifestSourceEntity.isSessionCaptureWriter(): Boolean =
	(sourceKind == SourceKind.STEPS.stableCode || sourceKind == SourceKind.PRESSURE.stableCode) &&
		purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
		persistenceEligible && outputDestination != null && writerOwner != null &&
		writerOwnerGeneration != null

private fun SessionManifestSourceEntity.writerProvenanceIdentity(): List<Any?> = listOf(
	outputDestination,
	writerOwner,
	writerOwnerGeneration,
	writerProjectionId,
	writerProjectionVersion,
	writerBindingGeneration,
)

private fun SessionManifestSourceEntity.hasSameWriterProvenance(other: SessionManifestSourceEntity): Boolean =
	outputDestination == other.outputDestination &&
		writerOwner == other.writerOwner &&
		writerOwnerGeneration == other.writerOwnerGeneration &&
		writerProjectionId == other.writerProjectionId &&
		writerProjectionVersion == other.writerProjectionVersion &&
		writerBindingGeneration == other.writerBindingGeneration

private fun terminalRetirementIncomplete(
	acknowledgements: Collection<SourceStopAck>,
	logicalTrackingId: String,
	serviceRunId: String,
): Boolean = acknowledgements.any { acknowledgement ->
	!acknowledgement.hasMembership(logicalTrackingId, serviceRunId) ||
		!acknowledgement.hasTerminalLifecycleSettlement()
}

private fun SourceStopAck.hasMembership(logicalTrackingId: String, serviceRunId: String): Boolean =
	this.logicalTrackingId == logicalTrackingId && this.serviceRunId == serviceRunId

private fun SourceStopAck.hasTerminalLifecycleSettlement(): Boolean =
	if (source == SourceKind.STEPS) {
		hasTerminalStepsRetirement()
	} else {
		!hasIncompleteTerminalRetirement() ||
		(status in setOf(
			SourceStopStatus.PROCESS_RESTARTED,
			SourceStopStatus.PARTIAL_UNOBSERVABLE,
		) &&
			registrationRemovalOutcome in setOf(
				RegistrationRemovalOutcome.REMOVED,
				RegistrationRemovalOutcome.NOT_REGISTERED,
			) &&
			(status != SourceStopStatus.PARTIAL_UNOBSERVABLE ||
				appDrainComplete && lastAdmissionOrdinal != null))
	}

private fun SourceRunRetirementEntity.withRetirementAcknowledgement(
	acknowledgement: SourceStopAck,
	updatedAtMs: Long,
): SourceRunRetirementEntity {
	val terminal = acknowledgement.hasTerminalLifecycleSettlement()
	if (state != SourceRunRetirementEntity.STATE_REQUESTED) {
		check(toStopAckOrNullForReplay() == acknowledgement) {
			"Terminal source retirement receipt is immutable"
		}
		return this
	}
	if (acknowledgement.source == SourceKind.STEPS && !terminal) {
		return copy(
			appliedRevision = null,
			callbackEntryBarrierSequence = null,
			lastSourceSequence = null,
			lastAdmissionOrdinal = null,
			failedAdmissionCount = null,
			unresolvedSequenceStart = null,
			unresolvedSequenceEnd = null,
			registrationRemovalOutcome = null,
			providerFlushOutcome = null,
			providerCoverage = null,
			appDrainComplete = null,
			stopStatus = null,
			updatedAtMs = updatedAtMs,
		)
	}
	return copy(
		state = if (acknowledgement.status == SourceStopStatus.PROCESS_RESTARTED) {
			SourceRunRetirementEntity.STATE_INTERRUPTED
		} else {
			SourceRunRetirementEntity.STATE_ACKNOWLEDGED
		},
		appliedRevision = acknowledgement.appliedRevision,
		callbackEntryBarrierSequence = acknowledgement.callbackEntryBarrierSequence,
		lastSourceSequence = acknowledgement.lastDurablyAdmittedSequence,
		lastAdmissionOrdinal = acknowledgement.lastAdmissionOrdinal,
		failedAdmissionCount = acknowledgement.failedAdmissionCount,
		unresolvedSequenceStart = acknowledgement.unresolvedSequenceStart,
		unresolvedSequenceEnd = acknowledgement.unresolvedSequenceEndInclusive,
		registrationRemovalOutcome = acknowledgement.registrationRemovalOutcome.name,
		providerFlushOutcome = acknowledgement.providerFlushOutcome.name,
		providerCoverage = acknowledgement.providerCoverage.name,
		appDrainComplete = acknowledgement.appDrainComplete,
		stopStatus = acknowledgement.status.name,
		updatedAtMs = updatedAtMs,
	)
}

private fun SourceRunRetirementEntity.toStopAckOrNullForReplay(): SourceStopAck? {
	if (
		state in setOf(
			SourceRunRetirementEntity.STATE_REQUESTED,
			SourceRunRetirementEntity.STATE_CLEANUP_ONLY_COMPLETED,
		)
	) {
		return null
	}
	return SourceStopAck(
		source = SourceKind.entries.single { it.stableCode == sourceKind },
		sourceInstanceId = SourceInstanceId(sourceInstanceId),
		registrationGeneration = registrationGeneration,
		appliedRevision = appliedRevision,
		callbackEntryBarrierSequence = requireNotNull(callbackEntryBarrierSequence),
		lastDurablyAdmittedSequence = lastSourceSequence,
		lastAdmissionOrdinal = lastAdmissionOrdinal,
		failedAdmissionCount = requireNotNull(failedAdmissionCount),
		unresolvedSequenceStart = unresolvedSequenceStart,
		unresolvedSequenceEndInclusive = unresolvedSequenceEnd,
		registrationRemovalOutcome = RegistrationRemovalOutcome.valueOf(
			requireNotNull(registrationRemovalOutcome),
		),
		providerFlushOutcome = ProviderFlushOutcome.valueOf(requireNotNull(providerFlushOutcome)),
		providerCoverage = ProviderCoverage.valueOf(requireNotNull(providerCoverage)),
		appDrainComplete = requireNotNull(appDrainComplete),
		status = SourceStopStatus.valueOf(requireNotNull(stopStatus)),
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
	).takeIf { it.hasTerminalLifecycleSettlement() }
}

/** Allows capability/power degradation while rejecting any plan stronger than policy QoS. */
private fun SourcePolicyEntity.allows(plan: SourcePlan): Boolean {
	if (sourceKind != plan.source.stableCode) return false
	if (!enabled) return !plan.enabled
	if (!capturePersistenceEligible || captureConsentEpoch == null) return false
	if (!plan.enabled) return true
	return when (qosCode) {
		0 -> false
		1 -> when (plan) {
			is LocationPlan -> plan.mode in setOf(LocationMode.PASSIVE, LocationMode.LOW_POWER) &&
				plan.requestedIntervalMs >= 60_000L &&
				plan.minimumUpdateIntervalMs >= 30_000L &&
				plan.minimumDisplacementMeters >= 50f &&
				plan.maximumBatchDelayMs >= 120_000L
			is ActivityPlan -> plan.mode == ActivityMode.TRANSITIONS_ONLY &&
				plan.desiredDetectionLatencyMs >= 60_000L &&
				plan.confidenceThresholdPercent >= 75
			is StepsPlan -> plan.maximumReportLatencyMs >= 300_000L &&
				plan.projectionCheckpointIntervalMs >= 60_000L &&
				!plan.movementPolicyNeedsLowLatency
			is PressurePlan -> plan.hardwareSamplePeriodMicros >= 1_000_000 &&
				plan.maximumReportLatencyMicros >= 60_000_000 &&
				plan.aggregationWindowMs >= 60_000L
			is WifiPlan -> plan.mode.ordinal <= WifiMode.CACHED_ONLY.ordinal &&
				plan.minimumAttemptIntervalMs >= 15 * 60_000L &&
				plan.maximumAcceptableResultAgeMs >= 10 * 60_000L &&
				plan.unchangedResultDedupeWindowMs >= 30 * 60_000L &&
				plan.backoff.initialDelayMs >= 30_000L &&
				plan.backoff.maximumDelayMs >= 30 * 60_000L && plan.backoff.multiplier >= 2.0
			is CellPlan -> plan.mode.ordinal <= CellMode.OBSERVE_CHANGES.ordinal &&
				plan.minimumRefreshAttemptIntervalMs >= 15 * 60_000L &&
				plan.maximumAcceptableCachedAgeMs >= 10 * 60_000L &&
				plan.backoff.initialDelayMs >= 30_000L &&
				plan.backoff.maximumDelayMs >= 30 * 60_000L && plan.backoff.multiplier >= 2.0
		}
		2 -> when (plan) {
			is LocationPlan -> plan.mode.ordinal <= LocationMode.BALANCED.ordinal &&
				plan.requestedIntervalMs >= requireNotNull(locationMinTimeSeconds) * 1_000L &&
				plan.minimumUpdateIntervalMs >= requireNotNull(locationMinTimeSeconds) * 1_000L &&
				plan.minimumDisplacementMeters >= requireNotNull(locationMinDistanceMeters).toFloat() &&
				plan.maximumBatchDelayMs >= maxOf(10_000L, requireNotNull(locationMinTimeSeconds) * 5_000L)
			is ActivityPlan -> plan.mode == ActivityMode.TRANSITIONS_ONLY &&
				plan.desiredDetectionLatencyMs >= 30_000L &&
				plan.confidenceThresholdPercent >= 65
			is StepsPlan -> plan.maximumReportLatencyMs >= 60_000L &&
				plan.projectionCheckpointIntervalMs >= 15_000L &&
				!plan.movementPolicyNeedsLowLatency
			is PressurePlan -> plan.hardwareSamplePeriodMicros >= 200_000 &&
				plan.maximumReportLatencyMicros >= 10_000_000 &&
				plan.aggregationWindowMs >= 10_000L
			is WifiPlan -> plan.mode.ordinal <= WifiMode.BROADCAST_DRIVEN.ordinal &&
				plan.minimumAttemptIntervalMs >= 5 * 60_000L &&
				plan.maximumAcceptableResultAgeMs >= 5 * 60_000L &&
				plan.unchangedResultDedupeWindowMs >= 10 * 60_000L &&
				plan.backoff.initialDelayMs >= 30_000L &&
				plan.backoff.maximumDelayMs >= 30 * 60_000L && plan.backoff.multiplier >= 2.0
			is CellPlan -> plan.mode.ordinal <= CellMode.OBSERVE_CHANGES.ordinal &&
				plan.minimumRefreshAttemptIntervalMs >= 5 * 60_000L &&
				plan.maximumAcceptableCachedAgeMs >= 5 * 60_000L &&
				plan.backoff.initialDelayMs >= 30_000L &&
				plan.backoff.maximumDelayMs >= 30 * 60_000L && plan.backoff.multiplier >= 2.0
		}
		3 -> when (plan) {
			is LocationPlan -> plan.mode.ordinal <= LocationMode.PROBE.ordinal &&
				plan.requestedIntervalMs >= 1_000L &&
				plan.minimumUpdateIntervalMs >= 500L &&
				plan.minimumDisplacementMeters >= 2f &&
				plan.maximumBatchDelayMs >= 2_000L
			is ActivityPlan -> plan.mode.ordinal <= ActivityMode.CONTINUOUS_RECOGNITION.ordinal &&
				plan.desiredDetectionLatencyMs >= 5_000L &&
				plan.confidenceThresholdPercent >= 55
			is StepsPlan -> plan.maximumReportLatencyMs >= 5_000L &&
				plan.projectionCheckpointIntervalMs >= 2_000L
			is PressurePlan -> plan.hardwareSamplePeriodMicros >= 50_000 &&
				plan.maximumReportLatencyMicros >= 1_000_000 &&
				plan.aggregationWindowMs >= 2_000L
			is WifiPlan -> plan.mode.ordinal <= WifiMode.ACTIVE_ATTEMPTS.ordinal &&
				plan.minimumAttemptIntervalMs >= 60_000L &&
				plan.maximumAcceptableResultAgeMs >= 60_000L &&
				plan.unchangedResultDedupeWindowMs >= 2 * 60_000L &&
				plan.backoff.initialDelayMs >= 30_000L &&
				plan.backoff.maximumDelayMs >= 30 * 60_000L && plan.backoff.multiplier >= 2.0
			is CellPlan -> plan.mode.ordinal <= CellMode.OBSERVE_AND_SPARSE_REFRESH.ordinal &&
				plan.minimumRefreshAttemptIntervalMs >= 2 * 60_000L &&
				plan.maximumAcceptableCachedAgeMs >= 60_000L &&
				plan.backoff.initialDelayMs >= 30_000L &&
				plan.backoff.maximumDelayMs >= 30 * 60_000L && plan.backoff.multiplier >= 2.0
		}
		else -> false
	}
}

internal data class CurrentRecoverySourceCallerAuthority(
	val manifestIdentity: SourceCallerManifestIdentity,
	val reference: SourceCallerReplayReference,
)

internal sealed interface CurrentRecoverySourceCallerAuthorityResult {
	data class Available(
		val authority: CurrentRecoverySourceCallerAuthority,
	) : CurrentRecoverySourceCallerAuthorityResult

	data class Rejected(
		val failureCode: String,
		val disposition: TrackingStartFailureDisposition,
	) : CurrentRecoverySourceCallerAuthorityResult
}

@Suppress("ComplexCondition", "ReturnCount")
internal fun currentRecoverySourceCallerAuthorityCandidate(
	logicalTrackingId: String,
	serviceRunId: String,
	session: LogicalTrackingSessionEntity?,
	run: SourceServiceRunEntity?,
	manifest: SessionManifestVersionEntity?,
	bindings: List<SessionManifestSourceEntity>,
	intent: SessionLifecycleIntentVersionEntity?,
): CurrentRecoverySourceCallerAuthority? {
	if (session == null || run == null || manifest == null || intent == null) return null
	val activeOrReconfiguringRun =
		session.currentServiceRunId == serviceRunId &&
			session.state in setOf(
				SessionLifecycleState.ACTIVE.name,
				SessionLifecycleState.RECONFIGURING.name,
			) &&
			run.state in setOf(
				SessionLifecycleState.ACTIVE.name,
				SessionLifecycleState.RECONFIGURING.name,
			) &&
			run.completedAtMs == null &&
			run.runtimeAcknowledgement == LifecycleActionStatus.START_ACCEPTED.name
	val completedSuspension =
		session.currentServiceRunId == null &&
			session.state == SessionLifecycleState.ACTIVE.name &&
			session.cutoffAtMs == null &&
			session.cutoffElapsedNanos == null &&
			session.finalAdmissionOrdinal == null &&
			run.state == SessionLifecycleState.FINALIZED.name &&
			run.completedAtMs != null &&
			run.runtimeAcknowledgement == LifecycleActionStatus.STOP_ACCEPTED.name
	if (
		session.logicalTrackingId != logicalTrackingId ||
		session.sessionMode != SessionMode.MANUAL.name ||
		session.state !in setOf(
			SessionLifecycleState.ACTIVE.name,
			SessionLifecycleState.RECONFIGURING.name,
		) ||
		session.completedAtMs != null ||
		session.currentManifestRevision != manifest.manifestRevision ||
		session.currentIntentRevision != intent.intentRevision ||
		session.lifecycleLeaseGeneration <= 0L ||
		session.lifecycleBootId.isNullOrBlank() ||
		(!activeOrReconfiguringRun && !completedSuspension)
	) return null
	if (
		run.logicalTrackingId != logicalTrackingId ||
		run.serviceRunId != serviceRunId ||
		!run.startIsUserInitiated ||
		run.bootId != session.lifecycleBootId ||
		run.leaseGeneration != session.lifecycleLeaseGeneration ||
		run.rolloutRevision != session.rolloutRevision ||
		run.desiredPlanRevision != session.desiredPlanRevision ||
		run.androidDeliveryState != AndroidStartDeliveryState.FOREGROUND_ACCEPTED.name
	) return null
	if (
		manifest.logicalTrackingId != logicalTrackingId ||
		manifest.serviceRunId != serviceRunId ||
		manifest.manifestRevision != session.currentManifestRevision ||
		manifest.sessionMode != SessionMode.MANUAL.name ||
		manifest.acquisitionPlanRevision != session.desiredPlanRevision ||
		manifest.rolloutRevision != session.rolloutRevision ||
		manifest.effectiveBootId != session.lifecycleBootId ||
		!SessionManifestIntegrity.verify(manifest, bindings)
	) return null
	if (
		intent.logicalTrackingId != logicalTrackingId ||
		intent.intentRevision != session.currentIntentRevision ||
		intent.manifestRevision != manifest.manifestRevision ||
		intent.desiredState != LifecycleDesiredState.ACTIVE.name ||
		intent.requestBootId != session.lifecycleBootId ||
		intent.requestedElapsedRealtimeNanos < 0L ||
		intent.requestedWallTimeMs < 0L ||
		(intent.stopReason == null && intent.startOrigin != manifest.startOrigin) ||
		(completedSuspension && intent.stopReason.isNullOrBlank()) ||
		(completedSuspension && run.completionReason != intent.stopReason) ||
		(activeOrReconfiguringRun && intent.stopReason != null) ||
		!intent.hasAuthenticRecoveryCallerIntent()
	) return null
	return CurrentRecoverySourceCallerAuthority(
		manifestIdentity = SourceCallerManifestIdentity(logicalTrackingId, manifest.manifestRevision),
		reference = SourceCallerReplayReference(
			intent.sourceCallerAuthorityReference ?: return null,
		),
	)
}

private fun SessionLifecycleIntentVersionEntity.hasAuthenticRecoveryCallerIntent(): Boolean {
	val reference = sourceCallerAuthorityReference?.takeIf(String::isNotBlank) ?: return false
	if (
		automationEpoch != null ||
		triggerId != null ||
		triggerKind != null ||
		triggerBootId != null ||
		triggerObservedElapsedRealtimeNanos != null ||
		triggerReceivedElapsedRealtimeNanos != null ||
		triggerExpiresElapsedRealtimeNanos != null ||
		triggerCollectedDataEpoch != null
	) return false
	val hasNoStopDeadline = stopDeadlineBootId == null && stopDeadlineElapsedRealtimeNanos == null
	val ordinaryActiveChecksum =
		startOrigin in setOf(
			SessionStartOrigin.MANUAL_FOREGROUND_START.name,
			SessionStartOrigin.RECOVERY.name,
			SessionStartOrigin.POLICY_RECONCILIATION.name,
		) &&
		stopReason == null &&
		hasNoStopDeadline &&
		intentChecksum == stableLifecycleChecksum(
			logicalTrackingId,
			intentRevision,
			manifestRevision,
			desiredState,
			startOrigin,
			requestBootId,
			requestedElapsedRealtimeNanos,
			requestedWallTimeMs,
			triggerId,
			automationEpoch,
			triggerCollectedDataEpoch,
			reference,
		)
	val suspendedRecoveryChecksum =
		startOrigin == SessionStartOrigin.RECOVERY.name &&
			!stopReason.isNullOrBlank() &&
			hasNoStopDeadline &&
			triggerId == null &&
			triggerKind == null &&
			triggerBootId == null &&
			triggerObservedElapsedRealtimeNanos == null &&
			triggerReceivedElapsedRealtimeNanos == null &&
			triggerExpiresElapsedRealtimeNanos == null &&
			intentChecksum == stableLifecycleChecksum(
				logicalTrackingId,
				intentRevision,
				manifestRevision,
				desiredState,
				stopReason,
				requestBootId,
				requestedElapsedRealtimeNanos,
				reference,
			)
	return ordinaryActiveChecksum || suspendedRecoveryChecksum
}

private val SourceStartResult.applied: AppliedSourcePlan
	get() = when (this) {
		is SourceStartResult.Started -> applied
		is SourceStartResult.Degraded -> applied
		is SourceStartResult.Blocked -> applied
		is SourceStartResult.Failed -> applied
	}

private val SourceApplyResult.applied: AppliedSourcePlan
	get() = when (this) {
		is SourceApplyResult.Applied -> state
		is SourceApplyResult.Degraded -> state
		is SourceApplyResult.RolledBack -> state
		is SourceApplyResult.Failed -> state
	}

private fun failedApplied(plan: SourcePlan, elapsedNanos: Long, status: SourceApplyStatus) = AppliedSourcePlan(
	desiredRevision = plan.revision,
	appliedRevision = null,
	source = plan.source,
	sourceInstanceId = null,
	registrationGeneration = null,
	appliedAtElapsedRealtimeNanos = elapsedNanos,
	status = status,
)

private fun disabledApplied(plan: SourcePlan, elapsedNanos: Long) = AppliedSourcePlan(
	desiredRevision = plan.revision,
	appliedRevision = plan.revision,
	source = plan.source,
	sourceInstanceId = null,
	registrationGeneration = null,
	appliedAtElapsedRealtimeNanos = elapsedNanos,
	status = SourceApplyStatus.APPLIED,
)

private fun CatalogSourcePlanDecision.Blocked.blockedApplied(
	elapsedNanos: Long,
): AppliedSourcePlan = failedApplied(
	requestedPlan,
	elapsedNanos,
	SourceApplyStatus.BLOCKED,
).copy(degradedReasons = reasons)

private fun CatalogSourcePlanDecision.Blocked.toBlockedExecution(
	elapsedNanos: Long,
): SourceActionExecution = SourceActionExecution(
	applied = blockedApplied(elapsedNanos),
	status = LifecycleActionStatus.TERMINAL_FAILURE,
	failureCode = failure.code,
)

private fun SourceActionExecution.withCatalogDecision(
	decision: CatalogSourcePlanDecision.Accepted,
): SourceActionExecution {
	if (decision.degradedReasons.isEmpty()) return this
	return copy(
		applied = applied.copy(
			status = if (applied.status == SourceApplyStatus.APPLIED) {
				SourceApplyStatus.DEGRADED
			} else {
				applied.status
			},
			degradedReasons = applied.degradedReasons + decision.degradedReasons,
		),
	)
}

private fun PersistedLifecycleIntent.activeCaptureSources(): Set<SourceKind> =
	demands.asSequence()
		.filter { demand ->
			demand.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
				demand.status == SourceDemandEntity.STATUS_ACTIVE
		}
		.mapNotNullTo(linkedSetOf()) { demand ->
			SourceKind.entries.firstOrNull { source -> source.stableCode == demand.sourceKind }
		}

private fun SourcePlan.disabledForCatalog(): SourcePlan = when (this) {
	is LocationPlan -> copy(mode = LocationMode.DISABLED)
	is ActivityPlan -> copy(mode = ActivityMode.OFF)
	is StepsPlan -> copy(enabled = false)
	is PressurePlan -> copy(enabled = false)
	is WifiPlan -> copy(mode = WifiMode.OFF)
	is CellPlan -> copy(mode = CellMode.OFF)
}

private fun List<AppliedSourcePlan>.desiredStatus(): DesiredPlanStatus = when {
	all { it.status == SourceApplyStatus.APPLIED } -> DesiredPlanStatus.EFFECTIVE
	any { it.status in setOf(SourceApplyStatus.FAILED, SourceApplyStatus.BLOCKED) } -> DesiredPlanStatus.DEGRADED
	else -> DesiredPlanStatus.DEGRADED
}

private fun runRetirementAck(
	source: SourceKind,
	logicalTrackingId: String,
	serviceRunId: String,
	status: SourceStopStatus,
) = SourceStopAck(
	source = source,
	sourceInstanceId = SourceInstanceId(
		if (status == SourceStopStatus.COMPLETE) {
			"not-owned-${source.name.lowercase()}"
		} else {
			"unresolved-${source.name.lowercase()}"
		},
	),
	registrationGeneration = 0L,
	appliedRevision = null,
	callbackEntryBarrierSequence = 0L,
	lastDurablyAdmittedSequence = null,
	lastAdmissionOrdinal = null,
	failedAdmissionCount = if (status == SourceStopStatus.COMPLETE) 0L else 1L,
	unresolvedSequenceStart = null,
	unresolvedSequenceEndInclusive = null,
	registrationRemovalOutcome = if (status == SourceStopStatus.COMPLETE) {
		RegistrationRemovalOutcome.NOT_REGISTERED
	} else {
		RegistrationRemovalOutcome.UNOBSERVABLE
	},
	providerFlushOutcome = if (status == SourceStopStatus.TIMED_OUT) {
		ProviderFlushOutcome.TIMED_OUT
	} else {
		ProviderFlushOutcome.NOT_REQUESTED
	},
	providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
	appDrainComplete = status == SourceStopStatus.COMPLETE,
	status = status,
	logicalTrackingId = logicalTrackingId,
	serviceRunId = serviceRunId,
)

private class SourceCallerDemandRejectedException(
	val reason: SourceCallerRejectionReason,
) : IllegalStateException(reason.name) {
	val failureCode: String = "SOURCE_CALLER_GUARD_${reason.name}"
	val disposition: TrackingStartFailureDisposition =
		if (reason.isRetryable) {
			TrackingStartFailureDisposition.RETRYABLE
		} else {
			TrackingStartFailureDisposition.TERMINAL
		}
}

private fun PersistedLifecycleIntent.withSourceCallerReceipt(
	receipt: SourceCallerAcceptanceReceipt,
): PersistedLifecycleIntent {
	val reference = receipt.reference.value
	return copy(
		intent = intent.copy(
			sourceCallerAuthorityReference = reference,
			intentChecksum = stableLifecycleChecksum(
				intent.logicalTrackingId,
				intent.intentRevision,
				intent.manifestRevision,
				intent.desiredState,
				intent.startOrigin,
				intent.requestBootId,
				intent.requestedElapsedRealtimeNanos,
				intent.requestedWallTimeMs,
				intent.triggerId,
				intent.automationEpoch,
				intent.triggerCollectedDataEpoch,
				reference,
			),
		),
	)
}

private fun PersistedLifecycleIntent.sourceCallerAuthorityReference(): SourceCallerReplayReference =
	SourceCallerReplayReference(requireNotNull(intent.sourceCallerAuthorityReference))

enum class SessionLifecycleState {
	IDLE,
	STARTING,
	ACTIVE,
	RECONFIGURING,
	STOPPING,
	FINALIZED,
	FAILED,
}

enum class SessionStartOrigin {
	MANUAL_FOREGROUND_START,
	AUTOMATIC_BACKGROUND_START,
	RECOVERY,
	POLICY_RECONCILIATION,
}

internal data class CatalogStartPrerequisiteFailure(
	val code: String,
	val disposition: TrackingStartFailureDisposition,
)

internal sealed interface CatalogSourcePlanDecision {
	val requestedPlan: SourcePlan

	data class Disabled(
		override val requestedPlan: SourcePlan,
	) : CatalogSourcePlanDecision

	data class Accepted(
		override val requestedPlan: SourcePlan,
		val effectivePlan: SourcePlan,
		val degradedReasons: Set<SourceDegradedReason>,
	) : CatalogSourcePlanDecision {
		init {
			require(requestedPlan.enabled)
			require(effectivePlan.enabled)
			require(requestedPlan.source == effectivePlan.source)
			require(requestedPlan.revision == effectivePlan.revision)
		}
	}

	data class Blocked(
		override val requestedPlan: SourcePlan,
		val availability: SourceCatalogAvailability?,
		val reasons: Set<SourceDegradedReason>,
		val failure: CatalogStartPrerequisiteFailure,
	) : CatalogSourcePlanDecision {
		init {
			require(requestedPlan.enabled)
			require(reasons.isNotEmpty())
		}
	}
}

internal data class CatalogPlanDecisions(
	val bySource: Map<SourceKind, CatalogSourcePlanDecision>,
) {
	val acceptedSources: Set<SourceKind> = bySource.values
		.filterIsInstance<CatalogSourcePlanDecision.Accepted>()
		.mapTo(linkedSetOf()) { decision -> decision.requestedPlan.source }

	fun getValue(source: SourceKind): CatalogSourcePlanDecision = bySource.getValue(source)

	fun failureIfNoAcceptedSource(): CatalogStartPrerequisiteFailure? {
		val enabled = bySource.values.filterNot { it is CatalogSourcePlanDecision.Disabled }
		if (enabled.any { it is CatalogSourcePlanDecision.Accepted }) return null
		return enabled.filterIsInstance<CatalogSourcePlanDecision.Blocked>()
			.minByOrNull { decision -> decision.requestedPlan.source.stableCode }
			?.failure
	}

	fun retryableFailure(): CatalogReconfigurationRetryDebt? {
		val retryable = bySource.values
			.filterIsInstance<CatalogSourcePlanDecision.Blocked>()
			.filter { decision ->
				decision.failure.disposition == TrackingStartFailureDisposition.RETRYABLE
			}
		val failure = retryable.minByOrNull { decision ->
			decision.requestedPlan.source.stableCode
		} ?: return null
		return CatalogReconfigurationRetryDebt(
			failure = failure.failure,
			sources = retryable.mapTo(linkedSetOf()) { decision ->
				decision.requestedPlan.source
			},
		)
	}

	fun constrainedToAcceptedSources(
		acceptedSources: Set<SourceKind>,
	): CatalogPlanDecisions = CatalogPlanDecisions(
		bySource.mapValues { (source, decision) ->
			if (decision !is CatalogSourcePlanDecision.Accepted || source in acceptedSources) {
				decision
			} else {
				CatalogSourcePlanDecision.Blocked(
					requestedPlan = decision.requestedPlan,
					availability = null,
					reasons = setOf(SourceDegradedReason.CATALOG_NOT_ACCEPTED),
					failure = CatalogStartPrerequisiteFailure(
						"SOURCE_CATALOG_${source.name}_SESSION_CAPTURE_NOT_ACCEPTED",
						TrackingStartFailureDisposition.TERMINAL,
					),
				)
			}
		},
	)
}

internal data class CatalogReconfigurationRetryDebt(
	val failure: CatalogStartPrerequisiteFailure,
	val sources: Set<SourceKind>,
) {
	init {
		require(failure.disposition == TrackingStartFailureDisposition.RETRYABLE)
		require(sources.isNotEmpty())
	}
}

internal suspend fun SourceRuntimeRegistry.catalogPlanDecisions(
	plan: AcquisitionPlanRevision,
	tier: SourceAvailabilityTier,
): CatalogPlanDecisions = CatalogPlanDecisions(
	plan.plans.values
		.sortedBy { sourcePlan -> sourcePlan.source.stableCode }
		.associate { sourcePlan ->
			sourcePlan.source to if (!sourcePlan.enabled) {
				CatalogSourcePlanDecision.Disabled(sourcePlan)
			} else {
				try {
					availability(
						SourceAvailabilityRequest(
							source = sourcePlan.source,
							purpose = TrackingPurpose.SESSION_CAPTURE,
							plan = sourcePlan,
							tier = tier,
						),
					).toCatalogPlanDecision(sourcePlan)
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (_: Exception) {
					CatalogSourcePlanDecision.Blocked(
						requestedPlan = sourcePlan,
						availability = null,
						reasons = setOf(SourceDegradedReason.AVAILABILITY_READ_FAILED),
						failure = CatalogStartPrerequisiteFailure(
							"SOURCE_CATALOG_${sourcePlan.source.name}_AVAILABILITY_READ_FAILED",
							TrackingStartFailureDisposition.RETRYABLE,
						),
					)
				}
			}
		},
)

private fun SourceCatalogAvailability.toCatalogPlanDecision(
	requestedPlan: SourcePlan,
): CatalogSourcePlanDecision = when (this) {
	is SourceCatalogAvailability.Unsupported -> CatalogSourcePlanDecision.Blocked(
		requestedPlan = requestedPlan,
		availability = this,
		reasons = setOf(SourceDegradedReason.CATALOG_UNSUPPORTED),
		failure = requireNotNull(toStartPrerequisiteFailure(requestedPlan.source)),
	)
	is SourceCatalogAvailability.Executable -> when (val result = availability) {
		is SourceProviderAvailability.Available -> CatalogSourcePlanDecision.Accepted(
			requestedPlan = requestedPlan,
			effectivePlan = requestedPlan,
			degradedReasons = result.degradedReasons,
		)
		is SourceProviderAvailability.Degraded -> CatalogSourcePlanDecision.Accepted(
			requestedPlan = requestedPlan,
			effectivePlan = result.effectivePlan,
			degradedReasons = result.evidence.reasons(),
		)
		else -> CatalogSourcePlanDecision.Blocked(
			requestedPlan = requestedPlan,
			availability = this,
			reasons = result.blockingReasons(),
			failure = requireNotNull(toStartPrerequisiteFailure(requestedPlan.source)),
		)
	}
}

private fun SourceProviderAvailability.blockingReasons():
	Set<SourceDegradedReason> = when (this) {
	is SourceProviderAvailability.PermissionRequired -> setOf(
		SourceDegradedReason.PERMISSION_MISSING,
	)
	is SourceProviderAvailability.ProviderUnavailable ->
		evidence.reasons().ifEmpty {
			setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE)
		}
	is SourceProviderAvailability.OsLimited ->
		evidence.reasons().ifEmpty {
			setOf(SourceDegradedReason.PLATFORM_THROTTLED)
		}
	is SourceProviderAvailability.HardwareUnavailable ->
		evidence.reasons().ifEmpty {
			setOf(SourceDegradedReason.HARDWARE_UNAVAILABLE)
		}
	is SourceProviderAvailability.Contained -> setOf(
		SourceDegradedReason.CATALOG_CONTAINED,
	)
	is SourceProviderAvailability.Available,
	is SourceProviderAvailability.Degraded,
	-> error("Available source has no blocking reasons")
}

private fun SourceProviderAvailabilityEvidence.reasons():
	Set<SourceDegradedReason> = when (this) {
	is SourceProviderAvailabilityEvidence.Runtime -> reasons
	is SourceProviderAvailabilityEvidence.Location -> reasons
	is SourceProviderAvailabilityEvidence.AmbientSteps -> emptySet()
}

private fun SessionStartOrigin.toSourceAvailabilityTier(): SourceAvailabilityTier = when (this) {
	SessionStartOrigin.MANUAL_FOREGROUND_START -> SourceAvailabilityTier.MANUAL_FOREGROUND_START
	SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
	SessionStartOrigin.RECOVERY,
	-> SourceAvailabilityTier.AUTOMATIC_BACKGROUND_START
	SessionStartOrigin.POLICY_RECONCILIATION -> SourceAvailabilityTier.SESSION_ALREADY_FOREGROUND
}

internal fun SourceCatalogAvailability.toStartPrerequisiteFailure(
	source: SourceKind,
): CatalogStartPrerequisiteFailure? = when (this) {
	is SourceCatalogAvailability.Unsupported -> CatalogStartPrerequisiteFailure(
		code = "SOURCE_CATALOG_${source.name}_${purpose.name}_UNSUPPORTED",
		disposition = TrackingStartFailureDisposition.TERMINAL,
	)
	is SourceCatalogAvailability.Executable -> {
		val code = when (availability) {
			is SourceProviderAvailability.Available,
			is SourceProviderAvailability.Degraded,
			-> null
			is SourceProviderAvailability.PermissionRequired ->
				"SOURCE_CATALOG_${source.name}_SESSION_CAPTURE_PERMISSION_REQUIRED"
			is SourceProviderAvailability.ProviderUnavailable ->
				"SOURCE_CATALOG_${source.name}_SESSION_CAPTURE_PROVIDER_UNAVAILABLE"
			is SourceProviderAvailability.OsLimited ->
				"SOURCE_CATALOG_${source.name}_SESSION_CAPTURE_OS_LIMITED"
			is SourceProviderAvailability.HardwareUnavailable ->
				"SOURCE_CATALOG_${source.name}_SESSION_CAPTURE_HARDWARE_UNAVAILABLE"
			is SourceProviderAvailability.Contained ->
				"SOURCE_CATALOG_${source.name}_SESSION_CAPTURE_CONTAINED"
		}
		code?.let {
			CatalogStartPrerequisiteFailure(
				code = it,
				disposition = when (availability) {
					is SourceProviderAvailability.ProviderUnavailable,
					is SourceProviderAvailability.OsLimited,
					-> TrackingStartFailureDisposition.RETRYABLE
					else -> TrackingStartFailureDisposition.TERMINAL
				},
			)
		}
	}
}

data class SessionStartRequest(
	val ownerToken: String,
	val origin: SessionStartOrigin,
	val plan: AcquisitionPlanRevision,
	val rolloutRevision: Long,
	val clockDomainId: String,
	val foregroundCapabilityFlags: Long,
	val wallTimeMs: Long,
	val elapsedRealtimeNanos: Long,
	val zoneId: String,
	val controlDependencies: Set<SourceKind> = emptySet(),
	val automaticTrigger: AutomaticTrackingStartTrigger? = null,
	val logicalTrackingId: String? = null,
	val serviceRunId: String? = null,
	val continuationAuthority: ServiceRunContinuationAuthority? = null,
)

/** Exact authority to replace one active manual Android-service run without changing its session. */
data class ServiceRunContinuationAuthority(
	val previousServiceRunId: String,
	val previousDeliveryToken: PreparedTrackingStartToken? = null,
	val previousCommandGeneration: Long? = null,
) {
	init {
		require(previousServiceRunId.isNotBlank())
		require((previousDeliveryToken == null) == (previousCommandGeneration == null)) {
			"Delivery token and command generation must be supplied together"
		}
		require(previousCommandGeneration == null || previousCommandGeneration > 0L)
	}
}

data class AndroidStartDeliveryMetadata(
	val token: PreparedTrackingStartToken,
	val commandGeneration: Long,
	val isUserInitiated: Boolean,
	val isAmbient: Boolean,
) {
	init {
		require(commandGeneration > 0L)
	}
}

enum class AndroidStartDeliveryState {
	PREPARED,
	ENQUEUED,
	DELIVERED,
	FOREGROUND_ACCEPTED,
	TERMINAL_FAILURE,
}

data class PreparedSessionStart(
	val token: PreparedTrackingStartToken,
	val logicalTrackingId: String,
	val serviceRunId: String,
	val manifestRevision: Long,
	val intentRevision: Long,
	val sourceCallerAuthorityReference: SourceCallerReplayReference,
)

sealed interface SessionStartPreparationResult {
	data class Prepared(val start: PreparedSessionStart) : SessionStartPreparationResult
	data object AlreadyActive : SessionStartPreparationResult
	data object Busy : SessionStartPreparationResult
	data class Rejected(
		val failureCode: String,
		val disposition: TrackingStartFailureDisposition =
			TrackingStartFailureDisposition.TERMINAL,
	) : SessionStartPreparationResult
}

data class ClaimedPreparedSessionStart(
	val token: PreparedTrackingStartToken,
	val logicalTrackingId: String,
	val serviceRunId: String,
	val manifestRevision: Long,
	val intentRevision: Long,
	val planRevision: Long,
	val sourcePolicyRevision: Long,
	val startOrigin: SessionStartOrigin,
	val sessionMode: SessionMode,
	val acceptedSources: Set<SourceKind>,
	val desiredForegroundCapabilityFlags: Long,
	val intent: SessionLifecycleIntentVersionEntity,
	val automaticTrigger: AutomaticTrackingStartTrigger?,
	val isUserInitiated: Boolean,
	val isAmbient: Boolean,
	val alreadyForegroundAccepted: Boolean,
)

sealed interface PreparedSessionClaimResult {
	data class Claimed(val start: ClaimedPreparedSessionStart) : PreparedSessionClaimResult
	data class Rejected(val failureCode: String) : PreparedSessionClaimResult
}

sealed interface SessionStartResult {
	data class Started(
		val logicalTrackingId: String,
		val serviceRunId: String,
		val applied: List<AppliedSourcePlan>,
		val planStatus: DesiredPlanStatus,
		val sourceCallerAuthorityReference: SourceCallerReplayReference? = null,
	) : SessionStartResult
	data class Failed(
		val logicalTrackingId: String,
		val serviceRunId: String,
		val applied: List<AppliedSourcePlan>,
		val code: String,
	) : SessionStartResult
	data object AlreadyActive : SessionStartResult
	data object Busy : SessionStartResult
	data class InvalidRollout(val code: String) : SessionStartResult
	data class InvalidPolicy(val code: String) : SessionStartResult
	data class InvalidIntent(
		val code: String,
		val disposition: TrackingStartFailureDisposition =
			TrackingStartFailureDisposition.TERMINAL,
	) : SessionStartResult
}

data class SessionReconfigureRequest(
	val ownerToken: String,
	val plan: AcquisitionPlanRevision,
	val wallTimeMs: Long,
	val elapsedRealtimeNanos: Long,
	val clockDomainId: String,
	val zoneId: String,
	val foregroundCapabilityFlags: Long,
	val controlDependencies: Set<SourceKind> = emptySet(),
)

sealed interface SessionReconfigureResult {
	data class Applied(
		val revision: Long,
		val applied: List<AppliedSourcePlan>,
		val status: DesiredPlanStatus,
		val sourceCallerAuthorityReference: SourceCallerReplayReference,
	) : SessionReconfigureResult
	data class Failed(
		val revision: Long,
		val applied: List<AppliedSourcePlan>,
		val failureCode: String,
		val sourceCallerAuthorityReference: SourceCallerReplayReference? = null,
	) : SessionReconfigureResult
	data class Retryable(
		val revision: Long,
		val failureCode: String,
		val sources: Set<SourceKind>,
	) : SessionReconfigureResult {
		init {
			require(sources.isNotEmpty())
		}
	}
	data class InvalidState(val state: String) : SessionReconfigureResult
	data object NoActiveSession : SessionReconfigureResult
	data object Busy : SessionReconfigureResult
	data class InvalidRollout(val code: String) : SessionReconfigureResult
	data class InvalidPolicy(val code: String) : SessionReconfigureResult
	data class InvalidIntent(val code: String) : SessionReconfigureResult
}

private fun TrackingRolloutState.validateEventPlan(
	expectedRevision: Long,
	plan: AcquisitionPlanRevision,
	captureMode: CaptureReachabilityMode?,
): String? = when {
	revision != expectedRevision -> "ROLLOUT_REVISION_MISMATCH"
	coordinatorMode != CoordinatorMode.EVENT -> "EVENT_COORDINATOR_DISABLED"
	plan.plans.values.any { sourcePlan ->
		sourcePlan.enabled && sourceOwners[sourcePlan.source] != SourceOwner.EVENT
	} -> "EVENT_SOURCE_NOT_OWNED"
	captureMode != null && plan.plans.values.any { sourcePlan ->
		sourcePlan.enabled && captureModeMasks.getValue(sourcePlan.source) and captureMode.mask == 0L
	} -> "EVENT_CAPTURE_MODE_NOT_REACHABLE"
	else -> null
}

private fun SessionMode.captureReachabilityModeOrNull(): CaptureReachabilityMode? = when (this) {
	SessionMode.MANUAL -> CaptureReachabilityMode.MANUAL_SESSION_CAPTURE
	SessionMode.AUTOMATIC -> CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE
	SessionMode.LEGACY_UNKNOWN -> null
}

data class SessionStopRequest(
	val ownerToken: String,
	val reason: String,
	val wallTimeMs: Long,
	val elapsedRealtimeNanos: Long,
	val clockDomainId: String,
	val gracePeriodMs: Long = 10_000L,
	val perSourceTimeoutMs: Long = 2_000L,
)

sealed interface SessionStopResult {
	data class Stopped(
		val logicalTrackingId: String,
		val finalAdmissionOrdinal: Long,
		val acknowledgements: List<SourceStopAck>,
	) : SessionStopResult
	data class CleanupPending(
		val logicalTrackingId: String,
		val requiredOrdinal: Long,
		val acknowledgements: List<SourceStopAck>,
	) : SessionStopResult
	data class DrainPending(
		val logicalTrackingId: String,
		val requiredOrdinal: Long,
		val source: SourceKind? = null,
		val reason: String? = null,
		val sourceResults: List<SourceProductDrainResult> = emptyList(),
		val sourceMemberships: List<SourceDrainMembership> = emptyList(),
	) : SessionStopResult
	/** The requested stop could not be durably represented without violating lifecycle intent. */
	data class InvalidIntent(val code: String) : SessionStopResult
	data object NoActiveSession : SessionStopResult
	data object Busy : SessionStopResult
}

data class SessionSuspendRequest(
	val ownerToken: String,
	val reason: String,
	val wallTimeMs: Long,
	val elapsedRealtimeNanos: Long,
	val clockDomainId: String,
	val gracePeriodMs: Long = 10_000L,
	val perSourceTimeoutMs: Long = 2_000L,
)

sealed interface SessionSuspendResult {
	data class Suspended(
		val logicalTrackingId: String,
		val finalAdmissionOrdinal: Long,
		val acknowledgements: List<SourceStopAck>,
		val incomplete: Boolean,
		val sourceCallerAuthorityReference: SourceCallerReplayReference? = null,
	) : SessionSuspendResult
	data class CleanupPending(
		val logicalTrackingId: String,
		val requiredOrdinal: Long,
		val acknowledgements: List<SourceStopAck>,
		val sourceCallerAuthorityReference: SourceCallerReplayReference? = null,
	) : SessionSuspendResult
	data class DrainPending(
		val logicalTrackingId: String,
		val requiredOrdinal: Long,
		val source: SourceKind? = null,
		val reason: String? = null,
		val sourceResults: List<SourceProductDrainResult> = emptyList(),
		val sourceMemberships: List<SourceDrainMembership> = emptyList(),
		val sourceCallerAuthorityReference: SourceCallerReplayReference? = null,
	) : SessionSuspendResult
	/** The requested suspension could not be durably represented without violating lifecycle intent. */
	data class InvalidIntent(val code: String) : SessionSuspendResult
	data class Retryable(val failureCode: String) : SessionSuspendResult
	data object NoActiveSession : SessionSuspendResult
	data object Busy : SessionSuspendResult
}

private fun AcquisitionPlanRevision.captureSourceMask(): Long = plans.values.asSequence()
	.filter(SourcePlan::enabled)
	.fold(0L) { mask, plan ->
		require(plan.source.stableCode in 1..Long.SIZE_BITS) {
			"Invalid source kind ${plan.source.stableCode}"
		}
		mask or (1L shl (plan.source.stableCode - 1))
	}

private fun SourceProductDrainResult.pendingReason(): String = when (this) {
	is SourceProductDrainResult.Complete -> "SOURCE_PRODUCT_DRAIN_COMPLETE"
	is SourceProductDrainResult.Deferred -> reason
	is SourceProductDrainResult.Inactive -> reason
	is SourceProductDrainResult.Failed -> failureCode
	is SourceProductDrainResult.AuthorityChanged -> reason
	is SourceProductDrainResult.Unavailable -> reason
}

private fun ExecutableSourceLaneBinding.hasSameWriterSemanticsAs(
	base: ExecutableSourceLaneBinding,
): Boolean = source == base.source &&
	projectionId == base.projectionId &&
	projectionVersion == base.projectionVersion &&
	captureModes == base.captureModes
