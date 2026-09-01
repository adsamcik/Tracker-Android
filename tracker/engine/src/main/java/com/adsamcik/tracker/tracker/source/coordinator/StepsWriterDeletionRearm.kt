package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.liveSourceProjectionActivationOrdinal
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject

internal class StepsWriterDeletionRearm @Inject constructor(
	private val state: StepsWriterTransitionState,
	private val hooks: StepsWriterTransitionRuntimeHooks,
) {
	suspend fun apply(updatedAtMs: Long) {
		state.database.withTransaction {
			if (deletionRowsRemain()) {
				blocked(StepsWriterTransitionBlocker.DELETION_ROWS_REMAIN)
			}
			val owner = ensureOwner(updatedAtMs)
			val rolloutState = loadRolloutState()
			val nextRevision = nextRolloutRevision(rolloutState.entityRevision)
			val contained = containedRollout(rolloutState.model, nextRevision)
			val binding = candidateRearmBinding(owner, rolloutState.decodedModel)
			val rearmCandidate = binding != null
			val targetOwner = if (rearmCandidate) {
				StepsWriterDestination.CANDIDATE_OWNER
			} else {
				StepsWriterDestination.LEGACY_OWNER
			}
			val targetRollout = if (rearmCandidate) {
				installCandidateLane(contained, requireNotNull(binding), nextRevision, updatedAtMs)
			} else {
				contained
			}
			val nextGeneration = updateOwner(owner, targetOwner, updatedAtMs)
			state.database.trackingRolloutStateDao().save(targetRollout.toEntity(updatedAtMs))
			hooks.checkpoint(StepsWriterTransitionCheckpoint.AFTER_DELETION_ROLLOUT_REARM)
			verifyPostcondition(rearmCandidate, targetRollout, targetOwner, nextGeneration)
		}
	}

	private suspend fun deletionRowsRemain(): Boolean {
		val sessionDao = state.database.sourceSessionDao()
		val lifecycleRowsRemain = sessionDao.hasLifecycleBoundaryBlocker() ||
			sessionDao.hasIncompleteServiceRun() ||
			sessionDao.hasNonterminalLatestLifecycleAction()
		val pipelineRowsRemain = state.database.pendingSignalDao().hasAny() ||
			state.database.sourceEventWalDao().countAll() != 0L ||
			state.database.stepFactRevisionDao().countAll() != 0L
		val destinationRowsRemain = state.database.stepIntervalDao().hasAny() ||
			state.database.sourceProjectionStateDao()
				.latestProductLane(StepsWriterDestination.SOURCE) != null
		return lifecycleRowsRemain || pipelineRowsRemain || destinationRowsRemain
	}

	private suspend fun ensureOwner(updatedAtMs: Long): SourceDestinationOwnerEntity {
		val dao = state.database.sourceDestinationOwnerDao()
		if (dao.get(StepsWriterDestination.SOURCE, StepsWriterDestination.DESTINATION) == null) {
			dao.insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = StepsWriterDestination.SOURCE,
					destination = StepsWriterDestination.DESTINATION,
					owner = StepsWriterDestination.LEGACY_OWNER,
					ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
					updatedAtMs = updatedAtMs,
				),
			)
		}
		val owner = dao.get(StepsWriterDestination.SOURCE, StepsWriterDestination.DESTINATION)
			?: blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_MISSING)
		if (owner.owner != StepsWriterDestination.LEGACY_OWNER &&
			owner.owner != StepsWriterDestination.CANDIDATE_OWNER
		) {
			blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
		}
		return owner
	}

	private suspend fun loadRolloutState(): DeletionRolloutState {
		val entity = state.database.trackingRolloutStateDao().get()
		val decoded = entity?.decodeCurrentModelOrNull()
		val model = decoded ?: TrackingRolloutState.contained(revision = entity?.revision ?: 0L)
		return DeletionRolloutState(entity?.revision ?: 0L, decoded, model)
	}

	private fun containedRollout(
		current: TrackingRolloutState,
		nextRevision: Long,
	): TrackingRolloutState = current.copy(
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

	private fun candidateRearmBinding(
		owner: SourceDestinationOwnerEntity,
		rollout: TrackingRolloutState?,
	): ExecutableSourceLaneBinding? {
		val current = rollout ?: return null
		val authorityMatches = owner.owner == StepsWriterDestination.CANDIDATE_OWNER &&
			current.coordinatorMode == CoordinatorMode.EVENT &&
			current.sourceOwners.getValue(SourceKind.STEPS) == SourceOwner.EVENT
		val previousBinding = state.executableLaneCatalog.bindingForCaptureModeMask(
			SourceKind.STEPS,
			current.captureModeMasks.getValue(SourceKind.STEPS),
		)
		val outputMatches = current.productProjectionStages.getValue(SourceKind.STEPS) ==
			ProductProjectionStage.EVENT_CANONICAL && previousBinding != null
		return previousBinding?.takeIf { authorityMatches && outputMatches }
	}

	private suspend fun installCandidateLane(
		contained: TrackingRolloutState,
		binding: ExecutableSourceLaneBinding,
		nextRevision: Long,
		updatedAtMs: Long,
	): TrackingRolloutState {
		val activationOrdinal = state.database.liveSourceProjectionActivationOrdinal()
		state.database.sourceProjectionStateDao().installProductLane(
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
		hooks.checkpoint(StepsWriterTransitionCheckpoint.AFTER_DELETION_LANE_REARM)
		return contained.copy(
			sourceOwners = contained.sourceOwners + (SourceKind.STEPS to SourceOwner.EVENT),
			productProjectionStages = contained.productProjectionStages +
				(SourceKind.STEPS to ProductProjectionStage.EVENT_CANONICAL),
			captureModeMasks = contained.captureModeMasks +
				(SourceKind.STEPS to binding.captureModeMask),
		)
	}

	private suspend fun updateOwner(
		current: SourceDestinationOwnerEntity,
		targetOwner: String,
		updatedAtMs: Long,
	): Long {
		val nextGeneration = nextOwnerGeneration(current.ownerGeneration)
		val updated = state.database.sourceDestinationOwnerDao().compareAndSetOwner(
			sourceKind = StepsWriterDestination.SOURCE,
			destination = StepsWriterDestination.DESTINATION,
			expectedOwner = current.owner,
			expectedOwnerGeneration = current.ownerGeneration,
			newOwner = targetOwner,
			newOwnerGeneration = nextGeneration,
			updatedAtMs = updatedAtMs,
		)
		if (updated != 1) {
			blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
		}
		hooks.checkpoint(StepsWriterTransitionCheckpoint.AFTER_DELETION_OWNER_REARM)
		return nextGeneration
	}

	private suspend fun verifyPostcondition(
		rearmCandidate: Boolean,
		targetRollout: TrackingRolloutState,
		targetOwner: String,
		targetGeneration: Long,
	) {
		val ownerMatches = state.database.sourceDestinationOwnerDao().isExactOwner(
			StepsWriterDestination.SOURCE,
			StepsWriterDestination.DESTINATION,
			targetOwner,
			targetGeneration,
		)
		if (!ownerMatches) {
			blocked(StepsWriterTransitionBlocker.POSTCONDITION_FAILED)
		}
		val lane = state.exactActiveLane()
		if (rearmCandidate) {
			verifyCandidateLane(lane, targetRollout)
		} else if (lane != null) {
			blocked(StepsWriterTransitionBlocker.POSTCONDITION_FAILED)
		}
	}

	private fun verifyCandidateLane(
		lane: SourceProductProjectionLaneEntity?,
		rollout: TrackingRolloutState,
	) {
		if (lane == null) {
			blocked(StepsWriterTransitionBlocker.POSTCONDITION_FAILED)
		}
		if (!lane.isCanonicalCaptureAuthorizedBy(rollout, state.executableLaneCatalog)) {
			blocked(StepsWriterTransitionBlocker.POSTCONDITION_FAILED)
		}
	}
}

private data class DeletionRolloutState(
	val entityRevision: Long,
	val decodedModel: TrackingRolloutState?,
	val model: TrackingRolloutState,
)
