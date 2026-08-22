package com.adsamcik.tracker.tracker.source.coordinator

import android.os.SystemClock
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
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
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.runtime.ProviderCoverage
import com.adsamcik.tracker.tracker.source.runtime.ProviderFlushOutcome
import com.adsamcik.tracker.tracker.source.runtime.RegistrationRemovalOutcome
import com.adsamcik.tracker.tracker.source.runtime.SessionCutoff
import com.adsamcik.tracker.tracker.source.runtime.SourceApplyResult
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionFailureCode
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionHandoff
import com.adsamcik.tracker.tracker.source.runtime.SourceEventSink
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeRegistry
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import com.adsamcik.tracker.tracker.source.runtime.SourceStartResult
import com.adsamcik.tracker.tracker.source.runtime.SourceStopAck
import com.adsamcik.tracker.tracker.source.runtime.SourceStopStatus
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Room-first lifecycle actor for logical tracking sessions and their service runs. */
@Singleton
class AuthoritativeSessionCoordinator @Inject constructor(
	private val database: AppDatabase,
	private val planStore: RoomSourcePlanStore,
	private val runtimes: SourceRuntimeRegistry,
	private val sinkFactory: DurableSourceEventSinkFactory,
	private val eventCoordinator: TrackingCoordinator,
	private val rolloutStore: TrackingRolloutStateStore = RoomTrackingRolloutStateStore(database),
	private val sourceBroker: SourceBroker = SourceBroker(database),
) {
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
		val rolloutFailure = rollout.validateEventPlan(request.rolloutRevision, request.plan)
		if (rolloutFailure != null) return SessionStartResult.InvalidRollout(rolloutFailure)
		val lease = acquireLease(request.ownerToken, request.clockDomainId, request.elapsedRealtimeNanos)
			?: return SessionStartResult.Busy
		return try {
			validateSourcePolicy(request.plan)?.let { return SessionStartResult.InvalidPolicy(it) }
			var active = database.sourceSessionDao().activeSession()
			if (active != null) {
				if (request.origin == SessionStartOrigin.RECOVERY &&
					request.logicalTrackingId == active.logicalTrackingId
				) {
					return resume(active, request, lease)
				}
				if (isStaleAutomaticOrLegacy(active, request)) {
					finalizeInterruptedSession(active, lease, request.wallTimeMs, "STALE_AUTOMATIC_SESSION")
					active = database.sourceSessionDao().activeSession()
				}
				if (active != null) return SessionStartResult.AlreadyActive
			}
			val logicalTrackingId = request.logicalTrackingId ?: UUID.randomUUID().toString()
			if (request.origin == SessionStartOrigin.RECOVERY) {
				return SessionStartResult.InvalidIntent("RECOVERY_SESSION_MISSING")
			}
			if (database.sourceSessionDao().session(logicalTrackingId) != null) {
				return SessionStartResult.InvalidIntent("LOGICAL_SESSION_ID_ALREADY_EXISTS")
			}
			val serviceRunId = request.serviceRunId ?: UUID.randomUUID().toString()
			var intentPolicyFailure: String? = null
			var persisted: PersistedLifecycleIntent? = null
			val created = database.withTransaction {
				requireLeaseInTransaction(lease)
				intentPolicyFailure = validateSourcePolicyInTransaction(request.plan)
				if (intentPolicyFailure != null) return@withTransaction false
				if (database.sourceSessionDao().activeSession() != null) return@withTransaction false
				val draft = buildManifestDraft(
					logicalTrackingId = logicalTrackingId,
					manifestRevision = 1L,
					intentRevision = 1L,
					request = request,
					sessionMode = request.origin.toSessionMode(),
					changeReason = "SESSION_START",
					lease = lease,
					serviceRunId = serviceRunId,
				)
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
						lifecycleLeaseGeneration = lease.generation,
						lifecycleBootId = lease.bootId,
						automationEpoch = draft.intent.automationEpoch,
					),
				)
				database.sourceSessionDao().insertManifest(draft.manifest)
				database.sourceSessionDao().insertManifestSources(draft.bindings)
				sourceBroker.replaceSessionDemandsInTransaction(
					logicalTrackingId,
					draft.demands,
					request.clockDomainId,
					request.elapsedRealtimeNanos,
					request.wallTimeMs,
				)
				database.sourceSessionDao().insertLifecycleIntent(draft.intent)
				database.sourceSessionDao().insertLifecycleActions(draft.actions)
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
				persisted = draft
				true
			}
			intentPolicyFailure?.let { return SessionStartResult.InvalidPolicy(it) }
			if (!created) return SessionStartResult.AlreadyActive

			val lifecycleIntent = requireNotNull(persisted)
			planStore.updateStatus(request.plan.revision, DesiredPlanStatus.APPLYING)
			val sink = sinkFactory.forSession(logicalTrackingId, serviceRunId)
			var applied = reconcileStartActions(
				plan = request.plan,
				intent = lifecycleIntent,
				sink = sink,
				lease = lease,
				wallTimeMs = request.wallTimeMs,
				elapsedRealtimeNanos = request.elapsedRealtimeNanos,
			)
			val finalPolicyFailure = validateSourcePolicy(request.plan)
			if (finalPolicyFailure != null) {
				applied = rollbackStalePolicySources(request.plan, applied, request.elapsedRealtimeNanos, request.wallTimeMs)
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
					SessionStartResult.Started(logicalTrackingId, serviceRunId, applied, effectiveStatus)
				} else {
					applied = rollbackStalePolicySources(
						request.plan,
						applied,
						request.elapsedRealtimeNanos,
						request.wallTimeMs,
					)
					planStore.updateStatus(request.plan.revision, DesiredPlanStatus.FAILED)
					markStartFailed(logicalTrackingId, serviceRunId, request.wallTimeMs, runningPolicyFailure, lease)
					SessionStartResult.Failed(logicalTrackingId, serviceRunId, applied, runningPolicyFailure)
				}
			}
		} finally {
			releaseLease(lease, request.elapsedRealtimeNanos)
		}
	}

	private suspend fun resume(
		session: LogicalTrackingSessionEntity,
		request: SessionStartRequest,
		lease: LifecycleLeaseToken,
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
		val serviceRunId = request.serviceRunId ?: UUID.randomUUID().toString()
		var policyFailure: String? = null
		var persisted: PersistedLifecycleIntent? = null
		database.withTransaction {
			requireLeaseInTransaction(lease)
			policyFailure = validateSourcePolicyInTransaction(request.plan)
			if (policyFailure != null) return@withTransaction
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
			)
			planStore.persistDesired(request.plan, DesiredPlanStatus.APPLYING)
			val latestRun = database.sourceSessionDao().latestServiceRun(session.logicalTrackingId)
			if (latestRun != null && latestRun.completedAtMs == null) {
				database.sourceSessionDao().updateServiceRun(
					latestRun.copy(
						state = SessionLifecycleState.FINALIZED.name,
						completedAtMs = request.wallTimeMs,
						completionReason = "PROCESS_DEATH_RECOVERY",
						runtimeAcknowledgement = LifecycleActionStatus.TERMINAL_FAILURE.name,
						runtimeFailureCode = "PROCESS_DEATH_RECOVERY",
						runRevision = latestRun.runRevision + 1L,
					),
				)
			}
			supersedePendingActions(session.logicalTrackingId, request.wallTimeMs, request.elapsedRealtimeNanos)
			database.sourceSessionDao().insertManifest(draft.manifest)
			database.sourceSessionDao().insertManifestSources(draft.bindings)
			sourceBroker.replaceSessionDemandsInTransaction(
				session.logicalTrackingId,
				draft.demands,
				request.clockDomainId,
				request.elapsedRealtimeNanos,
				request.wallTimeMs,
			)
			database.sourceSessionDao().insertLifecycleIntent(draft.intent)
			database.sourceSessionDao().insertLifecycleActions(draft.actions)
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
					lifecycleLeaseGeneration = lease.generation,
					lifecycleBootId = lease.bootId,
				),
			)
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
			persisted = draft
		}
		policyFailure?.let { return SessionStartResult.InvalidPolicy(it) }
		val lifecycleIntent = requireNotNull(persisted)
		val sink = sinkFactory.forSession(session.logicalTrackingId, serviceRunId)
		var applied = reconcileStartActions(
			request.plan,
			lifecycleIntent,
			sink,
			lease,
			request.wallTimeMs,
			request.elapsedRealtimeNanos,
		)
		if (validateSourcePolicy(request.plan) != null) {
			applied = rollbackStalePolicySources(request.plan, applied, request.elapsedRealtimeNanos, request.wallTimeMs)
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
				SessionStartResult.Started(session.logicalTrackingId, serviceRunId, applied, effectiveStatus)
			} else {
				applied = rollbackStalePolicySources(
					request.plan,
					applied,
					request.elapsedRealtimeNanos,
					request.wallTimeMs,
				)
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
		validateSourcePolicy(request.plan)?.let { return SessionReconfigureResult.InvalidPolicy(it) }
		validateControlDependencies(request.plan.sourcePolicyRevision, request.controlDependencies)?.let {
			return SessionReconfigureResult.InvalidPolicy(it)
		}
		val rollout = rolloutStore.load()
		val sessionForRollout = database.sourceSessionDao().activeSession()
			?: return SessionReconfigureResult.NoActiveSession
		val rolloutFailure = rollout.validateEventPlan(sessionForRollout.rolloutRevision, request.plan)
		if (rolloutFailure != null) return SessionReconfigureResult.InvalidRollout(rolloutFailure)
		val lease = acquireLease(request.ownerToken, request.clockDomainId, request.elapsedRealtimeNanos)
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
			database.withTransaction {
				requireLeaseInTransaction(lease)
				intentPolicyFailure = validateSourcePolicyInTransaction(request.plan)
				if (intentPolicyFailure != null) return@withTransaction
				val current = requireNotNull(database.sourceSessionDao().session(session.logicalTrackingId))
				check(current.lifecycleRevision == session.lifecycleRevision &&
					current.state == SessionLifecycleState.ACTIVE.name
				) { "Session changed during reconfiguration" }
				val currentManifest = current.currentManifestRevision?.let { revision ->
					database.sourceSessionDao().manifest(current.logicalTrackingId, revision)
				}
				if (currentManifest == null) {
					intentValidationFailure = "CURRENT_MANIFEST_MISSING"
					return@withTransaction
				}
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
				val serviceRun = requireNotNull(database.sourceSessionDao().latestServiceRun(session.logicalTrackingId))
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
				)
				planStore.persistDesired(request.plan, DesiredPlanStatus.APPLYING)
				database.sourceSessionDao().insertManifest(draft.manifest)
				database.sourceSessionDao().insertManifestSources(draft.bindings)
				sourceBroker.replaceSessionDemandsInTransaction(
					current.logicalTrackingId,
					draft.demands,
					request.clockDomainId,
					request.elapsedRealtimeNanos,
					request.wallTimeMs,
				)
				database.sourceSessionDao().insertLifecycleIntent(draft.intent)
				database.sourceSessionDao().insertLifecycleActions(draft.actions)
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
				persisted = draft
			}
			intentPolicyFailure?.let { return SessionReconfigureResult.InvalidPolicy(it) }
			intentValidationFailure?.let { return SessionReconfigureResult.InvalidIntent(it) }
			val existing = database.sourcePlanStateDao().appliedStates().associateBy { it.sourceKind }
			val serviceRun = requireNotNull(database.sourceSessionDao().latestServiceRun(session.logicalTrackingId))
			val sink = sinkFactory.forSession(session.logicalTrackingId, serviceRun.serviceRunId)
			var applied = reconcileReconfigureActions(
				plan = request.plan,
				intent = requireNotNull(persisted),
				existing = existing,
				sink = sink,
				lease = lease,
				wallTimeMs = request.wallTimeMs,
				elapsedRealtimeNanos = request.elapsedRealtimeNanos,
			)
			val finalPolicyFailure = validateSourcePolicy(request.plan)
			if (finalPolicyFailure != null) {
				applied = rollbackStalePolicySources(request.plan, applied, request.elapsedRealtimeNanos, request.wallTimeMs)
				planStore.updateStatus(request.plan.revision, DesiredPlanStatus.FAILED)
				markStartFailed(
					session.logicalTrackingId,
					serviceRun.serviceRunId,
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
				markStartFailed(
					session.logicalTrackingId,
					serviceRun.serviceRunId,
					request.wallTimeMs,
					"NO_SOURCE_ACTIVE_AFTER_RECONFIGURE",
					lease,
				)
				return SessionReconfigureResult.Failed(
					request.plan.revision,
					applied,
					"NO_SOURCE_ACTIVE_AFTER_RECONFIGURE",
				)
			}
			database.withTransaction {
				requireLeaseInTransaction(lease)
				val updated = requireNotNull(database.sourceSessionDao().session(session.logicalTrackingId))
				check(updated.state == SessionLifecycleState.RECONFIGURING.name)
				database.sourceSessionDao().updateSession(
					updated.copy(
						state = SessionLifecycleState.ACTIVE.name,
						lifecycleRevision = updated.lifecycleRevision + 1,
					),
				)
				val run = requireNotNull(database.sourceSessionDao().latestServiceRun(session.logicalTrackingId))
				database.sourceSessionDao().updateServiceRun(
					run.copy(
						state = SessionLifecycleState.ACTIVE.name,
						appliedForegroundCapabilityFlags = request.foregroundCapabilityFlags,
						runtimeAcknowledgement = LifecycleActionStatus.START_ACCEPTED.name,
						runRevision = run.runRevision + 1L,
					),
				)
			}
			SessionReconfigureResult.Applied(request.plan.revision, applied, status)
		} finally {
			releaseLease(lease, request.elapsedRealtimeNanos)
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
		return null
	}

	private fun validateStartIntent(request: SessionStartRequest): String? = when {
		request.clockDomainId.isBlank() -> "BOOT_ID_MISSING"
		request.zoneId.isBlank() -> "ZONE_ID_MISSING"
		request.origin == SessionStartOrigin.RECOVERY && request.logicalTrackingId == null -> "RECOVERY_ID_MISSING"
		request.origin == SessionStartOrigin.POLICY_RECONCILIATION -> "POLICY_RECONCILIATION_START_FORBIDDEN"
		request.origin == SessionStartOrigin.AUTOMATIC_BACKGROUND_START &&
			SourceKind.ACTIVITY !in request.controlDependencies -> "AUTOMATIC_ACTIVITY_CONTROL_MISSING"
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
			val epoch = policy?.controlConsentEpoch
			val ledger = epoch?.let { policyDao.consentEpoch(source.stableCode, POLICY_PURPOSE_CONTROL, it) }
			if (epoch == null || ledger?.eligible != true || ledger.policyRevision != revision) {
				return@withTransaction "CONTROL_CONSENT_MISSING:${source.name}"
			}
		}
		null
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
	)

	@Suppress("LongParameterList")
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
		automaticTrigger: AutomaticTriggerEvidence?,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
		foregroundCapabilityFlags: Long,
		sessionMode: SessionMode,
		changeReason: String,
		lease: LifecycleLeaseToken,
		serviceRunId: String,
	): PersistedLifecycleIntent {
		val policyRevision = requireNotNull(plan.sourcePolicyRevision)
		val policyDao = database.sourcePolicyDao()
		val policies = policyDao.policiesAtRevision(policyRevision).associateBy(SourcePolicyEntity::sourceKind)
		val captureBindings = plan.plans.values.filter(SourcePlan::enabled).map { sourcePlan ->
			val policy = requireNotNull(policies[sourcePlan.source.stableCode])
			val consentEpoch = requireNotNull(policy.captureConsentEpoch)
			val ledger = requireNotNull(
				policyDao.consentEpoch(sourcePlan.source.stableCode, POLICY_PURPOSE_CAPTURE, consentEpoch),
			)
			check(ledger.eligible && ledger.persistenceEligible && ledger.policyRevision == policyRevision)
			SessionManifestSourceEntity(
				logicalTrackingId = logicalTrackingId,
				manifestRevision = manifestRevision,
				sourceKind = sourcePlan.source.stableCode,
				purpose = SessionManifestPurpose.SESSION_CAPTURE.name,
				consentEpoch = consentEpoch,
				persistenceEligible = true,
				qosCode = policy.qosCode,
			)
		}
		val controlBindings = controlDependencies.map { source ->
			val policy = requireNotNull(policies[source.stableCode])
			val consentEpoch = requireNotNull(policy.controlConsentEpoch)
			val ledger = requireNotNull(
				policyDao.consentEpoch(source.stableCode, POLICY_PURPOSE_CONTROL, consentEpoch),
			)
			check(ledger.eligible && ledger.policyRevision == policyRevision)
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
		val bindingIdentity = bindings.map { binding ->
			"${binding.purpose}:${binding.sourceKind}:${binding.consentEpoch}:" +
				"${binding.persistenceEligible}:${binding.qosCode}"
		}
		val manifestChecksum = stableLifecycleChecksum(
			logicalTrackingId,
			manifestRevision,
			sessionMode,
			policyRevision,
			plan.revision,
			rolloutRevision,
			origin,
			clockDomainId,
			elapsedRealtimeNanos,
			wallTimeMs,
			zoneId,
			automaticTrigger?.automationEpoch,
			changeReason,
			bindingIdentity,
		)
		val manifest = SessionManifestVersionEntity(
			logicalTrackingId = logicalTrackingId,
			manifestRevision = manifestRevision,
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
			manifestChecksum = manifestChecksum,
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
		)
		var nextActionRevision = database.sourceSessionDao().maximumActionRevision(logicalTrackingId) + 1L
		val actions = plan.plans.values.sortedBy { it.source.stableCode }.mapNotNull { sourcePlan ->
			if (!sourcePlan.enabled && manifestRevision == 1L) return@mapNotNull null
			val binding = captureBindings.firstOrNull { it.sourceKind == sourcePlan.source.stableCode }
			LifecycleDesiredActionEntity(
				actionId = stableLifecycleChecksum(
					logicalTrackingId,
					intentRevision,
					sourcePlan.source.stableCode,
					if (sourcePlan.enabled) ACTION_DESIRED_STARTED else ACTION_DESIRED_STOPPED,
				),
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				manifestRevision = manifestRevision,
				actionRevision = nextActionRevision++,
				actionFamily = LifecycleActionFamily.SOURCE_RUNTIME.name,
				sourceKind = sourcePlan.source.stableCode,
				desiredState = if (sourcePlan.enabled) ACTION_DESIRED_STARTED else ACTION_DESIRED_STOPPED,
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
		val demands = sourceBroker.buildSessionDemands(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			manifestRevision = manifestRevision,
			lifecycleLeaseGeneration = lease.generation,
			policyRevision = policyRevision,
			bindings = bindings,
			bootId = clockDomainId,
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			wallTimeMs = wallTimeMs,
		)
		return PersistedLifecycleIntent(manifest, bindings, intent, actions, demands, foregroundCapabilityFlags)
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
		val binding = sessionDao.manifestSource(
			authorization.logicalTrackingId,
			authorization.manifestRevision,
			source.stableCode,
			SessionManifestPurpose.SESSION_CAPTURE.name,
		)
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
			session?.currentManifestRevision == authorization.manifestRevision &&
			session.lifecycleLeaseGeneration == authorization.leaseGeneration &&
			run?.leaseGeneration == authorization.leaseGeneration &&
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
	): List<AppliedSourcePlan> {
		val actionBySource = intent.actions.associateBy { it.sourceKind }
		val bindingBySource = intent.bindings
			.filter { it.purpose == SessionManifestPurpose.SESSION_CAPTURE.name }
			.associateBy { it.sourceKind }
		return plan.plans.values.sortedBy { it.source.stableCode }.map { sourcePlan ->
			if (!sourcePlan.enabled) {
				return@map disabledApplied(sourcePlan, elapsedRealtimeNanos).also { state ->
					planStore.saveApplied(state, wallTimeMs)
				}
			}
			val action = requireNotNull(actionBySource[sourcePlan.source.stableCode])
			val binding = requireNotNull(bindingBySource[sourcePlan.source.stableCode])
			val nowElapsed = monotonicNowAtLeast(elapsedRealtimeNanos)
			renewLease(lease, nowElapsed)
			val claimed = claimLifecycleAction(action.actionId, lease, nowElapsed)
			if (claimed == null) {
				return@map failedApplied(sourcePlan, nowElapsed, SourceApplyStatus.FAILED)
			}
			val authorization = CaptureAuthorization(
				logicalTrackingId = intent.manifest.logicalTrackingId,
				serviceRunId = action.serviceRunId,
				policyRevision = intent.manifest.sourcePolicyRevision,
				captureConsentEpoch = binding.consentEpoch,
				manifestRevision = intent.manifest.manifestRevision,
				leaseGeneration = lease.generation,
			)
			val execution = startSource(sourcePlan, sink, authorization, nowElapsed)
			planStore.saveApplied(execution.applied, wallTimeMs)
			val acknowledged = acknowledgeLifecycleAction(
				claimed,
				execution,
				lease,
				wallTimeMs,
				monotonicNowAtLeast(nowElapsed),
			)
			if (!acknowledged) {
				closeAndFence(sourcePlan.source, authorization.policyRevision)
				failedApplied(sourcePlan, nowElapsed, SourceApplyStatus.FAILED)
			} else {
				execution.applied
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
	): List<AppliedSourcePlan> {
		val actionBySource = intent.actions.associateBy { it.sourceKind }
		val bindingBySource = intent.bindings
			.filter { it.purpose == SessionManifestPurpose.SESSION_CAPTURE.name }
			.associateBy { it.sourceKind }
		return plan.plans.values.sortedBy { it.source.stableCode }.map { sourcePlan ->
			val action = requireNotNull(actionBySource[sourcePlan.source.stableCode])
			val nowElapsed = monotonicNowAtLeast(elapsedRealtimeNanos)
			renewLease(lease, nowElapsed)
			val claimed = claimLifecycleAction(action.actionId, lease, nowElapsed)
			if (claimed == null) {
				return@map failedApplied(sourcePlan, nowElapsed, SourceApplyStatus.FAILED)
			}
			val binding = bindingBySource[sourcePlan.source.stableCode]
			val authorization = CaptureAuthorization(
				logicalTrackingId = intent.manifest.logicalTrackingId,
				serviceRunId = action.serviceRunId,
				policyRevision = intent.manifest.sourcePolicyRevision,
				captureConsentEpoch = binding?.consentEpoch ?: 0L,
				manifestRevision = intent.manifest.manifestRevision,
				leaseGeneration = lease.generation,
			)
			val execution = if (existing[sourcePlan.source.stableCode]?.sourceInstanceId == null) {
				startSource(sourcePlan, sink, authorization, nowElapsed)
			} else {
				reconfigureSource(sourcePlan, sink, authorization, nowElapsed)
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
				closeAndFence(sourcePlan.source, authorization.policyRevision)
				failedApplied(sourcePlan, nowElapsed, SourceApplyStatus.FAILED)
			} else {
				execution.applied
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

	private suspend fun acknowledgeLifecycleAction(
		action: LifecycleDesiredActionEntity,
		execution: SourceActionExecution,
		lease: LifecycleLeaseToken,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
	): Boolean = database.withTransaction {
		if (!leaseIsCurrentInTransaction(lease)) return@withTransaction false
		val dao = database.sourceSessionDao()
		val current = dao.lifecycleAction(action.actionId) ?: return@withTransaction false
		val session = dao.session(action.logicalTrackingId) ?: return@withTransaction false
		if (current.status != LifecycleActionStatus.APPLYING.name ||
			current.attemptCount != action.attemptCount ||
			current.leaseGeneration != lease.generation ||
			session.currentManifestRevision != action.manifestRevision ||
			session.lifecycleLeaseGeneration != lease.generation
		) return@withTransaction false
		dao.updateLifecycleAction(
			current.copy(
				status = execution.status.name,
				acknowledgedAtMs = wallTimeMs,
				acknowledgedElapsedRealtimeNanos = elapsedRealtimeNanos,
				failureCode = execution.failureCode,
				retryTrigger = execution.retryTrigger,
				sourceInstanceId = execution.applied.sourceInstanceId?.value,
				registrationGeneration = execution.applied.registrationGeneration,
			),
		) == 1
	}

	private suspend fun persistStopIntent(
		session: LogicalTrackingSessionEntity,
		request: SessionStopRequest,
		lease: LifecycleLeaseToken,
	): LogicalTrackingSessionEntity {
		val dao = database.sourceSessionDao()
		val manifestRevision = requireNotNull(session.currentManifestRevision)
		val intentRevision = requireNotNull(session.currentIntentRevision) + 1L
		val manifest = requireNotNull(dao.manifest(session.logicalTrackingId, manifestRevision))
		val run = requireNotNull(dao.latestServiceRun(session.logicalTrackingId))
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
				manifest,
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
		check(
			dao.updateServiceRun(
				run.copy(
					state = SessionLifecycleState.STOPPING.name,
					leaseGeneration = lease.generation,
					bootId = lease.bootId,
					runtimeAcknowledgement = LifecycleActionStatus.PENDING.name,
					runRevision = run.runRevision + 1L,
				),
			) == 1,
		)
		return updated
	}

	private suspend fun persistServiceRunSuspendIntent(
		session: LogicalTrackingSessionEntity,
		request: SessionSuspendRequest,
		lease: LifecycleLeaseToken,
	): LogicalTrackingSessionEntity {
		val dao = database.sourceSessionDao()
		val manifestRevision = requireNotNull(session.currentManifestRevision)
		val intentRevision = requireNotNull(session.currentIntentRevision) + 1L
		val manifest = requireNotNull(dao.manifest(session.logicalTrackingId, manifestRevision))
		val run = requireNotNull(dao.latestServiceRun(session.logicalTrackingId))
		val intent = SessionLifecycleIntentVersionEntity(
			logicalTrackingId = session.logicalTrackingId,
			intentRevision = intentRevision,
			manifestRevision = manifestRevision,
			desiredState = LifecycleDesiredState.ACTIVE.name,
			startOrigin = SessionStartOrigin.RECOVERY.name,
			requestBootId = request.clockDomainId,
			requestedElapsedRealtimeNanos = request.elapsedRealtimeNanos,
			requestedWallTimeMs = request.wallTimeMs,
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
				request.elapsedRealtimeNanos,
			),
		)
		dao.insertLifecycleIntent(intent)
		dao.insertLifecycleActions(
			stopActions(
				session,
				manifest,
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
		check(
			dao.updateServiceRun(
				run.copy(
					state = SessionLifecycleState.STOPPING.name,
					leaseGeneration = lease.generation,
					bootId = lease.bootId,
					runtimeAcknowledgement = LifecycleActionStatus.PENDING.name,
					runRevision = run.runRevision + 1L,
				),
			) == 1,
		)
		return updated
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
		manifest: SessionManifestVersionEntity,
		intentRevision: Long,
		serviceRunId: String,
		lease: LifecycleLeaseToken,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
	): List<LifecycleDesiredActionEntity> {
		val bindings = database.sourceSessionDao().manifestSources(
			session.logicalTrackingId,
			manifest.manifestRevision,
		).filter { it.purpose == SessionManifestPurpose.SESSION_CAPTURE.name }
		var actionRevision = database.sourceSessionDao().maximumActionRevision(session.logicalTrackingId) + 1L
		return bindings.map { binding ->
			LifecycleDesiredActionEntity(
				actionId = stableLifecycleChecksum(
					session.logicalTrackingId,
					intentRevision,
					binding.sourceKind,
					ACTION_DESIRED_STOPPED,
				),
				logicalTrackingId = session.logicalTrackingId,
				serviceRunId = serviceRunId,
				manifestRevision = manifest.manifestRevision,
				actionRevision = actionRevision++,
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
		lease: LifecycleLeaseToken,
	) {
		val dao = database.sourceSessionDao()
		dao.lifecycleActions(logicalTrackingId)
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
	}

	private suspend fun acknowledgeStopAction(
		logicalTrackingId: String,
		ack: SourceStopAck,
		lease: LifecycleLeaseToken,
		request: SessionStopRequest,
	) = acknowledgeStopAction(
		logicalTrackingId,
		ack,
		lease,
		request.wallTimeMs,
		request.elapsedRealtimeNanos,
	)

	private suspend fun acknowledgeSuspendAction(
		logicalTrackingId: String,
		ack: SourceStopAck,
		lease: LifecycleLeaseToken,
		request: SessionSuspendRequest,
	) = acknowledgeStopAction(
		logicalTrackingId,
		ack,
		lease,
		request.wallTimeMs,
		request.elapsedRealtimeNanos,
	)

	private suspend fun acknowledgeStopAction(
		logicalTrackingId: String,
		ack: SourceStopAck,
		lease: LifecycleLeaseToken,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
	) {
		val dao = database.sourceSessionDao()
		val action = dao.lifecycleActions(logicalTrackingId).lastOrNull { candidate ->
			candidate.sourceKind == ack.source.stableCode &&
				candidate.desiredState == ACTION_DESIRED_STOPPED &&
				candidate.leaseGeneration == lease.generation &&
				candidate.status == LifecycleActionStatus.APPLYING.name
		} ?: return
		val accepted = ack.status == SourceStopStatus.COMPLETE &&
			ack.registrationRemovalOutcome != RegistrationRemovalOutcome.FAILED
		check(
			dao.updateLifecycleAction(
				action.copy(
					status = if (accepted) {
						LifecycleActionStatus.STOP_ACCEPTED.name
					} else {
						LifecycleActionStatus.TERMINAL_FAILURE.name
					},
					acknowledgedAtMs = wallTimeMs,
					acknowledgedElapsedRealtimeNanos = elapsedRealtimeNanos,
					failureCode = if (accepted) null else "STOP_${ack.status.name}",
					sourceInstanceId = ack.sourceInstanceId.value,
					registrationGeneration = ack.registrationGeneration,
				),
			) == 1,
		)
	}

	suspend fun stop(request: SessionStopRequest): SessionStopResult {
		val lease = acquireLease(request.ownerToken, request.clockDomainId, request.elapsedRealtimeNanos)
			?: return SessionStopResult.Busy
		return try {
			val session = database.sourceSessionDao().activeSession() ?: return SessionStopResult.NoActiveSession
			val cutoffSession = database.withTransaction {
				requireLeaseInTransaction(lease)
				val current = requireNotNull(database.sourceSessionDao().session(session.logicalTrackingId))
				if (current.state == SessionLifecycleState.STOPPING.name) current else {
					check(current.state in setOf(SessionLifecycleState.STARTING.name, SessionLifecycleState.ACTIVE.name,
						SessionLifecycleState.RECONFIGURING.name)) { "Terminal session cannot stop again" }
					persistStopIntent(current, request, lease)
				}
			}
			database.withTransaction {
				requireLeaseInTransaction(lease)
				markStopActionsApplying(cutoffSession.logicalTrackingId, lease)
			}
			val plan = requireNotNull(planStore.load(cutoffSession.desiredPlanRevision))
			val cutoff = SessionCutoff(
				logicalTrackingId = cutoffSession.logicalTrackingId,
				elapsedRealtimeNanos = requireNotNull(cutoffSession.cutoffElapsedNanos),
				wallTimeMs = requireNotNull(cutoffSession.cutoffAtMs),
				deadlineElapsedRealtimeNanos = request.elapsedRealtimeNanos + request.gracePeriodMs * NANOS_PER_MILLISECOND,
			)
			val acks = quiesceSources(plan, cutoff, request.perSourceTimeoutMs)
			database.withTransaction {
				requireLeaseInTransaction(lease)
				acks.forEach { ack ->
					saveCompleteness(cutoffSession.logicalTrackingId, ack, request.wallTimeMs)
					acknowledgeStopAction(cutoffSession.logicalTrackingId, ack, lease, request)
				}
			}
			val finalOrdinal = database.sourceEventWalDao().maximumAdmissionOrdinal() ?: 0L
			when (val drain = eventCoordinator.drainAvailable("${request.ownerToken}:projection")) {
				is CoordinatorDrainResult.Complete -> {
					if (drain.lastCompletedOrdinal < finalOrdinal) {
						return SessionStopResult.DrainPending(cutoffSession.logicalTrackingId, finalOrdinal)
					}
				}
				else -> return SessionStopResult.DrainPending(cutoffSession.logicalTrackingId, finalOrdinal)
			}
			var closeFailed = false
			plan.plans.values.filter(SourcePlan::enabled).forEach { planItem ->
				if (planItem.source in runtimes.registeredSources()) {
					closeFailed = runCatchingNonCancellation {
						runtimes.close(planItem.source)
					}.isFailure || closeFailed
				}
			}
			val incomplete = closeFailed || acks.any { ack ->
				ack.status != SourceStopStatus.COMPLETE ||
					ack.registrationRemovalOutcome == RegistrationRemovalOutcome.FAILED
			}
			database.withTransaction {
				requireLeaseInTransaction(lease)
				sourceBroker.retireSessionDemands(
					cutoffSession.logicalTrackingId,
					lease.bootId,
					request.elapsedRealtimeNanos,
					request.wallTimeMs,
				)
				val finalizing = requireNotNull(database.sourceSessionDao().session(cutoffSession.logicalTrackingId))
				check(finalizing.state == SessionLifecycleState.STOPPING.name)
				database.sourceSessionDao().updateSession(
					finalizing.copy(
						state = SessionLifecycleState.FINALIZED.name,
						lifecycleRevision = finalizing.lifecycleRevision + 1,
						finalAdmissionOrdinal = finalOrdinal,
						completedAtMs = request.wallTimeMs,
						failureCode = if (incomplete) "STOP_INCOMPLETE" else null,
					),
				)
				val serviceRun = database.sourceSessionDao().latestServiceRun(cutoffSession.logicalTrackingId)
				if (serviceRun != null) {
					database.sourceSessionDao().updateServiceRun(
						serviceRun.copy(
							state = SessionLifecycleState.FINALIZED.name,
							completedAtMs = request.wallTimeMs,
							completionReason = if (incomplete) "${request.reason}:INCOMPLETE" else request.reason,
							runtimeAcknowledgement = if (incomplete) {
								LifecycleActionStatus.TERMINAL_FAILURE.name
							} else {
								LifecycleActionStatus.STOP_ACCEPTED.name
							},
							runtimeFailureCode = if (incomplete) "STOP_INCOMPLETE" else null,
							runRevision = serviceRun.runRevision + 1L,
						),
					)
				}
			}
			SessionStopResult.Stopped(cutoffSession.logicalTrackingId, finalOrdinal, acks)
		} finally {
			releaseLease(lease, request.elapsedRealtimeNanos)
		}
	}

	/**
	 * Ends one Android service run while preserving the durable logical session for watchdog
	 * recovery. Sources are fenced and projections drained before the run is marked closed.
	 */
	suspend fun suspendForRestart(request: SessionSuspendRequest): SessionSuspendResult {
		val lease = acquireLease(request.ownerToken, request.clockDomainId, request.elapsedRealtimeNanos)
			?: return SessionSuspendResult.Busy
		return try {
			val session = database.sourceSessionDao().activeSession()
				?: return SessionSuspendResult.NoActiveSession
			if (session.state != SessionLifecycleState.ACTIVE.name) {
				return SessionSuspendResult.NoActiveSession
			}
			val durableSession = database.withTransaction {
				requireLeaseInTransaction(lease)
				persistServiceRunSuspendIntent(session, request, lease)
			}
			database.withTransaction {
				requireLeaseInTransaction(lease)
				markStopActionsApplying(durableSession.logicalTrackingId, lease)
			}
			val plan = requireNotNull(planStore.load(durableSession.desiredPlanRevision))
			val cutoff = SessionCutoff(
				logicalTrackingId = durableSession.logicalTrackingId,
				elapsedRealtimeNanos = request.elapsedRealtimeNanos,
				wallTimeMs = request.wallTimeMs,
				deadlineElapsedRealtimeNanos = request.elapsedRealtimeNanos +
					request.gracePeriodMs * NANOS_PER_MILLISECOND,
			)
			val acks = quiesceSources(plan, cutoff, request.perSourceTimeoutMs)
			database.withTransaction {
				requireLeaseInTransaction(lease)
				acks.forEach { ack ->
					saveCompleteness(durableSession.logicalTrackingId, ack, request.wallTimeMs)
					acknowledgeSuspendAction(durableSession.logicalTrackingId, ack, lease, request)
				}
			}
			val finalOrdinal = database.sourceEventWalDao().maximumAdmissionOrdinal() ?: 0L
			when (val drain = eventCoordinator.drainAvailable("${request.ownerToken}:projection")) {
				is CoordinatorDrainResult.Complete -> if (drain.lastCompletedOrdinal < finalOrdinal) {
					return SessionSuspendResult.DrainPending(durableSession.logicalTrackingId, finalOrdinal)
				}
				else -> return SessionSuspendResult.DrainPending(durableSession.logicalTrackingId, finalOrdinal)
			}
			var closeFailed = false
			plan.plans.values.forEach { planItem ->
				if (planItem.source in runtimes.registeredSources()) {
					closeFailed = runCatchingNonCancellation {
						runtimes.close(planItem.source)
					}.isFailure || closeFailed
				}
			}
			database.withTransaction {
				requireLeaseInTransaction(lease)
				val latest = requireNotNull(database.sourceSessionDao().session(durableSession.logicalTrackingId))
				check(latest.state == SessionLifecycleState.ACTIVE.name)
				val serviceRun = database.sourceSessionDao().latestServiceRun(durableSession.logicalTrackingId)
				if (serviceRun != null && serviceRun.completedAtMs == null) {
					database.sourceSessionDao().updateServiceRun(
						serviceRun.copy(
							state = SessionLifecycleState.FINALIZED.name,
							completedAtMs = request.wallTimeMs,
							completionReason = if (closeFailed) "${request.reason}:INCOMPLETE" else request.reason,
							runtimeAcknowledgement = if (closeFailed) {
								LifecycleActionStatus.TERMINAL_FAILURE.name
							} else {
								LifecycleActionStatus.STOP_ACCEPTED.name
							},
							runtimeFailureCode = if (closeFailed) "STOP_INCOMPLETE" else null,
							runRevision = serviceRun.runRevision + 1L,
						),
					)
				}
			}
			SessionSuspendResult.Suspended(durableSession.logicalTrackingId, finalOrdinal, acks)
		} finally {
			releaseLease(lease, request.elapsedRealtimeNanos)
		}
	}

	private suspend fun startSource(
		plan: SourcePlan,
		sink: SourceEventSink,
		authorization: CaptureAuthorization,
		elapsedNanos: Long,
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
			runtimes.start(plan, registrationFencedSink(sink, plan.source)).toExecution()
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			closeAndFence(plan.source, authorization.policyRevision)
			return SourceActionExecution(
				failedApplied(plan, elapsedNanos, SourceApplyStatus.FAILED),
				LifecycleActionStatus.TERMINAL_FAILURE,
				"SOURCE_START_EXCEPTION",
			)
		}
		return if (captureAuthorizationCurrent(authorization, plan.source)) execution else {
			val closed = closeAndFence(plan.source, authorization.policyRevision)
			SourceActionExecution(
				failedApplied(
					plan,
					elapsedNanos,
					if (closed) SourceApplyStatus.ROLLED_BACK else SourceApplyStatus.FAILED,
				),
				LifecycleActionStatus.TERMINAL_FAILURE,
				"SOURCE_AUTHORIZATION_STALE",
			)
		}
	}

	private suspend fun reconfigureSource(
		plan: SourcePlan,
		sink: SourceEventSink,
		authorization: CaptureAuthorization,
		elapsedNanos: Long,
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
				plan,
				registrationFencedSink(sink, plan.source),
			).toExecution(plan.enabled)
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			closeAndFence(plan.source, authorization.policyRevision)
			return SourceActionExecution(
				failedApplied(plan, elapsedNanos, SourceApplyStatus.FAILED),
				LifecycleActionStatus.TERMINAL_FAILURE,
				"SOURCE_RECONFIGURE_EXCEPTION",
			)
		}
		return if (!plan.enabled || captureAuthorizationCurrent(authorization, plan.source)) execution else {
			val closed = closeAndFence(plan.source, authorization.policyRevision)
			SourceActionExecution(
				failedApplied(
					plan,
					elapsedNanos,
					if (closed) SourceApplyStatus.ROLLED_BACK else SourceApplyStatus.FAILED,
				),
				LifecycleActionStatus.TERMINAL_FAILURE,
				"SOURCE_AUTHORIZATION_STALE",
			)
		}
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

	private suspend fun closeAndFence(source: SourceKind, expectedRevision: Long): Boolean {
		@Suppress("UNUSED_VARIABLE")
		val policyRevisionFence = expectedRevision
		return runCatchingNonCancellation {
			runtimes.close(source)
			true
		}.getOrDefault(false)
	}

	private suspend fun rollbackStalePolicySources(
		plan: AcquisitionPlanRevision,
		applied: List<AppliedSourcePlan>,
		elapsedNanos: Long,
		wallTimeMs: Long,
	): List<AppliedSourcePlan> {
		val policyRevision = requireNotNull(plan.sourcePolicyRevision)
		val rolledBack = applied.map { state ->
			val sourcePlan = requireNotNull(plan.plans[state.source])
			if (!sourcePlan.enabled) state else {
				val closed = closeAndFence(state.source, policyRevision)
				failedApplied(
					sourcePlan,
					elapsedNanos,
					if (closed) SourceApplyStatus.ROLLED_BACK else SourceApplyStatus.FAILED,
				)
			}
		}
		rolledBack.forEach { state -> planStore.saveApplied(state, wallTimeMs) }
		return rolledBack
	}

	private suspend fun quiesceSources(
		plan: AcquisitionPlanRevision,
		cutoff: SessionCutoff,
		perSourceTimeoutMs: Long,
	): List<SourceStopAck> = coroutineScope {
		val applied = database.sourcePlanStateDao().appliedStates().associateBy { it.sourceKind }
		plan.plans.values.filter(SourcePlan::enabled).sortedBy { it.source.stableCode }.map { sourcePlan ->
			async {
				if (sourcePlan.source !in runtimes.registeredSources()) {
					timeoutAck(sourcePlan.source, applied[sourcePlan.source.stableCode], SourceStopStatus.PROVIDER_FAILED)
				} else {
					withTimeoutOrNull(perSourceTimeoutMs) { runtimes.quiesce(sourcePlan.source, cutoff) }
						?: timeoutAck(sourcePlan.source, applied[sourcePlan.source.stableCode], SourceStopStatus.TIMED_OUT)
				}
			}
		}.awaitAll()
	}

	private suspend fun saveCompleteness(logicalTrackingId: String, ack: SourceStopAck, nowMs: Long) {
		database.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = logicalTrackingId,
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
			),
		)
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
			if (session.currentManifestRevision != manifestRevision ||
				session.lifecycleLeaseGeneration != lease.generation ||
				session.state !in setOf(
					SessionLifecycleState.STARTING.name,
					SessionLifecycleState.RECONFIGURING.name,
				)
			) {
				policyFailure = "LIFECYCLE_INTENT_STALE"
				return@withTransaction
			}
			val accepted = database.sourceSessionDao().lifecycleActions(logicalTrackingId).any { action ->
				action.manifestRevision == manifestRevision &&
					action.status == LifecycleActionStatus.START_ACCEPTED.name
			}
			if (!accepted) {
				policyFailure = "NO_SOURCE_START_ACCEPTED"
				return@withTransaction
			}
			database.sourceSessionDao().updateSession(
				session.copy(state = SessionLifecycleState.ACTIVE.name, lifecycleRevision = session.lifecycleRevision + 1),
			)
			val run = requireNotNull(database.sourceSessionDao().serviceRun(serviceRunId))
			database.sourceSessionDao().updateServiceRun(
				run.copy(
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
				),
			)
			val run = requireNotNull(database.sourceSessionDao().serviceRun(serviceRunId))
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
		bootId: String,
		nowElapsedNanos: Long,
	): LifecycleLeaseToken? = database.withTransaction {
		val dao = database.sourceProjectionStateDao()
		val leaseNowElapsedNanos = monotonicNowAtLeast(nowElapsedNanos)
		val expires = leaseNowElapsedNanos + LEASE_DURATION_NANOS
		val nowMs = System.currentTimeMillis()
		val inserted = dao.insertLeaseIfAbsent(
			SourceCoordinatorLeaseEntity(
				leaseName = SESSION_LEASE,
				ownerToken = ownerToken,
				acquiredAtMs = nowMs,
				expiresAtMs = nowMs + LEASE_DURATION_NANOS / NANOS_PER_MILLISECOND,
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
				nowMs + LEASE_DURATION_NANOS / NANOS_PER_MILLISECOND,
				leaseNowElapsedNanos,
				expires,
			) != 1
		) return@withTransaction null
		val current = requireNotNull(dao.lease(SESSION_LEASE))
		if (current.ownerToken != ownerToken || current.bootId != bootId) return@withTransaction null
		LifecycleLeaseToken(SESSION_LEASE, ownerToken, bootId, current.generation)
	}

	private suspend fun renewLease(lease: LifecycleLeaseToken, nowElapsedNanos: Long) {
		val leaseNowElapsedNanos = monotonicNowAtLeast(nowElapsedNanos)
		val nowMs = System.currentTimeMillis()
		check(
			database.sourceProjectionStateDao().acquireOrRenewLease(
				lease.leaseName,
				lease.ownerToken,
				lease.bootId,
				nowMs,
				nowMs + LEASE_DURATION_NANOS / NANOS_PER_MILLISECOND,
				leaseNowElapsedNanos,
				leaseNowElapsedNanos + LEASE_DURATION_NANOS,
			) == 1,
		) { "Session coordinator lease lost" }
		check(database.sourceProjectionStateDao().lease(lease.leaseName)?.generation == lease.generation) {
			"Session coordinator lease generation changed"
		}
	}

	private suspend fun releaseLease(lease: LifecycleLeaseToken, nowElapsedNanos: Long) {
		database.sourceProjectionStateDao().releaseLease(
			lease.leaseName,
			lease.ownerToken,
			lease.bootId,
			lease.generation,
			System.currentTimeMillis(),
			monotonicNowAtLeast(nowElapsedNanos),
		)
	}

	private suspend fun requireLeaseInTransaction(lease: LifecycleLeaseToken) {
		check(leaseIsCurrentInTransaction(lease)) { "Session coordinator lease lost" }
	}

	private suspend fun leaseIsCurrentInTransaction(lease: LifecycleLeaseToken): Boolean {
		val current = database.sourceProjectionStateDao().lease(lease.leaseName) ?: return false
		return current.ownerToken == lease.ownerToken &&
			current.bootId == lease.bootId &&
			current.generation == lease.generation &&
			current.expiresElapsedRealtimeNanos > SystemClock.elapsedRealtimeNanos()
	}

	private fun monotonicNowAtLeast(floorNanos: Long): Long =
		maxOf(floorNanos, SystemClock.elapsedRealtimeNanos())

	private suspend fun isStaleAutomaticOrLegacy(
		session: LogicalTrackingSessionEntity,
		request: SessionStartRequest,
	): Boolean {
		if (session.currentManifestRevision == null || session.currentIntentRevision == null) return true
		val automatic = session.sessionMode == SessionMode.AUTOMATIC.name ||
			session.startOrigin == "AUTOMATIC_ACTIVITY_TRANSITION"
		if (!automatic) return false
		val requestedRun = request.serviceRunId
		return requestedRun == null ||
			database.sourceSessionDao().latestServiceRun(session.logicalTrackingId)?.serviceRunId != requestedRun
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
			dao.incompleteServiceRuns(current.logicalTrackingId).forEach { run ->
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

	private companion object {
		const val SESSION_LEASE = "tracking-session-coordinator"
		const val LEASE_DURATION_NANOS = 30_000L * 1_000_000L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val POLICY_PURPOSE_CAPTURE = "SESSION_CAPTURE"
		const val POLICY_PURPOSE_CONTROL = "CONTROL"
		const val ACTION_DESIRED_STARTED = "STARTED"
		const val ACTION_DESIRED_STOPPED = "STOPPED"
		val TERMINAL_STATES = setOf(SessionLifecycleState.FINALIZED.name, SessionLifecycleState.FAILED.name)
		val TERMINAL_OR_STOPPING_STATES = TERMINAL_STATES + SessionLifecycleState.STOPPING.name
	}
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
				plan.aggregationWindowMs >= 60_000L &&
				plan.movementGatedBurst
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

private fun List<AppliedSourcePlan>.desiredStatus(): DesiredPlanStatus = when {
	all { it.status == SourceApplyStatus.APPLIED } -> DesiredPlanStatus.EFFECTIVE
	any { it.status in setOf(SourceApplyStatus.FAILED, SourceApplyStatus.BLOCKED) } -> DesiredPlanStatus.DEGRADED
	else -> DesiredPlanStatus.DEGRADED
}

private fun timeoutAck(
	source: SourceKind,
	applied: com.adsamcik.tracker.shared.base.database.data.SourceAppliedPlanStateEntity?,
	status: SourceStopStatus,
) = SourceStopAck(
	source = source,
	sourceInstanceId = com.adsamcik.tracker.tracker.source.model.SourceInstanceId(
		applied?.sourceInstanceId ?: "unavailable-${source.name.lowercase()}",
	),
	registrationGeneration = applied?.registrationGeneration ?: 0L,
	appliedRevision = applied?.appliedRevision,
	callbackEntryBarrierSequence = 0L,
	lastDurablyAdmittedSequence = null,
	lastAdmissionOrdinal = null,
	failedAdmissionCount = 0,
	unresolvedSequenceStart = null,
	unresolvedSequenceEndInclusive = null,
	registrationRemovalOutcome = RegistrationRemovalOutcome.UNOBSERVABLE,
	providerFlushOutcome = ProviderFlushOutcome.TIMED_OUT,
	providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
	appDrainComplete = false,
	status = status,
)

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
	val automaticTrigger: AutomaticTriggerEvidence? = null,
	val logicalTrackingId: String? = null,
	val serviceRunId: String? = null,
)

sealed interface SessionStartResult {
	data class Started(
		val logicalTrackingId: String,
		val serviceRunId: String,
		val applied: List<AppliedSourcePlan>,
		val planStatus: DesiredPlanStatus,
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
	data class InvalidIntent(val code: String) : SessionStartResult
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
	) : SessionReconfigureResult
	data class Failed(
		val revision: Long,
		val applied: List<AppliedSourcePlan>,
		val failureCode: String,
	) : SessionReconfigureResult
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
): String? = when {
	revision != expectedRevision -> "ROLLOUT_REVISION_MISMATCH"
	coordinatorMode != CoordinatorMode.EVENT -> "EVENT_COORDINATOR_DISABLED"
	plan.plans.values.any { sourcePlan ->
		sourcePlan.enabled && sourceOwners[sourcePlan.source] != SourceOwner.EVENT
	} -> "EVENT_SOURCE_NOT_OWNED"
	else -> null
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
	data class DrainPending(val logicalTrackingId: String, val requiredOrdinal: Long) : SessionStopResult
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
	) : SessionSuspendResult
	data class DrainPending(val logicalTrackingId: String, val requiredOrdinal: Long) : SessionSuspendResult
	data object NoActiveSession : SessionSuspendResult
	data object Busy : SessionSuspendResult
}
