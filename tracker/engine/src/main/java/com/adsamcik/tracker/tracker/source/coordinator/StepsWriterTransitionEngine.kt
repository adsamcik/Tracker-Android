package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.AppDatabase
import javax.inject.Inject
import javax.inject.Provider

internal class StepsWriterTransitionEngine @Inject constructor(
	private val boundary: StepsWriterTransitionBoundary,
	private val activation: StepsWriterActivationTransition,
	private val rollback: StepsWriterRollbackTransition,
	private val deletionRearm: StepsWriterDeletionRearm,
	private val hooks: StepsWriterTransitionRuntimeHooks,
) {
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
