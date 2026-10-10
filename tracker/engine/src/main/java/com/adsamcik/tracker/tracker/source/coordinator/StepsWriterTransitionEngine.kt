package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.liveSourceProjectionActivationOrdinal
import javax.inject.Inject
import javax.inject.Provider

internal class StepsWriterTransitionEngine @Inject constructor(
	private val state: StepsWriterTransitionState,
	private val boundary: StepsWriterTransitionBoundary,
	private val activation: StepsWriterActivationTransition,
	private val rollback: StepsWriterRollbackTransition,
	private val deletionRearm: StepsWriterDeletionRearm,
	private val hooks: StepsWriterTransitionRuntimeHooks,
) {
	/** Uses the established Steps writer/owner arithmetic; no new generation engine. */
	internal suspend fun preparePreferredWriter(updatedAtMs: Long): StepsWriterTransitionResult {
		require(updatedAtMs >= 0L)
		val desired = ExecutableSourceLaneCatalog.PREFERRED_STEPS_SESSION_FACTS
		val initial = state.database.withTransaction {
			val rollout = state.database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull()
				?: blocked(StepsWriterTransitionBlocker.ROLLOUT_MISSING_OR_UNREADABLE)
			val lane = state.exactActiveLane()
			val ready = lane?.takeIf { state.executableLaneCatalog.bindingFor(it) == desired &&
				it.captureAdmissionCutoffOrdinal == null &&
				it.isCanonicalCaptureAuthorizedBy(state.database, rollout, state.executableLaneCatalog) }
			ready to rollout
		}
		initial.first?.let { lane ->
			val owner = state.database.sourceDestinationOwnerDao().get(StepsWriterDestination.SOURCE, StepsWriterDestination.DESTINATION)
			return StepsWriterTransitionResult.AlreadyApplied(StepsWriterTransitionPhase.ACTIVATE_CANDIDATE,
				initial.second.revision, requireNotNull(owner).ownerGeneration, lane.captureAdmissionCutoffOrdinal)
		}
		var active = state.exactActiveLane()
		if (active != null && state.executableLaneCatalog.bindingFor(active) == ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1) {
			if (active.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL) {
				if (active.captureAdmissionCutoffOrdinal == null) {
					val begun = beginCandidateRollback(currentRolloutRevision(), updatedAtMs)
					if (begun !is StepsWriterTransitionResult.Applied && begun !is StepsWriterTransitionResult.AlreadyApplied) return begun
				}
				active = state.exactActiveLane()
				val cutoff = active?.captureAdmissionCutoffOrdinal ?: return StepsWriterTransitionResult.Blocked(
					StepsWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK, StepsWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
				if (active?.contiguousAdmissionOrdinal != cutoff) return StepsWriterTransitionResult.Blocked(
					StepsWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK, StepsWriterTransitionBlocker.ROLLBACK_CURSOR_BEHIND)
				val completed = completeCandidateRollback(currentRolloutRevision(), cutoff, updatedAtMs)
				if (completed !is StepsWriterTransitionResult.Applied && completed !is StepsWriterTransitionResult.AlreadyApplied) return completed
			} else if (active.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW) {
				var needsDrain = false
				val retired = boundary.run(StepsWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK) { lease ->
					state.database.withTransaction {
						boundary.requireLease(lease); state.requireRunBoundary()
						var shadow = state.exactActiveLane() ?: blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
						if (state.executableLaneCatalog.bindingFor(shadow) != ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1 ||
							shadow.productStage != SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW)
							blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
						val owner = state.database.sourceDestinationOwnerDao().get(StepsWriterDestination.SOURCE, StepsWriterDestination.DESTINATION)
						if (owner?.owner != StepsWriterDestination.LEGACY_OWNER) blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
						val store = RoomTrackingRolloutStateStore(state.database, state.executableLaneCatalog)
						if (shadow.captureAdmissionCutoffOrdinal == null) shadow = store.beginLaneContainment(
							ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1, currentRolloutRevision() + 1L, updatedAtMs).lane
						val cutoff = requireNotNull(shadow.captureAdmissionCutoffOrdinal)
						if (shadow.contiguousAdmissionOrdinal != cutoff) {
							needsDrain = true
							return@withTransaction StepsWriterTransitionResult.Blocked(StepsWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK,
								StepsWriterTransitionBlocker.SHADOW_CURSOR_BEHIND)
						}
						val rollout = store.finishLaneRetirement(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1, cutoff, updatedAtMs)
						StepsWriterTransitionResult.Applied(StepsWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK,
							rollout.revision, owner.ownerGeneration, cutoff)
					}
				}
				if (needsDrain) hooks.requestStepsDrain()
				if (retired !is StepsWriterTransitionResult.Applied && retired !is StepsWriterTransitionResult.AlreadyApplied) return retired
			} else return StepsWriterTransitionResult.Blocked(StepsWriterTransitionPhase.ACTIVATE_CANDIDATE, StepsWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		}
		active = state.exactActiveLane()
		if (active == null) {
			val installed = boundary.run(StepsWriterTransitionPhase.ACTIVATE_CANDIDATE) { lease ->
				state.database.withTransaction {
					boundary.requireLease(lease); state.requireRunBoundary()
					val rollout = state.database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull()
						?: blocked(StepsWriterTransitionBlocker.ROLLOUT_MISSING_OR_UNREADABLE)
					state.requireContainedSteps(rollout)
					val owner = state.database.sourceDestinationOwnerDao().get(StepsWriterDestination.SOURCE, StepsWriterDestination.DESTINATION)
						?: blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_MISSING)
					if (owner.owner != StepsWriterDestination.LEGACY_OWNER) blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
					val latest = state.database.sourceProjectionStateDao().latestProductLane(StepsWriterDestination.SOURCE)
					val store = RoomTrackingRolloutStateStore(state.database, state.executableLaneCatalog)
					if (latest == null) store.installInertShadowLane(desired, currentRolloutRevision() + 1L, updatedAtMs)
					else {
						if (state.executableLaneCatalog.bindingFor(latest) != ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1 ||
							latest.status != SourceProductProjectionLaneEntity.STATUS_RETIRED || latest.retentionRequired ||
							latest.terminalDisposition != SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN ||
							latest.terminalAtMs == null || latest.captureAdmissionCutoffOrdinal == null ||
							latest.contiguousAdmissionOrdinal != latest.captureAdmissionCutoffOrdinal)
							blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
						store.rearmInertShadowLane(desired, currentRolloutRevision() + 1L, updatedAtMs)
					}
					StepsWriterTransitionResult.Applied(StepsWriterTransitionPhase.ACTIVATE_CANDIDATE, currentRolloutRevision(), owner.ownerGeneration, null)
				}
			}
			if (installed !is StepsWriterTransitionResult.Applied && installed !is StepsWriterTransitionResult.AlreadyApplied) return installed
		} else if (state.executableLaneCatalog.bindingFor(active) != desired ||
			active.productStage != SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW) return StepsWriterTransitionResult.Blocked(
				StepsWriterTransitionPhase.ACTIVATE_CANDIDATE, StepsWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		return activateCandidate(currentRolloutRevision(), updatedAtMs)
	}

	private suspend fun currentRolloutRevision(): Long = state.database.trackingRolloutStateDao().get()?.revision
		?.takeIf { it < Long.MAX_VALUE } ?: blocked(StepsWriterTransitionBlocker.REVISION_EXHAUSTED)

	suspend fun activateCandidate(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
	): StepsWriterTransitionResult = boundary.run(StepsWriterTransitionPhase.ACTIVATE_CANDIDATE) { lease ->
		boundary.runIfLegacyWriterQuiescent {
			activation.apply(expectedRolloutRevision, updatedAtMs, lease)
		} ?: StepsWriterTransitionResult.Blocked(
			StepsWriterTransitionPhase.ACTIVATE_CANDIDATE,
			StepsWriterTransitionBlocker.LEGACY_STEPS_WRITER_NOT_QUIESCENT,
		)
	}

	suspend fun beginCandidateRollback(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
	): StepsWriterTransitionResult {
		val result = boundary.run(StepsWriterTransitionPhase.BEGIN_CANDIDATE_ROLLBACK) { lease ->
			rollback.begin(expectedRolloutRevision, updatedAtMs, lease)
		}
		if (result is StepsWriterTransitionResult.Applied ||
			result is StepsWriterTransitionResult.AlreadyApplied
		) {
			hooks.requestStepsDrain()
		}
		return result
	}

	suspend fun completeCandidateRollback(
		expectedContainedRolloutRevision: Long,
		expectedCutoffOrdinal: Long,
		updatedAtMs: Long,
	): StepsWriterTransitionResult = boundary.run(
		StepsWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK,
	) { lease ->
		rollback.complete(
			expectedContainedRolloutRevision,
			expectedCutoffOrdinal,
			updatedAtMs,
			lease,
		)
	}

	suspend fun rearmAfterFullDeletion(updatedAtMs: Long) {
		deletionRearm.apply(updatedAtMs)
	}

	suspend fun rearmAfterFullDeletion(
		operationId: String,
		targetCollectedDataEpoch: Long,
		updatedAtMs: Long,
	) {
		deletionRearm.apply(operationId, targetCollectedDataEpoch, updatedAtMs)
	}

	companion object {
		fun forTest(
			database: AppDatabase,
			executableLaneCatalog: ExecutableSourceLaneCatalog,
			dependencies: StepsWriterTransitionTestDependencies,
		): StepsWriterTransitionEngine {
			val state = StepsWriterTransitionState(database, executableLaneCatalog)
			val boundary = StepsWriterTransitionBoundary.forTest(database, dependencies)
			val hooks = StepsWriterTransitionRuntimeHooks.forTest(dependencies)
			return StepsWriterTransitionEngine(
				state,
				boundary,
				StepsWriterActivationTransition(state, boundary, hooks),
				StepsWriterRollbackTransition(state, boundary, hooks),
				StepsWriterDeletionRearm(state, hooks),
				hooks,
			)
		}
	}
}

internal class StepsWriterTransitionRuntimeHooks private constructor(
	private val requestDrain: () -> Unit,
	private val faultInjector: StepsWriterTransitionFaultInjector,
) {
	@Inject
	constructor(sourcePipelineRecoveryProvider: Provider<SourcePipelineRecovery>) : this(
		{ sourcePipelineRecoveryProvider.get().requestStepsSessionFactDrain() },
		StepsWriterTransitionFaultInjector.NONE,
	)

	fun requestStepsDrain() {
		requestDrain()
	}

	fun checkpoint(checkpoint: StepsWriterTransitionCheckpoint) {
		faultInjector.checkpoint(checkpoint)
	}

	companion object {
		fun forTest(dependencies: StepsWriterTransitionTestDependencies) =
			StepsWriterTransitionRuntimeHooks(
				dependencies.requestStepsDrain,
				dependencies.faultInjector,
			)
	}
}
