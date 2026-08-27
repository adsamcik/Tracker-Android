package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.liveSourceProjectionActivationOrdinal
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Source-specific one-writer transition for the first Steps fact lane.
 *
 * This is deliberately not a generic rollout engine and has no first-activation caller. A release
 * action may invoke the activation path only after shadow evidence and product-reader acceptance.
 * Full deletion may re-arm a writer generation that was already canonical; it cannot perform the
 * initial cutover. Each public transition runs behind the process startup/deletion gate and the
 * existing session-coordinator lease; the final authority changes then share one Room transaction.
 * The operations named rollback below are a fail-closed writer rollback: they contain capture,
 * drain and retire the candidate, and restore legacy ownership for compatibility. They do not
 * reactivate product capture by themselves.
 */
@Singleton
class StepsSessionFactWriterTransitionCoordinator private constructor(
	private val database: AppDatabase,
	private val executableLaneCatalog: ExecutableSourceLaneCatalog,
	private val startupGateProvider: Provider<TrackingStartupGate>,
	private val bootClockDomainProvider: BootClockDomainProvider,
	private val clock: Clock,
	private val legacyStepsWriterBoundary: LegacyStepsWriterTransitionBoundary,
	private val requestStepsDrain: () -> Unit,
	private val faultInjector: StepsWriterTransitionFaultInjector,
) {
	@Inject
	constructor(
		database: AppDatabase,
		executableLaneCatalog: ExecutableSourceLaneCatalog,
		startupGateProvider: Provider<TrackingStartupGate>,
		bootClockDomainProvider: BootClockDomainProvider,
		clock: Clock,
		persistenceProcessorProvider: Provider<PersistenceProcessor>,
		sourcePipelineRecoveryProvider: Provider<SourcePipelineRecovery>,
	) : this(
		database,
		executableLaneCatalog,
		startupGateProvider,
		bootClockDomainProvider,
		clock,
		PersistenceLegacyStepsWriterTransitionBoundary(persistenceProcessorProvider),
		{ sourcePipelineRecoveryProvider.get().requestStepsSessionFactDrain() },
		StepsWriterTransitionFaultInjector.NONE,
	)

	internal constructor(
		database: AppDatabase,
		executableLaneCatalog: ExecutableSourceLaneCatalog,
		startupGate: TrackingStartupGate,
		faultInjector: StepsWriterTransitionFaultInjector = StepsWriterTransitionFaultInjector.NONE,
		bootClockDomainProvider: BootClockDomainProvider = BootClockDomainProvider { "test-boot" },
		clock: Clock = FixedStepsWriterTransitionClock,
		legacyStepsWriterBoundary: LegacyStepsWriterTransitionBoundary =
			LegacyStepsWriterTransitionBoundary.ALWAYS_QUIESCENT,
		requestStepsDrain: () -> Unit = {},
	) : this(
		database,
		executableLaneCatalog,
		Provider { startupGate },
		bootClockDomainProvider,
		clock,
		legacyStepsWriterBoundary,
		requestStepsDrain,
		faultInjector,
	)

	suspend fun activateCandidate(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
	): StepsWriterTransitionResult = withTransitionBoundary(
		phase = StepsWriterTransitionPhase.ACTIVATE_CANDIDATE,
	) { lease ->
		legacyStepsWriterBoundary.runIfQuiescent {
			activateCandidateInTransaction(expectedRolloutRevision, updatedAtMs, lease)
		} ?: StepsWriterTransitionResult.Blocked(
			StepsWriterTransitionPhase.ACTIVATE_CANDIDATE,
			StepsWriterTransitionBlocker.LEGACY_STEPS_WRITER_NOT_QUIESCENT,
		)
	}

	suspend fun beginCandidateRollback(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
	): StepsWriterTransitionResult {
		val result = withTransitionBoundary(
			phase = StepsWriterTransitionPhase.BEGIN_CANDIDATE_ROLLBACK,
		) { lease ->
			beginCandidateRollbackInTransaction(expectedRolloutRevision, updatedAtMs, lease)
		}
		if (result is StepsWriterTransitionResult.Applied ||
			result is StepsWriterTransitionResult.AlreadyApplied
		) requestStepsDrain()
		return result
	}

	/** Completes candidate retirement while deliberately leaving the source rollout contained. */
	suspend fun completeCandidateRollback(
		expectedContainedRolloutRevision: Long,
		expectedCutoffOrdinal: Long,
		updatedAtMs: Long,
	): StepsWriterTransitionResult = withTransitionBoundary(
		phase = StepsWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK,
	) { lease ->
		completeCandidateRollbackInTransaction(
			expectedContainedRolloutRevision,
			expectedCutoffOrdinal,
			updatedAtMs,
			lease,
		)
	}

	/**
	 * Reconstructs an empty writer generation after the collected rows were cleared.
	 *
	 * The deletion service calls this while its durable marker and startup barrier are still closed.
	 * It intentionally uses a second Room transaction: a process death between deletion and this
	 * commit leaves the marker pending, so startup repeats the deletion and reconciliation before it
	 * can reopen providers. A blocked result throws and therefore cannot clear that marker.
	 */
	suspend fun rearmAfterFullDeletion(updatedAtMs: Long) {
		require(updatedAtMs >= 0L)
		try {
			rearmAfterFullDeletionInTransaction(updatedAtMs)
		} catch (blocked: StepsWriterTransitionBlockedException) {
			throw IllegalStateException(
				"Steps writer deletion re-arm blocked: ${blocked.blocker}",
				blocked,
			)
		}
	}

	private suspend fun withTransitionBoundary(
		phase: StepsWriterTransitionPhase,
		operation: suspend (LifecycleLeaseToken) -> StepsWriterTransitionResult,
	): StepsWriterTransitionResult {
		val startupGate = startupGateProvider.get()
		if (startupGate.reconcile() !is TrackingStartupResult.Ready) {
			return StepsWriterTransitionResult.StartupUnavailable(phase)
		}
		val startupGeneration = startupGate.currentGeneration
		return startupGate.withReadyGenerationOperation(startupGeneration) {
			val ownerToken = "steps-writer-transition:${UUID.randomUUID()}"
			val lease = acquireLease(ownerToken)
				?: return@withReadyGenerationOperation StepsWriterTransitionResult.Busy(phase)
			try {
				try {
					operation(lease)
				} catch (blocked: StepsWriterTransitionBlockedException) {
					StepsWriterTransitionResult.Blocked(phase, blocked.blocker)
				}
			} finally {
				releaseLease(lease)
			}
		} ?: StepsWriterTransitionResult.StartupUnavailable(phase)
	}

	private suspend fun activateCandidateInTransaction(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
		lease: LifecycleLeaseToken,
	): StepsWriterTransitionResult {
		require(expectedRolloutRevision >= 0L)
		require(updatedAtMs >= 0L)
		return database.withTransaction {
			requireLease(lease)
			val binding = stepsBinding()
			val rolloutDao = database.trackingRolloutStateDao()
			val current = rolloutDao.get()?.decodeCurrentModelOrNull()
				?: blocked(StepsWriterTransitionBlocker.ROLLOUT_MISSING_OR_UNREADABLE)
			val ownerDao = database.sourceDestinationOwnerDao()
			val owner = ownerDao.get(STEPS_SOURCE, STEPS_DESTINATION)
				?: blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_MISSING)
			val lane = exactActiveStepsLane()

			if (current.revision == successfulRevisionAfter(expectedRolloutRevision) &&
				owner.owner == CANDIDATE_OWNER && lane?.isExactCanonical(binding) == true &&
				lane.isCanonicalCaptureAuthorizedBy(current, executableLaneCatalog)
			) {
				return@withTransaction StepsWriterTransitionResult.AlreadyApplied(
					StepsWriterTransitionPhase.ACTIVATE_CANDIDATE,
					current.revision,
					owner.ownerGeneration,
					lane.captureAdmissionCutoffOrdinal,
				)
			}

			if (current.revision != expectedRolloutRevision) {
				blocked(StepsWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED)
			}
			requireContainedSteps(current)
			requireRunBoundary()
			if (owner.owner != LEGACY_OWNER) {
				blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
			}
			val shadow = lane ?: blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_MISSING)
			if (!shadow.matches(binding, ProductProjectionStage.EVENT_SHADOW, current.revision)) {
				blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
			}
			if (database.sourceProjectionStateDao().registration(
					binding.projectionId,
					binding.projectionVersion,
				) != null
			) blocked(StepsWriterTransitionBlocker.GLOBAL_WRITER_COLLISION)
			val durableHighWater = database.liveSourceProjectionActivationOrdinal() - 1L
			if (shadow.contiguousAdmissionOrdinal != durableHighWater) {
				blocked(StepsWriterTransitionBlocker.SHADOW_CURSOR_BEHIND)
			}
			val canonicalRevision = nextRevision(current.revision)
			val candidateGeneration = nextOwnerGeneration(owner.ownerGeneration)
			val canonicalRollout = current.copy(
				revision = canonicalRevision,
				sourceOwners = current.sourceOwners + (SourceKind.STEPS to SourceOwner.EVENT),
				productProjectionStages = current.productProjectionStages +
					(SourceKind.STEPS to ProductProjectionStage.EVENT_CANONICAL),
				captureModeMasks = current.captureModeMasks +
					(SourceKind.STEPS to binding.captureModeMask),
			)
			val projectionDao = database.sourceProjectionStateDao()
			if (projectionDao.promoteExactProductLaneToCanonical(
					sourceKind = shadow.sourceKind,
					bindingGeneration = shadow.bindingGeneration,
					projectionId = shadow.projectionId,
					projectionVersion = shadow.projectionVersion,
					captureModeMask = shadow.captureModeMask,
					expectedShadowRolloutRevision = shadow.activatedRolloutRevision,
					activationOrdinal = shadow.activationOrdinal,
					expectedCurrentOrdinal = shadow.contiguousAdmissionOrdinal,
					canonicalRolloutRevision = canonicalRevision,
					updatedAtMs = updatedAtMs,
				) != 1
			) blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
			faultInjector.checkpoint(StepsWriterTransitionCheckpoint.AFTER_LANE_PROMOTION)
			if (ownerDao.compareAndSetOwner(
					sourceKind = STEPS_SOURCE,
					destination = STEPS_DESTINATION,
					expectedOwner = LEGACY_OWNER,
					expectedOwnerGeneration = owner.ownerGeneration,
					newOwner = CANDIDATE_OWNER,
					newOwnerGeneration = candidateGeneration,
					updatedAtMs = updatedAtMs,
				) != 1
			) blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
			faultInjector.checkpoint(StepsWriterTransitionCheckpoint.AFTER_OWNER_PROMOTION)
			rolloutDao.save(canonicalRollout.toEntity(updatedAtMs))
			faultInjector.checkpoint(StepsWriterTransitionCheckpoint.AFTER_CANONICAL_ROLLOUT_SAVE)

			val canonicalLane = exactActiveStepsLane()
			if (canonicalLane?.isExactCanonical(binding) != true ||
				!canonicalLane.isCanonicalCaptureAuthorizedBy(
					canonicalRollout,
					executableLaneCatalog,
				) || !ownerDao.isExactOwner(
					STEPS_SOURCE,
					STEPS_DESTINATION,
					CANDIDATE_OWNER,
					candidateGeneration,
				)
			) blocked(StepsWriterTransitionBlocker.POSTCONDITION_FAILED)
			StepsWriterTransitionResult.Applied(
				StepsWriterTransitionPhase.ACTIVATE_CANDIDATE,
				canonicalRevision,
				candidateGeneration,
				cutoffOrdinal = null,
			)
		}
	}

	private suspend fun beginCandidateRollbackInTransaction(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
		lease: LifecycleLeaseToken,
	): StepsWriterTransitionResult {
		require(expectedRolloutRevision >= 0L)
		require(updatedAtMs >= 0L)
		return database.withTransaction {
			requireLease(lease)
			val binding = stepsBinding()
			val rolloutDao = database.trackingRolloutStateDao()
			val current = rolloutDao.get()?.decodeCurrentModelOrNull()
				?: blocked(StepsWriterTransitionBlocker.ROLLOUT_MISSING_OR_UNREADABLE)
			val owner = database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)
				?: blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_MISSING)
			val lane = exactActiveStepsLane()
			if (current.revision == successfulRevisionAfter(expectedRolloutRevision) &&
				owner.owner == CANDIDATE_OWNER && lane?.isExactCanonical(binding) == true &&
				lane.captureAdmissionCutoffOrdinal != null && current.stepsAreContained()
			) {
				return@withTransaction StepsWriterTransitionResult.AlreadyApplied(
					StepsWriterTransitionPhase.BEGIN_CANDIDATE_ROLLBACK,
					current.revision,
					owner.ownerGeneration,
					lane.captureAdmissionCutoffOrdinal,
				)
			}
			if (current.revision != expectedRolloutRevision) {
				blocked(StepsWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED)
			}
			if (owner.owner != CANDIDATE_OWNER) {
				blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
			}
			val canonical = lane ?: blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_MISSING)
			if (!canonical.isExactCanonical(binding) ||
				!canonical.isCanonicalCaptureAuthorizedBy(current, executableLaneCatalog)
			) blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
			requireRunBoundary()
			val containedRevision = nextRevision(current.revision)
			val cutoffOrdinal = database.liveSourceProjectionActivationOrdinal() - 1L
			val contained = current.copy(
				revision = containedRevision,
				sourceOwners = current.sourceOwners + (SourceKind.STEPS to SourceOwner.CONTAINED),
				productProjectionStages = current.productProjectionStages +
					(SourceKind.STEPS to ProductProjectionStage.LEGACY_CANONICAL),
				captureModeMasks = current.captureModeMasks + (SourceKind.STEPS to 0L),
			)
			rolloutDao.save(contained.toEntity(updatedAtMs))
			faultInjector.checkpoint(StepsWriterTransitionCheckpoint.AFTER_CONTAINED_ROLLOUT_SAVE)
			if (database.sourceProjectionStateDao().fenceProductLaneCaptureAdmission(
					sourceKind = canonical.sourceKind,
					bindingGeneration = canonical.bindingGeneration,
					projectionId = canonical.projectionId,
					projectionVersion = canonical.projectionVersion,
					cutoffOrdinal = cutoffOrdinal,
					updatedAtMs = updatedAtMs,
				) != 1
			) blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
			faultInjector.checkpoint(StepsWriterTransitionCheckpoint.AFTER_ROLLBACK_CUTOFF)
			StepsWriterTransitionResult.Applied(
				StepsWriterTransitionPhase.BEGIN_CANDIDATE_ROLLBACK,
				containedRevision,
				owner.ownerGeneration,
				cutoffOrdinal,
			)
		}
	}

	private suspend fun completeCandidateRollbackInTransaction(
		expectedContainedRolloutRevision: Long,
		expectedCutoffOrdinal: Long,
		updatedAtMs: Long,
		lease: LifecycleLeaseToken,
	): StepsWriterTransitionResult {
		require(expectedContainedRolloutRevision >= 0L)
		require(expectedCutoffOrdinal >= 0L)
		require(updatedAtMs >= 0L)
		return database.withTransaction {
			requireLease(lease)
			val binding = stepsBinding()
			val rollout = database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull()
				?: blocked(StepsWriterTransitionBlocker.ROLLOUT_MISSING_OR_UNREADABLE)
			val ownerDao = database.sourceDestinationOwnerDao()
			val owner = ownerDao.get(STEPS_SOURCE, STEPS_DESTINATION)
				?: blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_MISSING)
			val activeLane = exactActiveStepsLane()
			if (rollout.revision == expectedContainedRolloutRevision &&
				owner.owner == LEGACY_OWNER && activeLane == null
			) {
				val retired = database.sourceProjectionStateDao().productLane(
					STEPS_SOURCE,
					binding.bindingGeneration,
					binding.projectionId,
					binding.projectionVersion,
				)
				if (retired?.status == SourceProductProjectionLaneEntity.STATUS_RETIRED &&
					retired.captureAdmissionCutoffOrdinal == expectedCutoffOrdinal
				) {
					return@withTransaction StepsWriterTransitionResult.AlreadyApplied(
						StepsWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK,
						rollout.revision,
						owner.ownerGeneration,
						expectedCutoffOrdinal,
					)
				}
			}
			if (rollout.revision != expectedContainedRolloutRevision) {
				blocked(StepsWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED)
			}
			requireContainedSteps(rollout)
			if (owner.owner != CANDIDATE_OWNER) {
				blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
			}
			requireRunBoundary()
			val lane = activeLane ?: blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_MISSING)
			if (!lane.isExactCanonical(binding) ||
				lane.captureAdmissionCutoffOrdinal != expectedCutoffOrdinal
			) blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
			if (lane.contiguousAdmissionOrdinal != expectedCutoffOrdinal) {
				blocked(StepsWriterTransitionBlocker.ROLLBACK_CURSOR_BEHIND)
			}
			if (database.sourceProjectionStateDao().retireFencedProductLane(
					sourceKind = lane.sourceKind,
					bindingGeneration = lane.bindingGeneration,
					projectionId = lane.projectionId,
					projectionVersion = lane.projectionVersion,
					expectedCurrentOrdinal = expectedCutoffOrdinal,
					expectedCutoffOrdinal = expectedCutoffOrdinal,
					disposition = SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
					terminalAtMs = updatedAtMs,
				) != 1
			) blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
			faultInjector.checkpoint(StepsWriterTransitionCheckpoint.AFTER_CANDIDATE_LANE_RETIREMENT)
			val legacyGeneration = nextOwnerGeneration(owner.ownerGeneration)
			if (ownerDao.compareAndSetOwner(
					sourceKind = STEPS_SOURCE,
					destination = STEPS_DESTINATION,
					expectedOwner = CANDIDATE_OWNER,
					expectedOwnerGeneration = owner.ownerGeneration,
					newOwner = LEGACY_OWNER,
					newOwnerGeneration = legacyGeneration,
					updatedAtMs = updatedAtMs,
				) != 1
			) blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
			faultInjector.checkpoint(StepsWriterTransitionCheckpoint.AFTER_LEGACY_OWNER_RESTORE)
			StepsWriterTransitionResult.Applied(
				StepsWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK,
				rollout.revision,
				legacyGeneration,
				expectedCutoffOrdinal,
			)
		}
	}

	private suspend fun rearmAfterFullDeletionInTransaction(updatedAtMs: Long) {
		database.withTransaction {
			val sessionDao = database.sourceSessionDao()
			if (sessionDao.hasLifecycleBoundaryBlocker() ||
				sessionDao.hasIncompleteServiceRun() ||
				sessionDao.hasNonterminalLatestLifecycleAction() ||
				database.pendingSignalDao().hasAny() ||
				database.sourceEventWalDao().countAll() != 0L ||
				database.stepFactRevisionDao().countAll() != 0L ||
				database.sourceProjectionStateDao().latestProductLane(STEPS_SOURCE) != null
			) blocked(StepsWriterTransitionBlocker.DELETION_ROWS_REMAIN)

			val ownerDao = database.sourceDestinationOwnerDao()
			if (ownerDao.get(STEPS_SOURCE, STEPS_DESTINATION) == null) {
				ownerDao.insertIfAbsent(
					SourceDestinationOwnerEntity(
						sourceKind = STEPS_SOURCE,
						destination = STEPS_DESTINATION,
						owner = LEGACY_OWNER,
						ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
						updatedAtMs = updatedAtMs,
					),
				)
			}
			val owner = ownerDao.get(STEPS_SOURCE, STEPS_DESTINATION)
				?: blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_MISSING)
			if (owner.owner !in setOf(LEGACY_OWNER, CANDIDATE_OWNER)) {
				blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
			}
			val rolloutDao = database.trackingRolloutStateDao()
			val currentEntity = rolloutDao.get()
			val decodedCurrent = currentEntity?.decodeCurrentModelOrNull()
			val current = decodedCurrent
				?: TrackingRolloutState.contained(revision = currentEntity?.revision ?: 0L)
			val nextRevision = nextRevision(currentEntity?.revision ?: 0L)
			val contained = current.copy(
				revision = nextRevision,
				coordinatorMode = CoordinatorMode.EVENT,
				sourceOwners = SourceKind.entries.associateWith { SourceOwner.CONTAINED },
				productProjectionStages = SourceKind.entries.associateWith {
					ProductProjectionStage.LEGACY_CANONICAL
				},
				captureModeMasks = SourceKind.entries.associateWith { 0L },
				semanticSettingsEnabled = true,
				batteryEstimateMode = BatteryEstimateMode.SOURCE_PLAN_QUALITATIVE,
			)
			val binding = ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS
			val rearmCandidate = owner.owner == CANDIDATE_OWNER && decodedCurrent?.let { rollout ->
				rollout.coordinatorMode == CoordinatorMode.EVENT &&
					rollout.sourceOwners.getValue(SourceKind.STEPS) == SourceOwner.EVENT &&
					rollout.productProjectionStages.getValue(SourceKind.STEPS) ==
					ProductProjectionStage.EVENT_CANONICAL &&
					rollout.captureModeMasks.getValue(SourceKind.STEPS) == binding.captureModeMask &&
					executableLaneCatalog.owns(binding)
			} == true
			val targetOwner = if (rearmCandidate) CANDIDATE_OWNER else LEGACY_OWNER
			val nextOwnerGeneration = nextOwnerGeneration(owner.ownerGeneration)
			val targetRollout = if (rearmCandidate) {
				val activationOrdinal = database.liveSourceProjectionActivationOrdinal()
				database.sourceProjectionStateDao().installProductLane(
					SourceProductProjectionLaneEntity(
						sourceKind = binding.source.stableCode,
						bindingGeneration = binding.bindingGeneration,
						projectionId = binding.projectionId,
						projectionVersion = binding.projectionVersion,
						captureModeMask = binding.captureModeMask,
						productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
						activatedRolloutRevision = nextRevision,
						activationOrdinal = activationOrdinal,
						contiguousAdmissionOrdinal = activationOrdinal - 1L,
						retentionRequired = true,
						status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
						installedAtMs = updatedAtMs,
						updatedAtMs = updatedAtMs,
					),
				)
				faultInjector.checkpoint(StepsWriterTransitionCheckpoint.AFTER_DELETION_LANE_REARM)
				contained.copy(
					sourceOwners = contained.sourceOwners + (SourceKind.STEPS to SourceOwner.EVENT),
					productProjectionStages = contained.productProjectionStages +
						(SourceKind.STEPS to ProductProjectionStage.EVENT_CANONICAL),
					captureModeMasks = contained.captureModeMasks +
						(SourceKind.STEPS to binding.captureModeMask),
				)
			} else {
				contained
			}
			if (ownerDao.compareAndSetOwner(
					sourceKind = STEPS_SOURCE,
					destination = STEPS_DESTINATION,
					expectedOwner = owner.owner,
					expectedOwnerGeneration = owner.ownerGeneration,
					newOwner = targetOwner,
					newOwnerGeneration = nextOwnerGeneration,
					updatedAtMs = updatedAtMs,
				) != 1
			) blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
			faultInjector.checkpoint(StepsWriterTransitionCheckpoint.AFTER_DELETION_OWNER_REARM)
			rolloutDao.save(targetRollout.toEntity(updatedAtMs))
			faultInjector.checkpoint(StepsWriterTransitionCheckpoint.AFTER_DELETION_ROLLOUT_REARM)

			if (rearmCandidate) {
				val lane = exactActiveStepsLane()
				if (lane == null || !lane.isCanonicalCaptureAuthorizedBy(
						targetRollout,
						executableLaneCatalog,
					) || !ownerDao.isExactOwner(
						STEPS_SOURCE,
						STEPS_DESTINATION,
						targetOwner,
						nextOwnerGeneration,
					)
				) blocked(StepsWriterTransitionBlocker.POSTCONDITION_FAILED)
			} else if (exactActiveStepsLane() != null || !ownerDao.isExactOwner(
					STEPS_SOURCE,
					STEPS_DESTINATION,
					targetOwner,
					nextOwnerGeneration,
				)
			) {
				blocked(StepsWriterTransitionBlocker.POSTCONDITION_FAILED)
			}
		}
	}

	private suspend fun requireRunBoundary() {
		val sessionDao = database.sourceSessionDao()
		when {
			sessionDao.hasLifecycleBoundaryBlocker() ->
				blocked(StepsWriterTransitionBlocker.LIFECYCLE_NOT_IDLE)
			sessionDao.hasIncompleteServiceRun() ->
				blocked(StepsWriterTransitionBlocker.SERVICE_RUN_NOT_FINALIZED)
			sessionDao.hasNonterminalLatestLifecycleAction() ->
				blocked(StepsWriterTransitionBlocker.LIFECYCLE_ACTION_NOT_TERMINAL)
			database.pendingSignalDao().hasStepsWriterCommand() ->
				blocked(StepsWriterTransitionBlocker.STEPS_COMMAND_PENDING)
			!database.isSourceCaptureAdmissionFenced(STEPS_SOURCE) ->
				blocked(StepsWriterTransitionBlocker.CAPTURE_CALLBACK_BARRIER_OPEN)
		}
	}

	private fun requireContainedSteps(rollout: TrackingRolloutState) {
		if (!rollout.stepsAreContained()) {
			blocked(StepsWriterTransitionBlocker.ROLLOUT_NOT_CONTAINED)
		}
	}

	private fun TrackingRolloutState.stepsAreContained(): Boolean =
		sourceOwners.getValue(SourceKind.STEPS) == SourceOwner.CONTAINED &&
		productProjectionStages.getValue(SourceKind.STEPS) ==
		ProductProjectionStage.LEGACY_CANONICAL &&
		captureModeMasks.getValue(SourceKind.STEPS) == 0L

	private suspend fun exactActiveStepsLane(): SourceProductProjectionLaneEntity? {
		val lanes = database.sourceProjectionStateDao().allActiveProductLanes()
			.filter { lane -> lane.sourceKind == STEPS_SOURCE }
		if (lanes.size > 1) blocked(StepsWriterTransitionBlocker.MULTIPLE_ACTIVE_LANES)
		return lanes.singleOrNull()
	}

	private fun SourceProductProjectionLaneEntity.isExactCanonical(
		binding: ExecutableSourceLaneBinding,
	): Boolean = matches(
		binding,
		ProductProjectionStage.EVENT_CANONICAL,
		Long.MAX_VALUE,
	) && productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL

	private fun stepsBinding(): ExecutableSourceLaneBinding =
		ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS.also { binding ->
			if (!executableLaneCatalog.owns(binding)) {
				blocked(StepsWriterTransitionBlocker.BINDING_NOT_EXECUTABLE)
			}
		}

	private suspend fun acquireLease(
		ownerToken: String,
	): LifecycleLeaseToken? = database.withTransaction {
		val bootId = bootClockDomainProvider.current()
		val nowElapsedNanos = clock.elapsedRealtimeNanos()
		val nowMs = clock.currentTimeMillis()
		if (bootId.isBlank() || nowElapsedNanos < 0L || nowMs < 0L ||
			nowElapsedNanos > Long.MAX_VALUE - LEASE_DURATION_NANOS ||
			nowMs > Long.MAX_VALUE - LEASE_DURATION_MILLIS
		) return@withTransaction null
		val dao = database.sourceProjectionStateDao()
		val expiresElapsedNanos = nowElapsedNanos + LEASE_DURATION_NANOS
		val expiresAtMs = nowMs + LEASE_DURATION_MILLIS
		val inserted = dao.insertLeaseIfAbsent(
			SourceCoordinatorLeaseEntity(
				leaseName = SESSION_LEASE,
				ownerToken = ownerToken,
				acquiredAtMs = nowMs,
				expiresAtMs = expiresAtMs,
				bootId = bootId,
				generation = 1L,
				acquiredElapsedRealtimeNanos = nowElapsedNanos,
				expiresElapsedRealtimeNanos = expiresElapsedNanos,
			),
		)
		if (inserted < 0L && dao.acquireOrRenewLease(
				leaseName = SESSION_LEASE,
				ownerToken = ownerToken,
				bootId = bootId,
				nowMs = nowMs,
				expiresAtMs = expiresAtMs,
				nowElapsedNanos = nowElapsedNanos,
				expiresElapsedNanos = expiresElapsedNanos,
			) != 1
		) return@withTransaction null
		val current = dao.lease(SESSION_LEASE) ?: return@withTransaction null
		if (current.ownerToken != ownerToken || current.bootId != bootId) return@withTransaction null
		LifecycleLeaseToken(SESSION_LEASE, ownerToken, bootId, current.generation)
	}

	private suspend fun requireLease(lease: LifecycleLeaseToken) {
		val current = database.sourceProjectionStateDao().lease(lease.leaseName)
		val currentBootId = bootClockDomainProvider.current()
		val nowElapsedNanos = clock.elapsedRealtimeNanos()
		if (currentBootId.isBlank() || nowElapsedNanos < 0L || lease.bootId != currentBootId ||
			current?.ownerToken != lease.ownerToken || current.bootId != lease.bootId ||
			current.generation != lease.generation ||
			current.expiresElapsedRealtimeNanos <= nowElapsedNanos
		) blocked(StepsWriterTransitionBlocker.SESSION_LEASE_LOST)
	}

	private suspend fun releaseLease(lease: LifecycleLeaseToken) {
		database.sourceProjectionStateDao().releaseLease(
			leaseName = lease.leaseName,
			ownerToken = lease.ownerToken,
			bootId = lease.bootId,
			generation = lease.generation,
			nowMs = clock.currentTimeMillis().coerceAtLeast(0L),
			nowElapsedNanos = clock.elapsedRealtimeNanos(),
		)
	}

	private fun nextRevision(current: Long): Long {
		if (current == Long.MAX_VALUE) blocked(StepsWriterTransitionBlocker.REVISION_EXHAUSTED)
		return current + 1L
	}

	private fun nextOwnerGeneration(current: Long): Long {
		if (current == Long.MAX_VALUE) blocked(StepsWriterTransitionBlocker.OWNER_GENERATION_EXHAUSTED)
		return current + 1L
	}

	private fun successfulRevisionAfter(expected: Long): Long? =
		if (expected == Long.MAX_VALUE) null else expected + 1L

	private companion object {
		const val SESSION_LEASE = "tracking-session-coordinator"
		const val LEASE_DURATION_NANOS = 30_000_000_000L
		const val LEASE_DURATION_MILLIS = 30_000L
		const val STEPS_SOURCE = SourceDestinationOwnerEntity.SOURCE_STEPS
		const val STEPS_DESTINATION = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS
		const val LEGACY_OWNER = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL
		const val CANDIDATE_OWNER = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
	}
}

/** Process-local fence for the legacy Steps producer; durable backlog is checked separately. */
internal interface LegacyStepsWriterTransitionBoundary {
	suspend fun <T : Any> runIfQuiescent(operation: suspend () -> T): T?

	companion object {
		val ALWAYS_QUIESCENT = object : LegacyStepsWriterTransitionBoundary {
			override suspend fun <T : Any> runIfQuiescent(operation: suspend () -> T): T =
				operation()
		}
	}
}

private class PersistenceLegacyStepsWriterTransitionBoundary(
	private val persistenceProcessorProvider: Provider<PersistenceProcessor>,
) : LegacyStepsWriterTransitionBoundary {
	override suspend fun <T : Any> runIfQuiescent(operation: suspend () -> T): T? =
		persistenceProcessorProvider.get().withLegacyStepsWriterQuiesced(operation)
}

private object FixedStepsWriterTransitionClock : Clock {
	override fun currentTimeMillis(): Long = 1_000L
	override fun elapsedRealtimeNanos(): Long = 1_000L
}

sealed interface StepsWriterTransitionResult {
	val phase: StepsWriterTransitionPhase

	data class Applied(
		override val phase: StepsWriterTransitionPhase,
		val rolloutRevision: Long,
		val ownerGeneration: Long,
		val cutoffOrdinal: Long?,
	) : StepsWriterTransitionResult

	data class AlreadyApplied(
		override val phase: StepsWriterTransitionPhase,
		val rolloutRevision: Long,
		val ownerGeneration: Long,
		val cutoffOrdinal: Long?,
	) : StepsWriterTransitionResult

	data class Blocked(
		override val phase: StepsWriterTransitionPhase,
		val blocker: StepsWriterTransitionBlocker,
	) : StepsWriterTransitionResult

	data class StartupUnavailable(
		override val phase: StepsWriterTransitionPhase,
	) : StepsWriterTransitionResult

	data class Busy(
		override val phase: StepsWriterTransitionPhase,
	) : StepsWriterTransitionResult
}

enum class StepsWriterTransitionPhase {
	ACTIVATE_CANDIDATE,
	BEGIN_CANDIDATE_ROLLBACK,
	COMPLETE_CANDIDATE_ROLLBACK,
}

enum class StepsWriterTransitionBlocker {
	ROLLOUT_MISSING_OR_UNREADABLE,
	ROLLOUT_REVISION_CHANGED,
	ROLLOUT_NOT_CONTAINED,
	LIFECYCLE_NOT_IDLE,
	SERVICE_RUN_NOT_FINALIZED,
	LIFECYCLE_ACTION_NOT_TERMINAL,
	LEGACY_STEPS_WRITER_NOT_QUIESCENT,
	STEPS_COMMAND_PENDING,
	CAPTURE_CALLBACK_BARRIER_OPEN,
	DESTINATION_OWNER_MISSING,
	DESTINATION_OWNER_CONFLICT,
	DESTINATION_OWNER_CHANGED,
	BINDING_NOT_EXECUTABLE,
	ACTIVE_LANE_MISSING,
	ACTIVE_LANE_CONFLICT,
	ACTIVE_LANE_CHANGED,
	MULTIPLE_ACTIVE_LANES,
	GLOBAL_WRITER_COLLISION,
	SHADOW_CURSOR_BEHIND,
	ROLLBACK_CURSOR_BEHIND,
	SESSION_LEASE_LOST,
	REVISION_EXHAUSTED,
	OWNER_GENERATION_EXHAUSTED,
	DELETION_ROWS_REMAIN,
	POSTCONDITION_FAILED,
}

internal enum class StepsWriterTransitionCheckpoint {
	AFTER_LANE_PROMOTION,
	AFTER_OWNER_PROMOTION,
	AFTER_CANONICAL_ROLLOUT_SAVE,
	AFTER_CONTAINED_ROLLOUT_SAVE,
	AFTER_ROLLBACK_CUTOFF,
	AFTER_CANDIDATE_LANE_RETIREMENT,
	AFTER_LEGACY_OWNER_RESTORE,
	AFTER_DELETION_LANE_REARM,
	AFTER_DELETION_OWNER_REARM,
	AFTER_DELETION_ROLLOUT_REARM,
}

internal fun interface StepsWriterTransitionFaultInjector {
	fun checkpoint(checkpoint: StepsWriterTransitionCheckpoint)

	companion object {
		val NONE = StepsWriterTransitionFaultInjector { }
	}
}

private class StepsWriterTransitionBlockedException(
	val blocker: StepsWriterTransitionBlocker,
) : RuntimeException(blocker.name)

private fun blocked(blocker: StepsWriterTransitionBlocker): Nothing =
	throw StepsWriterTransitionBlockedException(blocker)
