package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject

internal class StepsWriterTransitionState @Inject constructor(
	val database: AppDatabase,
	val executableLaneCatalog: ExecutableSourceLaneCatalog,
) {
	suspend fun binding(): ExecutableSourceLaneBinding {
		val latestLane = exactActiveLane()
			?: database.sourceProjectionStateDao().latestProductLane(StepsWriterDestination.SOURCE)
		val binding = if (latestLane == null) {
			ExecutableSourceLaneCatalog.PREFERRED_STEPS_SESSION_FACTS
		} else {
			executableLaneCatalog.bindingFor(latestLane)
				?: blocked(StepsWriterTransitionBlocker.BINDING_NOT_EXECUTABLE)
		}
		if (!executableLaneCatalog.owns(binding)) {
			blocked(StepsWriterTransitionBlocker.BINDING_NOT_EXECUTABLE)
		}
		return binding
	}

	suspend fun exactActiveLane(): SourceProductProjectionLaneEntity? {
		val lanes = database.sourceProjectionStateDao().allActiveProductLanes()
			.filter { lane -> lane.sourceKind == StepsWriterDestination.SOURCE }
		if (lanes.size > 1) {
			blocked(StepsWriterTransitionBlocker.MULTIPLE_ACTIVE_LANES)
		}
		return lanes.singleOrNull()
	}

	suspend fun requireRunBoundary() {
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
			!database.isSourceCaptureAdmissionFenced(StepsWriterDestination.SOURCE) ->
				blocked(StepsWriterTransitionBlocker.CAPTURE_CALLBACK_BARRIER_OPEN)
		}
	}

	fun requireContainedSteps(rollout: TrackingRolloutState) {
		if (!rollout.stepsAreContained()) {
			blocked(StepsWriterTransitionBlocker.ROLLOUT_NOT_CONTAINED)
		}
	}
}

internal fun TrackingRolloutState.stepsAreContained(): Boolean =
	sourceOwners.getValue(SourceKind.STEPS) == SourceOwner.CONTAINED &&
		productProjectionStages.getValue(SourceKind.STEPS) == ProductProjectionStage.LEGACY_CANONICAL &&
		captureModeMasks.getValue(SourceKind.STEPS) == 0L

internal fun SourceProductProjectionLaneEntity.isExactCanonicalStepsLane(
	binding: ExecutableSourceLaneBinding,
): Boolean = matches(
	binding,
	ProductProjectionStage.EVENT_CANONICAL,
	Long.MAX_VALUE,
) && productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL
