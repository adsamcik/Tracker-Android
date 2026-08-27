package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.liveSourceProjectionActivationOrdinal
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject

internal class StepsWriterRollbackTransition @Inject constructor(
	private val state: StepsWriterTransitionState,
	private val boundary: StepsWriterTransitionBoundary,
	private val hooks: StepsWriterTransitionRuntimeHooks,
) {
	suspend fun begin(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
		lease: LifecycleLeaseToken,
	): StepsWriterTransitionResult {
		require(expectedRolloutRevision >= 0L)
		require(updatedAtMs >= 0L)
		return state.database.withTransaction {
			boundary.requireLease(lease)
			val snapshot = loadSnapshot()
			alreadyBegun(snapshot, expectedRolloutRevision)?.let { return@withTransaction it }
			validateBegin(snapshot, expectedRolloutRevision)

			val containedRevision = nextRolloutRevision(snapshot.rollout.revision)
			val cutoffOrdinal = state.database.liveSourceProjectionActivationOrdinal() - 1L
			val contained = containedRollout(snapshot.rollout, containedRevision)
			state.database.trackingRolloutStateDao().save(contained.toEntity(updatedAtMs))
			hooks.checkpoint(StepsWriterTransitionCheckpoint.AFTER_CONTAINED_ROLLOUT_SAVE)
			fenceLane(snapshot.lane, cutoffOrdinal, updatedAtMs)

			StepsWriterTransitionResult.Applied(
				StepsWriterTransitionPhase.BEGIN_CANDIDATE_ROLLBACK,
				containedRevision,
				snapshot.owner.ownerGeneration,
				cutoffOrdinal,
			)
		}
	}

	suspend fun complete(
		expectedContainedRolloutRevision: Long,
		expectedCutoffOrdinal: Long,
		updatedAtMs: Long,
		lease: LifecycleLeaseToken,
	): StepsWriterTransitionResult {
		require(expectedContainedRolloutRevision >= 0L)
		require(expectedCutoffOrdinal >= 0L)
		require(updatedAtMs >= 0L)
		return state.database.withTransaction {
			boundary.requireLease(lease)
			val snapshot = loadSnapshot()
			alreadyCompleted(snapshot, expectedContainedRolloutRevision, expectedCutoffOrdinal)
				?.let { return@withTransaction it }
			validateComplete(snapshot, expectedContainedRolloutRevision, expectedCutoffOrdinal)

			retireLane(snapshot.lane, expectedCutoffOrdinal, updatedAtMs)
			val legacyGeneration = restoreLegacyOwner(snapshot.owner, updatedAtMs)
			StepsWriterTransitionResult.Applied(
				StepsWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK,
				snapshot.rollout.revision,
				legacyGeneration,
				expectedCutoffOrdinal,
			)
		}
	}

	private suspend fun loadSnapshot(): RollbackSnapshot {
		val binding = state.binding()
		val rollout = state.database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull()
			?: blocked(StepsWriterTransitionBlocker.ROLLOUT_MISSING_OR_UNREADABLE)
		val owner = state.database.sourceDestinationOwnerDao().get(
			StepsWriterDestination.SOURCE,
			StepsWriterDestination.DESTINATION,
		) ?: blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_MISSING)
		return RollbackSnapshot(binding, rollout, owner, state.exactActiveLane())
	}

	private fun alreadyBegun(
		snapshot: RollbackSnapshot,
		expectedRolloutRevision: Long,
	): StepsWriterTransitionResult.AlreadyApplied? {
		val lane = snapshot.lane ?: return null
		val cutoffOrdinal = lane.captureAdmissionCutoffOrdinal ?: return null
		val matchesBegunState =
			snapshot.rollout.revision == successfulRevisionAfter(expectedRolloutRevision) &&
				snapshot.owner.owner == StepsWriterDestination.CANDIDATE_OWNER &&
				lane.isExactCanonicalStepsLane(snapshot.binding) &&
				snapshot.rollout.stepsAreContained()
		if (!matchesBegunState) {
			return null
		}
		return StepsWriterTransitionResult.AlreadyApplied(
			StepsWriterTransitionPhase.BEGIN_CANDIDATE_ROLLBACK,
			snapshot.rollout.revision,
			snapshot.owner.ownerGeneration,
			cutoffOrdinal,
		)
	}

	private suspend fun validateBegin(
		snapshot: RollbackSnapshot,
		expectedRolloutRevision: Long,
	) {
		if (snapshot.rollout.revision != expectedRolloutRevision) {
			blocked(StepsWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED)
		}
		if (snapshot.owner.owner != StepsWriterDestination.CANDIDATE_OWNER) {
			blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
		}
		val lane = snapshot.lane ?: blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_MISSING)
		if (!lane.isExactCanonicalStepsLane(snapshot.binding)) {
			blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		}
		if (!lane.isCanonicalCaptureAuthorizedBy(snapshot.rollout, state.executableLaneCatalog)) {
			blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		}
		state.requireRunBoundary()
	}

	private fun containedRollout(
		current: TrackingRolloutState,
		containedRevision: Long,
	): TrackingRolloutState = current.copy(
		revision = containedRevision,
		sourceOwners = current.sourceOwners + (SourceKind.STEPS to SourceOwner.CONTAINED),
		productProjectionStages = current.productProjectionStages +
			(SourceKind.STEPS to ProductProjectionStage.LEGACY_CANONICAL),
		captureModeMasks = current.captureModeMasks + (SourceKind.STEPS to 0L),
	)

	private suspend fun fenceLane(
		lane: SourceProductProjectionLaneEntity?,
		cutoffOrdinal: Long,
		updatedAtMs: Long,
	) {
		val canonical = lane ?: blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_MISSING)
		val updated = state.database.sourceProjectionStateDao().fenceProductLaneCaptureAdmission(
			sourceKind = canonical.sourceKind,
			bindingGeneration = canonical.bindingGeneration,
			projectionId = canonical.projectionId,
			projectionVersion = canonical.projectionVersion,
			cutoffOrdinal = cutoffOrdinal,
			updatedAtMs = updatedAtMs,
		)
		if (updated != 1) {
			blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
		}
		hooks.checkpoint(StepsWriterTransitionCheckpoint.AFTER_ROLLBACK_CUTOFF)
	}

	private suspend fun alreadyCompleted(
		snapshot: RollbackSnapshot,
		expectedRolloutRevision: Long,
		expectedCutoffOrdinal: Long,
	): StepsWriterTransitionResult.AlreadyApplied? {
		val retired = state.database.sourceProjectionStateDao().productLane(
			StepsWriterDestination.SOURCE,
			snapshot.binding.bindingGeneration,
			snapshot.binding.projectionId,
			snapshot.binding.projectionVersion,
		) ?: return null
		val authorityMatches = snapshot.rollout.revision == expectedRolloutRevision &&
			snapshot.owner.owner == StepsWriterDestination.LEGACY_OWNER &&
			snapshot.lane == null
		val retiredLaneMatches = retired.status == SourceProductProjectionLaneEntity.STATUS_RETIRED &&
			retired.captureAdmissionCutoffOrdinal == expectedCutoffOrdinal
		if (!authorityMatches || !retiredLaneMatches) {
			return null
		}
		return StepsWriterTransitionResult.AlreadyApplied(
			StepsWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK,
			snapshot.rollout.revision,
			snapshot.owner.ownerGeneration,
			expectedCutoffOrdinal,
		)
	}

	private suspend fun validateComplete(
		snapshot: RollbackSnapshot,
		expectedRolloutRevision: Long,
		expectedCutoffOrdinal: Long,
	) {
		if (snapshot.rollout.revision != expectedRolloutRevision) {
			blocked(StepsWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED)
		}
		state.requireContainedSteps(snapshot.rollout)
		if (snapshot.owner.owner != StepsWriterDestination.CANDIDATE_OWNER) {
			blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
		}
		state.requireRunBoundary()
		val lane = snapshot.lane ?: blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_MISSING)
		if (!lane.isExactCanonicalStepsLane(snapshot.binding)) {
			blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		}
		if (lane.captureAdmissionCutoffOrdinal != expectedCutoffOrdinal) {
			blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		}
		if (lane.contiguousAdmissionOrdinal != expectedCutoffOrdinal) {
			blocked(StepsWriterTransitionBlocker.ROLLBACK_CURSOR_BEHIND)
		}
	}

	private suspend fun retireLane(
		lane: SourceProductProjectionLaneEntity?,
		expectedCutoffOrdinal: Long,
		updatedAtMs: Long,
	) {
		val canonical = lane ?: blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_MISSING)
		val updated = state.database.sourceProjectionStateDao().retireFencedProductLane(
			sourceKind = canonical.sourceKind,
			bindingGeneration = canonical.bindingGeneration,
			projectionId = canonical.projectionId,
			projectionVersion = canonical.projectionVersion,
			expectedCurrentOrdinal = expectedCutoffOrdinal,
			expectedCutoffOrdinal = expectedCutoffOrdinal,
			disposition = SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
			terminalAtMs = updatedAtMs,
		)
		if (updated != 1) {
			blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
		}
		hooks.checkpoint(StepsWriterTransitionCheckpoint.AFTER_CANDIDATE_LANE_RETIREMENT)
	}

	private suspend fun restoreLegacyOwner(
		owner: SourceDestinationOwnerEntity,
		updatedAtMs: Long,
	): Long {
		val legacyGeneration = nextOwnerGeneration(owner.ownerGeneration)
		val updated = state.database.sourceDestinationOwnerDao().compareAndSetOwner(
			sourceKind = StepsWriterDestination.SOURCE,
			destination = StepsWriterDestination.DESTINATION,
			expectedOwner = StepsWriterDestination.CANDIDATE_OWNER,
			expectedOwnerGeneration = owner.ownerGeneration,
			newOwner = StepsWriterDestination.LEGACY_OWNER,
			newOwnerGeneration = legacyGeneration,
			updatedAtMs = updatedAtMs,
		)
		if (updated != 1) {
			blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
		}
		hooks.checkpoint(StepsWriterTransitionCheckpoint.AFTER_LEGACY_OWNER_RESTORE)
		return legacyGeneration
	}
}

private data class RollbackSnapshot(
	val binding: ExecutableSourceLaneBinding,
	val rollout: TrackingRolloutState,
	val owner: SourceDestinationOwnerEntity,
	val lane: SourceProductProjectionLaneEntity?,
)
