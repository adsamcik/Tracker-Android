package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.liveSourceProjectionActivationOrdinal
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject

internal class StepsWriterActivationTransition @Inject constructor(
	private val state: StepsWriterTransitionState,
	private val boundary: StepsWriterTransitionBoundary,
	private val hooks: StepsWriterTransitionRuntimeHooks,
) {
	suspend fun apply(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
		lease: LifecycleLeaseToken,
	): StepsWriterTransitionResult {
		require(expectedRolloutRevision >= 0L)
		require(updatedAtMs >= 0L)
		return state.database.withTransaction {
			boundary.requireLease(lease)
			val snapshot = loadSnapshot()
			alreadyApplied(snapshot, expectedRolloutRevision)?.let { return@withTransaction it }
			validateSnapshot(snapshot, expectedRolloutRevision)

			val canonicalRevision = nextRolloutRevision(snapshot.rollout.revision)
			val candidateGeneration = nextOwnerGeneration(snapshot.owner.ownerGeneration)
			val canonicalRollout = canonicalRollout(snapshot.rollout, snapshot.binding, canonicalRevision)
			promoteLane(snapshot.lane, canonicalRevision, updatedAtMs)
			promoteOwner(snapshot.owner, candidateGeneration, updatedAtMs)
			state.database.trackingRolloutStateDao().save(canonicalRollout.toEntity(updatedAtMs))
			hooks.checkpoint(StepsWriterTransitionCheckpoint.AFTER_CANONICAL_ROLLOUT_SAVE)
			verifyPostcondition(snapshot.binding, canonicalRollout, candidateGeneration)

			StepsWriterTransitionResult.Applied(
				StepsWriterTransitionPhase.ACTIVATE_CANDIDATE,
				canonicalRevision,
				candidateGeneration,
				cutoffOrdinal = null,
			)
		}
	}

	private suspend fun loadSnapshot(): ActivationSnapshot {
		val binding = state.binding()
		val rollout = state.database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull()
			?: blocked(StepsWriterTransitionBlocker.ROLLOUT_MISSING_OR_UNREADABLE)
		val owner = state.database.sourceDestinationOwnerDao().get(
			StepsWriterDestination.SOURCE,
			StepsWriterDestination.DESTINATION,
		) ?: blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_MISSING)
		return ActivationSnapshot(binding, rollout, owner, state.exactActiveLane())
	}

	private fun alreadyApplied(
		snapshot: ActivationSnapshot,
		expectedRolloutRevision: Long,
	): StepsWriterTransitionResult.AlreadyApplied? {
		val lane = snapshot.lane ?: return null
		val matchesAppliedState =
			snapshot.rollout.revision == successfulRevisionAfter(expectedRolloutRevision) &&
				snapshot.owner.owner == StepsWriterDestination.CANDIDATE_OWNER &&
				lane.isExactCanonicalStepsLane(snapshot.binding) &&
				lane.isCanonicalCaptureAuthorizedBy(snapshot.rollout, state.executableLaneCatalog)
		if (!matchesAppliedState) {
			return null
		}
		return StepsWriterTransitionResult.AlreadyApplied(
			StepsWriterTransitionPhase.ACTIVATE_CANDIDATE,
			snapshot.rollout.revision,
			snapshot.owner.ownerGeneration,
			lane.captureAdmissionCutoffOrdinal,
		)
	}

	private suspend fun validateSnapshot(
		snapshot: ActivationSnapshot,
		expectedRolloutRevision: Long,
	) {
		if (snapshot.rollout.revision != expectedRolloutRevision) {
			blocked(StepsWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED)
		}
		state.requireContainedSteps(snapshot.rollout)
		state.requireRunBoundary()
		if (snapshot.owner.owner != StepsWriterDestination.LEGACY_OWNER) {
			blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
		}
		val lane = snapshot.lane ?: blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_MISSING)
		if (!lane.matches(snapshot.binding, ProductProjectionStage.EVENT_SHADOW, snapshot.rollout.revision)) {
			blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		}
		val registration = state.database.sourceProjectionStateDao().registration(
			snapshot.binding.projectionId,
			snapshot.binding.projectionVersion,
		)
		if (registration != null) {
			blocked(StepsWriterTransitionBlocker.GLOBAL_WRITER_COLLISION)
		}
		val durableHighWater = state.database.liveSourceProjectionActivationOrdinal() - 1L
		if (lane.contiguousAdmissionOrdinal != durableHighWater) {
			blocked(StepsWriterTransitionBlocker.SHADOW_CURSOR_BEHIND)
		}
	}

	private suspend fun promoteLane(
		lane: SourceProductProjectionLaneEntity?,
		canonicalRevision: Long,
		updatedAtMs: Long,
	) {
		val shadow = lane ?: blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_MISSING)
		val updated = state.database.sourceProjectionStateDao().promoteExactProductLaneToCanonical(
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
		)
		if (updated != 1) {
			blocked(StepsWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
		}
		hooks.checkpoint(StepsWriterTransitionCheckpoint.AFTER_LANE_PROMOTION)
	}

	private suspend fun promoteOwner(
		owner: SourceDestinationOwnerEntity,
		candidateGeneration: Long,
		updatedAtMs: Long,
	) {
		val updated = state.database.sourceDestinationOwnerDao().compareAndSetOwner(
			sourceKind = StepsWriterDestination.SOURCE,
			destination = StepsWriterDestination.DESTINATION,
			expectedOwner = StepsWriterDestination.LEGACY_OWNER,
			expectedOwnerGeneration = owner.ownerGeneration,
			newOwner = StepsWriterDestination.CANDIDATE_OWNER,
			newOwnerGeneration = candidateGeneration,
			updatedAtMs = updatedAtMs,
		)
		if (updated != 1) {
			blocked(StepsWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
		}
		hooks.checkpoint(StepsWriterTransitionCheckpoint.AFTER_OWNER_PROMOTION)
	}

	private fun canonicalRollout(
		current: TrackingRolloutState,
		binding: ExecutableSourceLaneBinding,
		canonicalRevision: Long,
	): TrackingRolloutState = current.copy(
		revision = canonicalRevision,
		sourceOwners = current.sourceOwners + (SourceKind.STEPS to SourceOwner.EVENT),
		productProjectionStages = current.productProjectionStages +
			(SourceKind.STEPS to ProductProjectionStage.EVENT_CANONICAL),
		captureModeMasks = current.captureModeMasks + (SourceKind.STEPS to binding.captureModeMask),
	)

	private suspend fun verifyPostcondition(
		binding: ExecutableSourceLaneBinding,
		rollout: TrackingRolloutState,
		candidateGeneration: Long,
	) {
		val lane = state.exactActiveLane()
		if (lane?.isExactCanonicalStepsLane(binding) != true) {
			blocked(StepsWriterTransitionBlocker.POSTCONDITION_FAILED)
		}
		if (!lane.isCanonicalCaptureAuthorizedBy(rollout, state.executableLaneCatalog)) {
			blocked(StepsWriterTransitionBlocker.POSTCONDITION_FAILED)
		}
		val ownerMatches = state.database.sourceDestinationOwnerDao().isExactOwner(
			StepsWriterDestination.SOURCE,
			StepsWriterDestination.DESTINATION,
			StepsWriterDestination.CANDIDATE_OWNER,
			candidateGeneration,
		)
		if (!ownerMatches) {
			blocked(StepsWriterTransitionBlocker.POSTCONDITION_FAILED)
		}
	}
}

private data class ActivationSnapshot(
	val binding: ExecutableSourceLaneBinding,
	val rollout: TrackingRolloutState,
	val owner: SourceDestinationOwnerEntity,
	val lane: SourceProductProjectionLaneEntity?,
)
