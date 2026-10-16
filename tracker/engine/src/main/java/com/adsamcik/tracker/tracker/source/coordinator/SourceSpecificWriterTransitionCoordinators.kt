package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterCanonicalActivation
import com.adsamcik.tracker.shared.base.database.data.SourceWriterCompletedFullDeletion
import com.adsamcik.tracker.shared.base.database.data.SourceWriterDestinationOwner
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationBinding
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.SourceWriterRearmAuthorityInput
import com.adsamcik.tracker.shared.base.database.liveSourceProjectionActivationOrdinal
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Result of an exact source-writer handoff, including the phase and any unresolved ownership barrier.
 */
sealed interface SourceWriterTransitionResult {
	val source: SourceKind
	val phase: SourceWriterTransitionPhase

	/**
	 * The requested writer phase committed its rollout, owner generation and applicable admission cutoff.
	 */
	data class Applied(
		override val source: SourceKind,
		override val phase: SourceWriterTransitionPhase,
		val rolloutRevision: Long,
		val ownerGeneration: Long?,
		val cutoffAdmissionOrdinal: Long?,
	) : SourceWriterTransitionResult

	/**
	 * Durable state already satisfies this exact transition; retry does not advance ownership again.
	 */
	data class AlreadyApplied(
		override val source: SourceKind,
		override val phase: SourceWriterTransitionPhase,
		val rolloutRevision: Long,
		val ownerGeneration: Long?,
		val cutoffAdmissionOrdinal: Long?,
	) : SourceWriterTransitionResult

	/** A required source, lifecycle, cursor or destination invariant prevented the transition. */
	data class Blocked(
		override val source: SourceKind,
		override val phase: SourceWriterTransitionPhase,
		val blocker: SourceWriterTransitionBlocker,
	) : SourceWriterTransitionResult

	/** Storage/lifecycle startup could not provide authority for this writer transition. */
	data class StartupUnavailable(
		override val source: SourceKind,
		override val phase: SourceWriterTransitionPhase,
	) : SourceWriterTransitionResult

	/** Another coordinator still holds the source-writer transition lease. */
	data class Busy(
		override val source: SourceKind,
		override val phase: SourceWriterTransitionPhase,
	) : SourceWriterTransitionResult
}

/** Install, activate, rollback and full-clear rearm phases of one canonical source writer. */
enum class SourceWriterTransitionPhase {
	INSTALL_INERT_CANDIDATE,
	ACTIVATE_CANDIDATE,
	BEGIN_CANDIDATE_ROLLBACK,
	COMPLETE_CANDIDATE_ROLLBACK,
	REARM_AFTER_FULL_DELETION,
}

/** Concrete ownership, lifecycle, cursor or clear-evidence invariant that blocks writer handoff. */
enum class SourceWriterTransitionBlocker {
	ROLLOUT_MISSING_OR_UNREADABLE,
	ROLLOUT_REVISION_CHANGED,
	ROLLOUT_NOT_CONTAINED,
	LIFECYCLE_NOT_IDLE,
	SERVICE_RUN_NOT_FINALIZED,
	LIFECYCLE_ACTION_NOT_TERMINAL,
	CAPTURE_CALLBACK_BARRIER_OPEN,
	LEGACY_WRITER_NOT_QUIESCENT,
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
	REARM_BINDING_CONTRACT_UNAVAILABLE,
	FULL_DELETION_PROOF_UNAVAILABLE,
	POSTCONDITION_FAILED,
}

/**
 * Coordinates Pressure destination ownership and canonical projection activation under the shared
 * transition barriers.
 */
@Singleton
class PressureSessionFactWriterTransitionCoordinator @Inject internal constructor(
	factory: SourceWriterTransitionEngineFactory,
) {
	private val engine = factory.create(SourceWriterTransitionSpec.PRESSURE)

	internal constructor(
		database: AppDatabase,
		catalog: ExecutableSourceLaneCatalog,
		dependencies: SourceWriterTransitionTestDependencies,
	) : this(SourceWriterTransitionEngineFactory.forTest(database, catalog, dependencies))

	/**
	 * Installs the candidate projection under the expected rollout revision without enabling product
	 * writes; time is UTC milliseconds.
	 */
	suspend fun installInertCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.installInertCandidate(expectedRolloutRevision, updatedAtMs)

	/**
	 * Hands the destination to the candidate only after lifecycle, callback and cursor barriers are
	 * satisfied; time is UTC milliseconds.
	 */
	suspend fun activateCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.activateCandidate(expectedRolloutRevision, updatedAtMs)

	/**
	 * Contains the candidate and records its exact admission cutoff before returning ownership; time is
	 * UTC milliseconds.
	 */
	suspend fun beginCandidateRollback(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.beginCandidateRollback(expectedRolloutRevision, updatedAtMs)

	/**
	 * Completes rollback only for the expected contained rollout and drained cutoff; time is UTC
	 * milliseconds.
	 */
	suspend fun completeCandidateRollback(
		expectedContainedRolloutRevision: Long,
		expectedCutoffAdmissionOrdinal: Long,
		updatedAtMs: Long,
	) = engine.completeCandidateRollback(
		expectedContainedRolloutRevision,
		expectedCutoffAdmissionOrdinal,
		updatedAtMs,
	)

	/**
	 * Attempts canonical writer rearm only with completed full-clear authority; unknown owners remain
	 * preserved and time is UTC milliseconds.
	 */
	suspend fun rearmAfterFullDeletion(updatedAtMs: Long) = engine.rearmAfterFullDeletion(updatedAtMs)

	/**
	 * Attempts canonical writer rearm only with completed full-clear authority; unknown owners remain
	 * preserved and time is UTC milliseconds.
	 */
	suspend fun rearmAfterFullDeletion(
		completedFullDeletion: SourceWriterCompletedFullDeletion,
		updatedAtMs: Long,
	) = engine.rearmAfterFullDeletion(completedFullDeletion, updatedAtMs)
}

/**
 * Coordinates captured Activity writer ownership while leaving separate CONTROL evidence independent.
 */
@Singleton
class ActivityCapturedFactWriterTransitionCoordinator @Inject internal constructor(
	factory: SourceWriterTransitionEngineFactory,
) {
	private val engine = factory.create(SourceWriterTransitionSpec.ACTIVITY)

	internal constructor(
		database: AppDatabase,
		catalog: ExecutableSourceLaneCatalog,
		dependencies: SourceWriterTransitionTestDependencies,
	) : this(SourceWriterTransitionEngineFactory.forTest(database, catalog, dependencies))

	/**
	 * Installs the candidate projection under the expected rollout revision without enabling product
	 * writes; time is UTC milliseconds.
	 */
	suspend fun installInertCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.installInertCandidate(expectedRolloutRevision, updatedAtMs)

	/**
	 * Hands the destination to the candidate only after lifecycle, callback and cursor barriers are
	 * satisfied; time is UTC milliseconds.
	 */
	suspend fun activateCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.activateCandidate(expectedRolloutRevision, updatedAtMs)

	/**
	 * Contains the candidate and records its exact admission cutoff before returning ownership; time is
	 * UTC milliseconds.
	 */
	suspend fun beginCandidateRollback(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.beginCandidateRollback(expectedRolloutRevision, updatedAtMs)

	/**
	 * Completes rollback only for the expected contained rollout and drained cutoff; time is UTC
	 * milliseconds.
	 */
	suspend fun completeCandidateRollback(
		expectedContainedRolloutRevision: Long,
		expectedCutoffAdmissionOrdinal: Long,
		updatedAtMs: Long,
	) = engine.completeCandidateRollback(
		expectedContainedRolloutRevision,
		expectedCutoffAdmissionOrdinal,
		updatedAtMs,
	)

	/**
	 * Attempts canonical writer rearm only with completed full-clear authority; unknown owners remain
	 * preserved and time is UTC milliseconds.
	 */
	suspend fun rearmAfterFullDeletion(updatedAtMs: Long) = engine.rearmAfterFullDeletion(updatedAtMs)

	/**
	 * Attempts canonical writer rearm only with completed full-clear authority; unknown owners remain
	 * preserved and time is UTC milliseconds.
	 */
	suspend fun rearmAfterFullDeletion(
		completedFullDeletion: SourceWriterCompletedFullDeletion,
		updatedAtMs: Long,
	) = engine.rearmAfterFullDeletion(completedFullDeletion, updatedAtMs)
}

/**
 * Coordinates captured Wi-Fi writer ownership, lane cutoff and rollback under acknowledged lifecycle
 * barriers.
 */
@Singleton
class WifiSessionFactWriterTransitionCoordinator @Inject internal constructor(
	factory: SourceWriterTransitionEngineFactory,
) {
	private val engine = factory.create(SourceWriterTransitionSpec.WIFI)

	internal constructor(
		database: AppDatabase,
		catalog: ExecutableSourceLaneCatalog,
		dependencies: SourceWriterTransitionTestDependencies,
	) : this(SourceWriterTransitionEngineFactory.forTest(database, catalog, dependencies))

	/**
	 * Installs the candidate projection under the expected rollout revision without enabling product
	 * writes; time is UTC milliseconds.
	 */
	suspend fun installInertCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.installInertCandidate(expectedRolloutRevision, updatedAtMs)

	/**
	 * Hands the destination to the candidate only after lifecycle, callback and cursor barriers are
	 * satisfied; time is UTC milliseconds.
	 */
	suspend fun activateCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.activateCandidate(expectedRolloutRevision, updatedAtMs)

	/**
	 * Contains the candidate and records its exact admission cutoff before returning ownership; time is
	 * UTC milliseconds.
	 */
	suspend fun beginCandidateRollback(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.beginCandidateRollback(expectedRolloutRevision, updatedAtMs)

	/**
	 * Completes rollback only for the expected contained rollout and drained cutoff; time is UTC
	 * milliseconds.
	 */
	suspend fun completeCandidateRollback(
		expectedContainedRolloutRevision: Long,
		expectedCutoffAdmissionOrdinal: Long,
		updatedAtMs: Long,
	) = engine.completeCandidateRollback(
		expectedContainedRolloutRevision,
		expectedCutoffAdmissionOrdinal,
		updatedAtMs,
	)

	/**
	 * Attempts canonical writer rearm only with completed full-clear authority; unknown owners remain
	 * preserved and time is UTC milliseconds.
	 */
	suspend fun rearmAfterFullDeletion(updatedAtMs: Long) = engine.rearmAfterFullDeletion(updatedAtMs)

	/**
	 * Attempts canonical writer rearm only with completed full-clear authority; unknown owners remain
	 * preserved and time is UTC milliseconds.
	 */
	suspend fun rearmAfterFullDeletion(
		completedFullDeletion: SourceWriterCompletedFullDeletion,
		updatedAtMs: Long,
	) = engine.rearmAfterFullDeletion(completedFullDeletion, updatedAtMs)
}

/**
 * Coordinates captured Cell writer ownership, lane cutoff and rollback under acknowledged lifecycle
 * barriers.
 */
@Singleton
class CellSessionFactWriterTransitionCoordinator @Inject internal constructor(
	factory: SourceWriterTransitionEngineFactory,
) {
	private val engine = factory.create(SourceWriterTransitionSpec.CELL)

	internal constructor(
		database: AppDatabase,
		catalog: ExecutableSourceLaneCatalog,
		dependencies: SourceWriterTransitionTestDependencies,
	) : this(SourceWriterTransitionEngineFactory.forTest(database, catalog, dependencies))

	/**
	 * Installs the candidate projection under the expected rollout revision without enabling product
	 * writes; time is UTC milliseconds.
	 */
	suspend fun installInertCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.installInertCandidate(expectedRolloutRevision, updatedAtMs)

	/**
	 * Hands the destination to the candidate only after lifecycle, callback and cursor barriers are
	 * satisfied; time is UTC milliseconds.
	 */
	suspend fun activateCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.activateCandidate(expectedRolloutRevision, updatedAtMs)

	/**
	 * Contains the candidate and records its exact admission cutoff before returning ownership; time is
	 * UTC milliseconds.
	 */
	suspend fun beginCandidateRollback(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.beginCandidateRollback(expectedRolloutRevision, updatedAtMs)

	/**
	 * Completes rollback only for the expected contained rollout and drained cutoff; time is UTC
	 * milliseconds.
	 */
	suspend fun completeCandidateRollback(
		expectedContainedRolloutRevision: Long,
		expectedCutoffAdmissionOrdinal: Long,
		updatedAtMs: Long,
	) = engine.completeCandidateRollback(
		expectedContainedRolloutRevision,
		expectedCutoffAdmissionOrdinal,
		updatedAtMs,
	)

	/**
	 * Attempts canonical writer rearm only with completed full-clear authority; unknown owners remain
	 * preserved and time is UTC milliseconds.
	 */
	suspend fun rearmAfterFullDeletion(updatedAtMs: Long) = engine.rearmAfterFullDeletion(updatedAtMs)

	/**
	 * Attempts canonical writer rearm only with completed full-clear authority; unknown owners remain
	 * preserved and time is UTC milliseconds.
	 */
	suspend fun rearmAfterFullDeletion(
		completedFullDeletion: SourceWriterCompletedFullDeletion,
		updatedAtMs: Long,
	) = engine.rearmAfterFullDeletion(completedFullDeletion, updatedAtMs)
}

@Singleton
internal class SourceWriterTransitionEngineFactory private constructor(
	private val database: AppDatabase,
	private val catalog: ExecutableSourceLaneCatalog,
	private val boundary: SourceWriterTransitionBoundary,
	private val legacyWriterQuiescence: LegacySourceWriterTransitionBoundary,
	private val requestDrain: (SourceKind) -> Unit,
) {
	@Inject
	constructor(
		database: AppDatabase,
		catalog: ExecutableSourceLaneCatalog,
		boundary: SourceWriterTransitionBoundary,
		legacyWriterQuiescence: LegacySourceWriterTransitionBoundary,
		recoveryProvider: Provider<SourcePipelineRecovery>,
	) : this(
		database,
		catalog,
		boundary,
		legacyWriterQuiescence,
		{ source ->
			when (source) {
				SourceKind.ACTIVITY -> recoveryProvider.get().requestActivityCapturedFactDrain()
				SourceKind.PRESSURE -> recoveryProvider.get().requestPressureSessionFactDrain()
				SourceKind.WIFI -> recoveryProvider.get().requestWifiSessionFactDrain()
				SourceKind.CELL -> recoveryProvider.get().requestCellSessionFactDrain()
				else -> Unit
			}
		},
	)

	fun create(spec: SourceWriterTransitionSpec) = SourceWriterTransitionEngine(
		database,
		catalog,
		boundary,
		legacyWriterQuiescence,
		requestDrain,
		spec,
	)

	companion object {
		fun forTest(
			database: AppDatabase,
			catalog: ExecutableSourceLaneCatalog,
			dependencies: SourceWriterTransitionTestDependencies,
		) = SourceWriterTransitionEngineFactory(
			database,
			catalog,
			SourceWriterTransitionBoundary.forTest(database, dependencies),
			dependencies.legacyWriterQuiescence,
			dependencies.requestDrain,
		)
	}
}

// One existing writer engine keeps install, activation, rollback and clear rearm under the same spec, lease and Room invariants. Complete private phases avoid another authority owner; algorithm metrics remain active.
@Suppress("LargeClass", "TooManyFunctions")
internal class SourceWriterTransitionEngine(
	private val database: AppDatabase,
	private val catalog: ExecutableSourceLaneCatalog,
	private val boundary: SourceWriterTransitionBoundary,
	private val legacyWriterQuiescence: LegacySourceWriterTransitionBoundary,
	private val requestDrain: (SourceKind) -> Unit,
	private val spec: SourceWriterTransitionSpec,
) {
	/** A finite ordinary handoff; no unknown or later contract is upgraded implicitly. */
	internal suspend fun prepareFinalKnownWriter(updatedAtMs: Long): SourceWriterTransitionResult {
		require(updatedAtMs >= 0L)
		val initial = database.withTransaction {
			val rollout = currentRollout(); val lane = exactActiveLane(); val owner = currentOwner()
			val ready = if (isCanonicalState(rollout, lane, owner, spec.finalBinding) &&
				lane?.captureAdmissionCutoffOrdinal == null) {
				SourceWriterTransitionResult.AlreadyApplied(
					spec.source, SourceWriterTransitionPhase.ACTIVATE_CANDIDATE, rollout.revision,
                        owner?.ownerGeneration,
                        null)
			} else {
				null
			}
			ready to lane
		}
		initial.first?.let { return it }
		settleOriginalForFinalWriter(initial.second, updatedAtMs)?.let { result ->
			if (!result.isApplied()) {
				return result
			}
		}
		prepareFinalShadow(updatedAtMs)?.let { return it }
		return activate(currentRollout().revision, updatedAtMs, finalSetup = true)
	}

	private suspend fun settleOriginalForFinalWriter(
		original: SourceProductProjectionLaneEntity?,
		updatedAtMs: Long,
	): SourceWriterTransitionResult? {
		if (original == null || catalog.bindingFor(original) != spec.binding) {
			return null
		}
		return when (original.productStage) {
			SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL ->
				settleOriginalCanonicalForFinalWriter(original, updatedAtMs)
			SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW -> settleOriginalShadowForFinalWriter(updatedAtMs)
			else -> SourceWriterTransitionResult.Blocked(spec.source,
				SourceWriterTransitionPhase.INSTALL_INERT_CANDIDATE,
				SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		}
	}

	private suspend fun prepareFinalShadow(updatedAtMs: Long): SourceWriterTransitionResult? {
		val active = database.sourceProjectionStateDao().activeProductLane(spec.source.stableCode)
		if (active == null) {
			val installed = install(currentRollout().revision, updatedAtMs, spec.finalBinding, finalSetup = true)
			if (!installed.isApplied()) {
				return installed
			}
		} else if (catalog.bindingFor(active) != spec.finalBinding ||
			active.productStage != SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW) {
			return SourceWriterTransitionResult.Blocked(
				spec.source, SourceWriterTransitionPhase.INSTALL_INERT_CANDIDATE,
                    SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		}
		return null
	}

	private suspend fun settleOriginalCanonicalForFinalWriter(
		original: SourceProductProjectionLaneEntity,
		updatedAtMs: Long,
	): SourceWriterTransitionResult {
		if (original.captureAdmissionCutoffOrdinal == null) {
			val result = beginRollback(currentRollout().revision, updatedAtMs, finalSetup = true)
			if (!result.isApplied()) {
				return result
			}
		}
		val current = database.sourceProjectionStateDao().activeProductLane(spec.source.stableCode)
		val cutoff = current?.captureAdmissionCutoffOrdinal ?: return SourceWriterTransitionResult.Blocked(spec.source,
			SourceWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK, SourceWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
		if (current.contiguousAdmissionOrdinal != cutoff) {
			return SourceWriterTransitionResult.Blocked(spec.source,
			SourceWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK,
                SourceWriterTransitionBlocker.ROLLBACK_CURSOR_BEHIND)
		}
		return completeRollback(currentRollout().revision, cutoff, updatedAtMs, finalSetup = true)
	}

	private suspend fun settleOriginalShadowForFinalWriter(updatedAtMs: Long): SourceWriterTransitionResult {
		var needsDrain = false
		val result = boundary.run(spec.source, SourceWriterTransitionPhase.BEGIN_CANDIDATE_ROLLBACK) { lease ->
			database.withTransaction {
				boundary.requireLease(lease); requireFinalWriterSetupBoundary()
				var rollout = currentRollout(); var shadow = exactActiveLane() ?:
                    block(SourceWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
				if (catalog.bindingFor(shadow) != spec.binding ||
                    shadow.productStage != SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW)
					{
					block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
				}
				requireLegacyOwner(currentOwner())
				if (shadow.captureAdmissionCutoffOrdinal == null) {
					val contained = RoomTrackingRolloutStateStore(database,
                        catalog).beginOriginalWriterSetupContainment(
						spec.binding, nextRevision(rollout.revision), updatedAtMs)
					rollout = contained.rollout; shadow = contained.lane
				}
				val cutoff = requireNotNull(shadow.captureAdmissionCutoffOrdinal)
				if (shadow.contiguousAdmissionOrdinal != cutoff) {
					needsDrain = true
					return@withTransaction SourceWriterTransitionResult.Blocked(spec.source,
						SourceWriterTransitionPhase.BEGIN_CANDIDATE_ROLLBACK,
                            SourceWriterTransitionBlocker.SHADOW_CURSOR_BEHIND)
				}
				RoomTrackingRolloutStateStore(database, catalog).finishLaneRetirement(spec.binding, cutoff, updatedAtMs)
				SourceWriterTransitionResult.Applied(spec.source,
                    SourceWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK,
					rollout.revision, currentOwner()?.ownerGeneration, cutoff)
			}
		}
		if (needsDrain) {
			requestDrain(spec.source)
		}
		return result
	}

	suspend fun installInertCandidate(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
		requestedBinding: ExecutableSourceLaneBinding = spec.binding,
	): SourceWriterTransitionResult = install(expectedRolloutRevision, updatedAtMs, requestedBinding, finalSetup =
        false)

	private suspend fun install(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
		requestedBinding: ExecutableSourceLaneBinding,
		finalSetup: Boolean,
	): SourceWriterTransitionResult {
		require(expectedRolloutRevision >= 0L)
		require(updatedAtMs >= 0L)
		return boundary.run(
			spec.source,
			SourceWriterTransitionPhase.INSTALL_INERT_CANDIDATE,
		) { lease ->
			database.withTransaction {
				boundary.requireLease(lease)
				if ((requestedBinding != spec.binding && requestedBinding != spec.finalBinding) ||
					!catalog.ownsStaticBinding(requestedBinding)
				) {
					block(SourceWriterTransitionBlocker.BINDING_NOT_EXECUTABLE)
				}
				val rollout = currentRollout()
				val active = exactActiveLane()
				if (active?.matches(requestedBinding, ProductProjectionStage.EVENT_SHADOW, rollout.revision) == true &&
					active?.activatedRolloutRevision == rollout.revision &&
					rollout.revision == successfulRevisionAfter(expectedRolloutRevision)
				) {
					return@withTransaction SourceWriterTransitionResult.AlreadyApplied(
						spec.source,
						SourceWriterTransitionPhase.INSTALL_INERT_CANDIDATE,
						rollout.revision,
						currentOwner()?.ownerGeneration,
						null,
					)
				}
				requireExpectedRevision(rollout, expectedRolloutRevision)
				requireContained(rollout)
				requireSetupBoundary(requestedBinding, spec.finalBinding, finalSetup)
				if (active != null) {
					block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
				}
				val predecessor = requireInstallPredecessor(requestedBinding)
				requireInstallProjectionAvailable(requestedBinding)
				installShadowWriter(expectedRolloutRevision, requestedBinding, updatedAtMs, finalSetup, predecessor)
			}
		}
	}

	suspend fun activateCandidate(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
	): SourceWriterTransitionResult = activate(expectedRolloutRevision, updatedAtMs, finalSetup = false)

	private suspend fun installShadowWriter(
		expectedRolloutRevision: Long,
		requestedBinding: ExecutableSourceLaneBinding,
		updatedAtMs: Long,
		finalSetup: Boolean,
		predecessor: SourceProductProjectionLaneEntity?,
	): SourceWriterTransitionResult.Applied {
		val revision = nextRevision(expectedRolloutRevision)
		val store = RoomTrackingRolloutStateStore(database, catalog)
		val activation = if (finalSetup && spec.source in setOf(SourceKind.WIFI, SourceKind.CELL)) {
			store.installFinalRadioWriterShadowLane(requestedBinding, revision, updatedAtMs, rearm = predecessor != null)
		} else if (predecessor == null) {
			store.installInertShadowLane(requestedBinding, revision, updatedAtMs)
		} else {
			store.rearmInertShadowLane(requestedBinding, revision, updatedAtMs)
		}
		return SourceWriterTransitionResult.Applied(
			spec.source,
			SourceWriterTransitionPhase.INSTALL_INERT_CANDIDATE,
			activation.rollout.revision,
			currentOwner()?.ownerGeneration,
			null,
		)
	}

	private suspend fun requireInstallPredecessor(
		requestedBinding: ExecutableSourceLaneBinding,
	): SourceProductProjectionLaneEntity? {
		val predecessor = database.sourceProjectionStateDao().latestProductLane(spec.source.stableCode)
		val owner = currentOwner()
		if (predecessor == null && requestedBinding == spec.finalBinding &&
			owner.isExactCandidate(spec, requestedBinding.bindingGeneration) &&
			!hasCompletedClearFinalSetup(owner, requestedBinding)
		) {
			block(SourceWriterTransitionBlocker.FULL_DELETION_PROOF_UNAVAILABLE)
		}
		if (predecessor != null) {
			if (requestedBinding != spec.finalBinding) {
				block(SourceWriterTransitionBlocker.REARM_BINDING_CONTRACT_UNAVAILABLE)
			}
			requireSettledOriginalPredecessor(predecessor, currentOwner())
		}
		return predecessor
	}

	private suspend fun requireInstallProjectionAvailable(requestedBinding: ExecutableSourceLaneBinding) {
		if (database.sourceProjectionStateDao().registration(
				requestedBinding.projectionId,
				requestedBinding.projectionVersion,
			) != null
		) {
			block(SourceWriterTransitionBlocker.GLOBAL_WRITER_COLLISION)
		}
		if (database.sourceProjectionStateDao().productLanesByProjection(
				requestedBinding.projectionId,
				requestedBinding.projectionVersion,
			).any { lane -> lane.sourceKind != spec.source.stableCode }
		) {
			block(SourceWriterTransitionBlocker.GLOBAL_WRITER_COLLISION)
		}
	}

	private suspend fun activate(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
		finalSetup: Boolean,
	): SourceWriterTransitionResult {
		require(expectedRolloutRevision >= 0L)
		require(updatedAtMs >= 0L)
		val activation: suspend () -> SourceWriterTransitionResult = {
			boundary.run(
				spec.source,
				SourceWriterTransitionPhase.ACTIVATE_CANDIDATE,
			) { lease ->
				database.withTransaction {
					applyCanonicalActivation(expectedRolloutRevision, updatedAtMs, finalSetup, lease)
				}
			}
		}
		return if (spec.requiresLegacyWriterQuiescence) {
			legacyWriterQuiescence.runIfQuiescent(spec.source, activation)
				?: SourceWriterTransitionResult.Blocked(
					spec.source,
					SourceWriterTransitionPhase.ACTIVATE_CANDIDATE,
					SourceWriterTransitionBlocker.LEGACY_WRITER_NOT_QUIESCENT,
				)
		} else {
			activation()
		}
	}

	private suspend fun applyCanonicalActivation(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
		finalSetup: Boolean,
		lease: LifecycleLeaseToken,
	): SourceWriterTransitionResult {
		boundary.requireLease(lease)
		val rollout = currentRollout()
		val lane = exactActiveLane()
		val binding = executableBinding(lane)
		val owner = currentOwner()
		if (isCanonicalState(rollout, lane, owner, binding) &&
			lane?.matchesExactCanonicalActivation(
				spec = spec,
				binding = binding,
				owner = owner,
				expectedActivationRevision = rollout.revision,
			) == true &&
			rollout.revision == successfulRevisionAfter(expectedRolloutRevision)
		) {
			return SourceWriterTransitionResult.AlreadyApplied(
				spec.source,
				SourceWriterTransitionPhase.ACTIVATE_CANDIDATE,
				rollout.revision,
				owner?.ownerGeneration,
				null,
			)
		}
		requireExpectedRevision(rollout, expectedRolloutRevision)
		requireContained(rollout)
		requireSetupBoundary(binding, spec.finalBinding, finalSetup)
		requireInitialOwner(owner, binding)
		val shadow = lane ?: block(SourceWriterTransitionBlocker.ACTIVE_LANE_MISSING)
		if (!shadow.matches(binding, ProductProjectionStage.EVENT_SHADOW, rollout.revision)) {
			block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		}
		if (database.sourceProjectionStateDao().registration(
				binding.projectionId,
				binding.projectionVersion,
			) != null
		) {
			block(SourceWriterTransitionBlocker.GLOBAL_WRITER_COLLISION)
		}
		return commitCanonicalActivation(rollout, binding, owner, shadow, updatedAtMs)
	}

	suspend fun beginCandidateRollback(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
	): SourceWriterTransitionResult = beginRollback(expectedRolloutRevision, updatedAtMs, finalSetup = false)

	private suspend fun commitCanonicalActivation(
		rollout: TrackingRolloutState,
		binding: ExecutableSourceLaneBinding,
		owner: SourceDestinationOwnerEntity?,
		shadow: SourceProductProjectionLaneEntity,
		updatedAtMs: Long,
	): SourceWriterTransitionResult.Applied {
		val highWater = database.liveSourceProjectionActivationOrdinal() - 1L
		if (shadow.contiguousAdmissionOrdinal != highWater) {
			block(SourceWriterTransitionBlocker.SHADOW_CURSOR_BEHIND)
		}
		val canonicalRevision = nextRevision(rollout.revision)
		promoteLane(shadow, canonicalRevision, updatedAtMs)
		promoteOwner(owner, binding, updatedAtMs)
		val canonical = rollout.copy(
			revision = canonicalRevision,
			sourceOwners = rollout.sourceOwners + (spec.source to SourceOwner.EVENT),
			productProjectionStages = rollout.productProjectionStages +
				(spec.source to ProductProjectionStage.EVENT_CANONICAL),
			captureModeMasks = rollout.captureModeMasks +
				(spec.source to binding.captureModeMask),
		)
		database.trackingRolloutStateDao().save(canonical.toEntity(updatedAtMs))
		verifyCanonicalPostcondition(canonical, binding)
		return SourceWriterTransitionResult.Applied(
			spec.source,
			SourceWriterTransitionPhase.ACTIVATE_CANDIDATE,
			canonicalRevision,
			SourceWriterGenerationContract.canonicalOwnerGeneration(
				binding.bindingGeneration,
			),
			null,
		)
	}

	private suspend fun beginRollback(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
		finalSetup: Boolean,
	): SourceWriterTransitionResult {
		require(expectedRolloutRevision >= 0L)
		require(updatedAtMs >= 0L)
		val result = boundary.run(
			spec.source,
			SourceWriterTransitionPhase.BEGIN_CANDIDATE_ROLLBACK,
		) { lease ->
			database.withTransaction {
				boundary.requireLease(lease)
				val rollout = currentRollout()
				val lane = exactActiveLane()
				val binding = executableBinding(lane)
				val owner = currentOwner()
				val cutoff = lane?.captureAdmissionCutoffOrdinal
				if (cutoff != null && rollout.revision == successfulRevisionAfter(expectedRolloutRevision) &&
					rollout.isSourceContained(spec.source) &&
					lane?.matchesExactCanonicalActivation(
						spec = spec,
						binding = binding,
						owner = owner,
						expectedActivationRevision = expectedRolloutRevision,
					) == true
				) {
					return@withTransaction SourceWriterTransitionResult.AlreadyApplied(
						spec.source,
						SourceWriterTransitionPhase.BEGIN_CANDIDATE_ROLLBACK,
						rollout.revision,
						owner?.ownerGeneration,
						cutoff,
					)
				}
				requireExpectedRevision(rollout, expectedRolloutRevision)
				requireSetupBoundary(binding, spec.binding, finalSetup)
				if (!isCanonicalState(rollout, lane, owner, binding)) {
					block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
				}
				commitRollbackContainment(rollout, lane, owner, updatedAtMs)
			}
		}
		if (result.isApplied()) {
			requestDrain(spec.source)
		}
		return result
	}

	suspend fun completeCandidateRollback(
		expectedContainedRolloutRevision: Long,
		expectedCutoffAdmissionOrdinal: Long,
		updatedAtMs: Long,
	): SourceWriterTransitionResult = completeRollback(expectedContainedRolloutRevision,
		expectedCutoffAdmissionOrdinal, updatedAtMs, finalSetup = false)

	private suspend fun commitRollbackContainment(
		rollout: TrackingRolloutState,
		lane: SourceProductProjectionLaneEntity?,
		owner: SourceDestinationOwnerEntity?,
		updatedAtMs: Long,
	): SourceWriterTransitionResult.Applied {
		val canonical = requireNotNull(lane)
		val containedRevision = nextRevision(rollout.revision)
		val cutoffOrdinal = database.liveSourceProjectionActivationOrdinal() - 1L
		val contained = rollout.copy(
			revision = containedRevision,
			sourceOwners = rollout.sourceOwners + (spec.source to SourceOwner.CONTAINED),
			productProjectionStages = rollout.productProjectionStages +
				(spec.source to ProductProjectionStage.LEGACY_CANONICAL),
			captureModeMasks = rollout.captureModeMasks + (spec.source to 0L),
		)
		database.trackingRolloutStateDao().save(contained.toEntity(updatedAtMs))
		if (database.sourceProjectionStateDao().fenceProductLaneCaptureAdmission(
				sourceKind = canonical.sourceKind,
				bindingGeneration = canonical.bindingGeneration,
				projectionId = canonical.projectionId,
				projectionVersion = canonical.projectionVersion,
				cutoffOrdinal = cutoffOrdinal,
				updatedAtMs = updatedAtMs,
			) != 1
		) {
			block(SourceWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
		}
		return SourceWriterTransitionResult.Applied(
			spec.source,
			SourceWriterTransitionPhase.BEGIN_CANDIDATE_ROLLBACK,
			containedRevision,
			owner?.ownerGeneration,
			cutoffOrdinal,
		)
	}

	private suspend fun completeRollback(
		expectedContainedRolloutRevision: Long,
		expectedCutoffAdmissionOrdinal: Long,
		updatedAtMs: Long,
		finalSetup: Boolean,
	): SourceWriterTransitionResult {
		require(expectedContainedRolloutRevision >= 0L)
		require(expectedCutoffAdmissionOrdinal >= 0L)
		require(updatedAtMs >= 0L)
		return boundary.run(
			spec.source,
			SourceWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK,
		) { lease ->
			database.withTransaction {
				boundary.requireLease(lease)
				val rollout = currentRollout()
				val lane = exactActiveLane()
				val binding = executableBinding(lane)
				val owner = currentOwner()
				val retired = database.sourceProjectionStateDao().productLane(
					spec.source.stableCode,
					binding.bindingGeneration,
					binding.projectionId,
					binding.projectionVersion,
				)
				// Complete saved rollout/lane/owner/retirement correspondence makes an exact retry idempotent.
				@Suppress("ComplexCondition")
				if (rollout.revision == expectedContainedRolloutRevision &&
					lane == null &&
					owner?.owner == spec.containedOwner &&
					owner.ownerGeneration ==
						SourceWriterGenerationContract.containedOwnerGeneration(
							binding.bindingGeneration,
						) &&
					retired?.isExactRetiredCanonical(
						spec = spec,
						binding = binding,
						expectedActivationRevision =
							previousRevision(expectedContainedRolloutRevision),
						expectedCutoffAdmissionOrdinal = expectedCutoffAdmissionOrdinal,
					) == true
				) {
					return@withTransaction SourceWriterTransitionResult.AlreadyApplied(
						spec.source,
						SourceWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK,
						rollout.revision,
						owner.ownerGeneration,
						expectedCutoffAdmissionOrdinal,
					)
				}
				requireExpectedRevision(rollout, expectedContainedRolloutRevision)
				requireContained(rollout)
				requireSetupBoundary(binding, spec.binding, finalSetup)
				val draining = requireRollbackDrain(lane, binding, owner, expectedCutoffAdmissionOrdinal)
				commitRollbackRetirement(rollout, draining, owner, expectedCutoffAdmissionOrdinal, updatedAtMs)
			}
		}
	}

	private fun requireRollbackDrain(
		lane: SourceProductProjectionLaneEntity?,
		binding: ExecutableSourceLaneBinding,
		owner: SourceDestinationOwnerEntity?,
		expectedCutoffAdmissionOrdinal: Long,
	): SourceProductProjectionLaneEntity {
		if (!owner.isExactCandidate(spec, binding.bindingGeneration)) {
			block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
		}
		val draining = lane ?: block(SourceWriterTransitionBlocker.ACTIVE_LANE_MISSING)
		if (!draining.matchesSourceBinding(binding) ||
			draining.productStage != SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL ||
			draining.captureAdmissionCutoffOrdinal != expectedCutoffAdmissionOrdinal
		) {
			block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		}
		if (draining.contiguousAdmissionOrdinal != expectedCutoffAdmissionOrdinal) {
			block(SourceWriterTransitionBlocker.ROLLBACK_CURSOR_BEHIND)
		}
		return draining
	}

	private suspend fun commitRollbackRetirement(
		rollout: TrackingRolloutState,
		draining: SourceProductProjectionLaneEntity,
		owner: SourceDestinationOwnerEntity?,
		expectedCutoffAdmissionOrdinal: Long,
		updatedAtMs: Long,
	): SourceWriterTransitionResult.Applied {
		if (database.sourceProjectionStateDao().retireFencedProductLane(
				sourceKind = draining.sourceKind,
				bindingGeneration = draining.bindingGeneration,
				projectionId = draining.projectionId,
				projectionVersion = draining.projectionVersion,
				expectedCurrentOrdinal = expectedCutoffAdmissionOrdinal,
				expectedCutoffOrdinal = expectedCutoffAdmissionOrdinal,
				disposition = SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
				terminalAtMs = updatedAtMs,
			) != 1
		) {
			block(SourceWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
		}
		val candidate = requireNotNull(owner)
		val containedOwnerGeneration =
			SourceWriterGenerationContract.containedOwnerGeneration(
				draining.bindingGeneration,
			)
		if (database.sourceDestinationOwnerDao().compareAndSetOwner(
				sourceKind = spec.source.stableCode,
				destination = spec.destination,
				expectedOwner = spec.candidateOwner,
				expectedOwnerGeneration = candidate.ownerGeneration,
				newOwner = spec.containedOwner,
				newOwnerGeneration = containedOwnerGeneration,
				updatedAtMs = updatedAtMs,
			) != 1
		) {
			block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
		}
		return SourceWriterTransitionResult.Applied(
			spec.source,
			SourceWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK,
			rollout.revision,
			containedOwnerGeneration,
			expectedCutoffAdmissionOrdinal,
		)
	}

	suspend fun rearmAfterFullDeletion(updatedAtMs: Long): SourceWriterTransitionResult {
		return rearmAfterFullDeletionInternal(completedFullDeletion = null, updatedAtMs = updatedAtMs)
	}

	suspend fun rearmAfterFullDeletion(
		completedFullDeletion: SourceWriterCompletedFullDeletion,
		updatedAtMs: Long,
	): SourceWriterTransitionResult =
		rearmAfterFullDeletionInternal(
			completedFullDeletion = completedFullDeletion,
			updatedAtMs = updatedAtMs,
		)

	private suspend fun rearmAfterFullDeletionInternal(
		completedFullDeletion: SourceWriterCompletedFullDeletion?,
		updatedAtMs: Long,
	): SourceWriterTransitionResult {
		require(updatedAtMs >= 0L)
		return boundary.run(
			spec.source,
			SourceWriterTransitionPhase.REARM_AFTER_FULL_DELETION,
		) { lease ->
			val snapshot = database.withTransaction {
				boundary.requireLease(lease)
				readRearmSnapshot(completedFullDeletion)
			}
			snapshot.rearmBinding.declaration.runIfFullySupportedAndAuthorized(
				snapshot.authorityInput,
			) { authorizedNextBinding ->
				if (authorizedNextBinding != snapshot.rearmBinding.nextContractBinding) {
					block(SourceWriterTransitionBlocker.REARM_BINDING_CONTRACT_UNAVAILABLE)
				}
				val activation = database.withTransaction {
					boundary.requireLease(lease)
					commitRearmSnapshot(snapshot, updatedAtMs)
				}
				SourceWriterTransitionResult.Applied(
					source = spec.source,
					phase = SourceWriterTransitionPhase.REARM_AFTER_FULL_DELETION,
					rolloutRevision = activation.rollout.revision,
					ownerGeneration = snapshot.containedOwnerGeneration,
					cutoffAdmissionOrdinal = null,
				)
			} ?: SourceWriterTransitionResult.Blocked(
				spec.source,
				SourceWriterTransitionPhase.REARM_AFTER_FULL_DELETION,
				SourceWriterTransitionBlocker.FULL_DELETION_PROOF_UNAVAILABLE,
			)
		}
	}

	private suspend fun readRearmSnapshot(
		completedFullDeletion: SourceWriterCompletedFullDeletion?,
	): RearmSnapshot {
		val rollout = currentRollout()
		requireContained(rollout)
		requireRunBoundary()
		if (exactActiveLane() != null) {
			block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		}
		val retired = readRetiredRearmLane()
		val currentBinding = catalog.bindingFor(retired)
			?: block(SourceWriterTransitionBlocker.BINDING_NOT_EXECUTABLE)
		val owner = currentOwner()
		if (owner?.owner != spec.containedOwner ||
			owner.ownerGeneration !=
			SourceWriterGenerationContract.containedOwnerGeneration(
				currentBinding.bindingGeneration,
			)
		) {
			block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
		}
		val deletion = completedFullDeletion
			?: block(SourceWriterTransitionBlocker.FULL_DELETION_PROOF_UNAVAILABLE)
		val authorityInput = rearmAuthorityInput(retired, currentBinding, owner, deletion)
		val rearmBinding = catalog.nextRearmBinding(
			current = currentBinding,
			input = authorityInput,
			destination = spec.destination,
			candidateOwner = spec.candidateOwner,
			containedOwner = spec.containedOwner,
		) ?: block(SourceWriterTransitionBlocker.REARM_BINDING_CONTRACT_UNAVAILABLE)
		return RearmSnapshot(
			rollout = rollout,
			currentBinding = currentBinding,
			rearmBinding = rearmBinding,
			authorityInput = authorityInput,
			containedOwnerGeneration = owner.ownerGeneration,
			retiredLane = retired,
		)
	}

	private fun rearmAuthorityInput(
		retired: SourceProductProjectionLaneEntity,
		currentBinding: ExecutableSourceLaneBinding,
		owner: SourceDestinationOwnerEntity,
		deletion: SourceWriterCompletedFullDeletion,
	): SourceWriterRearmAuthorityInput = SourceWriterRearmAuthorityInput(
		retiredCanonicalActivation = retired.canonicalActivation(
			spec = spec,
			writerOwner = spec.candidateOwner,
			writerOwnerGeneration =
				SourceWriterGenerationContract.canonicalOwnerGeneration(
					currentBinding.bindingGeneration,
				),
		),
		containedDestinationOwner = SourceWriterDestinationOwner(
			sourceKind = owner.sourceKind,
			destination = owner.destination,
			owner = owner.owner,
			ownerGeneration = owner.ownerGeneration,
		),
		completedFullDeletion = deletion,
	)

	private suspend fun readRetiredRearmLane(): SourceProductProjectionLaneEntity {
		val retired = database.sourceProjectionStateDao()
			.latestProductLane(spec.source.stableCode)
			?: block(SourceWriterTransitionBlocker.ACTIVE_LANE_MISSING)
		// Complete immutable canonical retirement/cutoff correspondence before binding resolution.
		@Suppress("ComplexCondition")
		if (retired.status != SourceProductProjectionLaneEntity.STATUS_RETIRED ||
			retired.productStage != SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL ||
			retired.activatedRolloutRevision <= 0L ||
			retired.captureAdmissionCutoffOrdinal == null ||
			retired.contiguousAdmissionOrdinal != retired.captureAdmissionCutoffOrdinal ||
			retired.terminalDisposition !=
			SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN
		) {
			block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		}
		return retired
	}

	private suspend fun commitRearmSnapshot(
		snapshot: RearmSnapshot,
		updatedAtMs: Long,
	): SourceProductLaneActivation {
		val current = currentRollout()
		if (current != snapshot.rollout || exactActiveLane() != null) {
			block(SourceWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED)
		}
		val owner = currentOwner()
		if (owner?.owner != spec.containedOwner ||
			owner.ownerGeneration != snapshot.containedOwnerGeneration
		) {
			block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
		}
		val retired = database.sourceProjectionStateDao()
			.latestProductLane(spec.source.stableCode)
		if (retired == null ||
			retired != snapshot.retiredLane ||
			catalog.bindingFor(retired) != snapshot.currentBinding
		) {
			block(SourceWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
		}
		val nextRevision = nextRevision(snapshot.rollout.revision)
		return RoomTrackingRolloutStateStore(database, catalog)
			.rearmInertShadowLane(snapshot.rearmBinding, nextRevision, updatedAtMs)
	}

	private data class RearmSnapshot(
		val rollout: TrackingRolloutState,
		val currentBinding: ExecutableSourceLaneBinding,
		val rearmBinding: ExecutableSourceRearmBinding,
		val authorityInput: SourceWriterRearmAuthorityInput,
		val containedOwnerGeneration: Long,
		val retiredLane: SourceProductProjectionLaneEntity,
	)

	private suspend fun currentRollout(): TrackingRolloutState =
		database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull()
			?: block(SourceWriterTransitionBlocker.ROLLOUT_MISSING_OR_UNREADABLE)

	private suspend fun exactActiveLane(): SourceProductProjectionLaneEntity? {
		val lanes = database.sourceProjectionStateDao().allActiveProductLanes()
			.filter { lane -> lane.sourceKind == spec.source.stableCode }
		if (lanes.size > 1) {
			block(SourceWriterTransitionBlocker.MULTIPLE_ACTIVE_LANES)
		}
		return lanes.singleOrNull()
	}

	private suspend fun currentOwner(): SourceDestinationOwnerEntity? =
		database.sourceDestinationOwnerDao().get(spec.source.stableCode, spec.destination)

	private suspend fun executableBinding(
		lane: SourceProductProjectionLaneEntity?,
	): ExecutableSourceLaneBinding {
		val candidate = lane ?: database.sourceProjectionStateDao()
			.latestProductLane(spec.source.stableCode)
			?: block(SourceWriterTransitionBlocker.ACTIVE_LANE_MISSING)
		return catalog.bindingFor(candidate)
			?: block(SourceWriterTransitionBlocker.BINDING_NOT_EXECUTABLE)
	}

	private suspend fun requireSetupBoundary(
		binding: ExecutableSourceLaneBinding,
		expectedSetupBinding: ExecutableSourceLaneBinding,
		finalSetup: Boolean,
	) {
		if (finalSetup) {
			if (binding != expectedSetupBinding) {
				block(SourceWriterTransitionBlocker.BINDING_NOT_EXECUTABLE)
			}
			requireFinalWriterSetupBoundary()
		} else {
			requireRunBoundary()
		}
	}

	/** Exact known setup keeps independently allowed all-day radio work alive. */
	private suspend fun requireFinalWriterSetupBoundary() {
		val sessions = database.sourceSessionDao()
		when {
			sessions.hasLifecycleBoundaryBlocker() -> block(SourceWriterTransitionBlocker.LIFECYCLE_NOT_IDLE)
			sessions.hasIncompleteServiceRun() -> block(SourceWriterTransitionBlocker.SERVICE_RUN_NOT_FINALIZED)
			sessions.hasNonterminalLatestLifecycleAction() ->
                block(SourceWriterTransitionBlocker.LIFECYCLE_ACTION_NOT_TERMINAL)
			!(if (spec.source == SourceKind.WIFI || spec.source == SourceKind.CELL)
				{
				database.isSharedRadioSessionCaptureAdmissionFenced(spec.source.stableCode)
			}
				else {
				database.isSourceCaptureAdmissionFenced(spec.source.stableCode)
			}) ->
				block(SourceWriterTransitionBlocker.CAPTURE_CALLBACK_BARRIER_OPEN)
		}
	}

	private suspend fun requireRunBoundary() {
		val sessionDao = database.sourceSessionDao()
		when {
			sessionDao.hasLifecycleBoundaryBlocker() ->
				block(SourceWriterTransitionBlocker.LIFECYCLE_NOT_IDLE)
			sessionDao.hasIncompleteServiceRun() ->
				block(SourceWriterTransitionBlocker.SERVICE_RUN_NOT_FINALIZED)
			sessionDao.hasNonterminalLatestLifecycleAction() ->
				block(SourceWriterTransitionBlocker.LIFECYCLE_ACTION_NOT_TERMINAL)
			!database.isSourceCaptureAdmissionFenced(spec.source.stableCode) ->
				block(SourceWriterTransitionBlocker.CAPTURE_CALLBACK_BARRIER_OPEN)
		}
	}

	private fun requireExpectedRevision(current: TrackingRolloutState, expected: Long) {
		if (current.revision != expected) {
			block(SourceWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED)
		}
	}

	private fun requireContained(rollout: TrackingRolloutState) {
		if (!rollout.authorizesInertShadow(spec.source)) {
			block(SourceWriterTransitionBlocker.ROLLOUT_NOT_CONTAINED)
		}
	}

	private suspend fun requireInitialOwner(
		owner: SourceDestinationOwnerEntity?,
		binding: ExecutableSourceLaneBinding,
	) {
		if (binding == spec.binding) {
			requireLegacyOwner(owner)
			return
		}
		if (binding == spec.finalBinding) {
			if (hasCompletedClearFinalSetup(owner, binding)) {
				return
			}
			val predecessor = database.sourceProjectionStateDao().productLane(
				spec.source.stableCode, spec.binding.bindingGeneration,
				spec.binding.projectionId, spec.binding.projectionVersion,
			)
			if (predecessor == null) {
				requireLegacyOwner(owner)
			}
			else {
				requireSettledOriginalPredecessor(predecessor, owner)
			}
			return
		}
		// Preserve the separate, existing fully declared dynamic-rearm authority path.
		val previousBinding = binding.bindingGeneration - 1L
		if (owner?.owner != spec.containedOwner ||
			owner.ownerGeneration != SourceWriterGenerationContract.containedOwnerGeneration(previousBinding)
		) {
			block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
		}
	}

	/** Current-epoch clear authority is distinct from ordinary same-epoch owner transitions. */
	private suspend fun hasCompletedClearFinalSetup(
		owner: SourceDestinationOwnerEntity?,
		binding: ExecutableSourceLaneBinding,
	): Boolean {
		if (binding != spec.finalBinding || !owner.isExactCandidate(spec, binding.bindingGeneration)) {
			return false
		}
		if (!hasCompletedClearAuthority()) {
			return false
		}
		return hasNoFinalSetupFacts()
	}

	// Preserve original missing epoch/receipt, incompatible clear and weakened floor rejection order.
	@Suppress("ReturnCount")
	private suspend fun hasCompletedClearAuthority(): Boolean {
		val evidence = database.sourceEvidenceStateDao().get() ?: return false
		if (evidence.collectedDataEpoch <= 0L) {
			return false
		}
		val receipt = database.collectedDataDeletionOperationDao()
			.completedFullDeletionAfter(evidence.collectedDataEpoch - 1L) ?: return false
		if (!receipt.isWriterRearmedClearForEpoch(evidence.collectedDataEpoch)) {
			return false
		}
		// Later ordinary retention may tighten the floor; it cannot weaken the original clear.
		val clearFloor = receipt.retainedFromMs
		val retainedFromMs = evidence.retainedFromMs
		if (clearFloor != null && (retainedFromMs == null || retainedFromMs < clearFloor)) {
			return false
		}
		return true
	}

	private suspend fun hasNoFinalSetupFacts(): Boolean {
		if (database.sourceProjectionStateDao().productLane(
				spec.source.stableCode, spec.binding.bindingGeneration,
				spec.binding.projectionId, spec.binding.projectionVersion,
			) != null
		) {
			return false
		}
		val table = when (spec) {
			SourceWriterTransitionSpec.ACTIVITY -> "activity_captured_window_revision"
			SourceWriterTransitionSpec.PRESSURE -> "pressure_fact_revision"
			SourceWriterTransitionSpec.WIFI -> "wifi_captured_fact_revision"
			SourceWriterTransitionSpec.CELL -> "cell_captured_fact_revision"
		}
		return database.openHelper.writableDatabase.query(
			"SELECT EXISTS(SELECT 1 FROM $table LIMIT 1)",
		).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getInt(0) == 0
		}
	}

	private fun requireLegacyOwner(owner: SourceDestinationOwnerEntity?) {
		val expected = spec.initialOwner
		if (expected == null) {
			if (owner != null) {
				block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
			}
			return
		}
		if (owner == null) {
			block(SourceWriterTransitionBlocker.DESTINATION_OWNER_MISSING)
		}
		if (owner?.owner != expected ||
			owner.ownerGeneration != SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
		) {
			block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
		}
	}

	private fun requireSettledOriginalPredecessor(
		predecessor: SourceProductProjectionLaneEntity,
		owner: SourceDestinationOwnerEntity?,
	) {
		if (!predecessor.hasValidOriginalRetirement(spec.binding)) {
			block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		}
		val cutoff = predecessor.captureAdmissionCutoffOrdinal
			?: block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		if (cutoff < predecessor.activationOrdinal - 1L || predecessor.contiguousAdmissionOrdinal != cutoff) {
			block(SourceWriterTransitionBlocker.ROLLBACK_CURSOR_BEHIND)
		}
		when (predecessor.productStage) {
			SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW -> requireLegacyOwner(owner)
			SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL ->
				requireContainedOriginalPredecessor(predecessor, owner, cutoff)
			else -> block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		}
	}

	private fun requireContainedOriginalPredecessor(
		predecessor: SourceProductProjectionLaneEntity,
		owner: SourceDestinationOwnerEntity?,
		cutoff: Long,
	) {
		if (!predecessor.isExactRetiredCanonical(spec, spec.binding,
				predecessor.activatedRolloutRevision, cutoff) || owner?.owner != spec.containedOwner ||
			owner.ownerGeneration != SourceWriterGenerationContract.containedOwnerGeneration(spec.binding.bindingGeneration)
		) {
			block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
		}
	}

	private suspend fun promoteLane(
		lane: SourceProductProjectionLaneEntity,
		canonicalRevision: Long,
		updatedAtMs: Long,
	) {
		if (database.sourceProjectionStateDao().promoteExactProductLaneToCanonical(
				sourceKind = lane.sourceKind,
				bindingGeneration = lane.bindingGeneration,
				projectionId = lane.projectionId,
				projectionVersion = lane.projectionVersion,
				captureModeMask = lane.captureModeMask,
				expectedShadowRolloutRevision = lane.activatedRolloutRevision,
				activationOrdinal = lane.activationOrdinal,
				expectedCurrentOrdinal = lane.contiguousAdmissionOrdinal,
				canonicalRolloutRevision = canonicalRevision,
				updatedAtMs = updatedAtMs,
			) != 1
		) {
			block(SourceWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
		}
	}

	private suspend fun promoteOwner(
		owner: SourceDestinationOwnerEntity?,
		binding: ExecutableSourceLaneBinding,
		updatedAtMs: Long,
	) {
		val canonicalGeneration =
			SourceWriterGenerationContract.canonicalOwnerGeneration(binding.bindingGeneration)
		// requireInitialOwner authenticated the completed-clear epoch in this same transaction.
		// Its exact final owner is already installed; same-epoch monotonic CAS must stay unchanged.
		if (binding == spec.finalBinding && owner.isExactCandidate(spec, binding.bindingGeneration)) {
			return
		}
		val initialOwnerName = spec.initialOwner
		if (owner == null && initialOwnerName == null) {
			val inserted = database.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = spec.source.stableCode,
					destination = spec.destination,
					owner = spec.candidateOwner,
					ownerGeneration = canonicalGeneration,
					updatedAtMs = updatedAtMs,
				),
			)
			if (inserted < 0L) {
				block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
			}
			return
		}
		val initial = owner ?: block(SourceWriterTransitionBlocker.DESTINATION_OWNER_MISSING)
		// requireInitialOwner authenticated this exact first-use or settled-predecessor row above.
		val expectedOwner = initial.owner
		if (database.sourceDestinationOwnerDao().compareAndSetOwner(
				sourceKind = spec.source.stableCode,
				destination = spec.destination,
				expectedOwner = expectedOwner,
				expectedOwnerGeneration = initial.ownerGeneration,
				newOwner = spec.candidateOwner,
				newOwnerGeneration = canonicalGeneration,
				updatedAtMs = updatedAtMs,
			) != 1
		) {
			block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
		}
	}

	private suspend fun verifyCanonicalPostcondition(
		rollout: TrackingRolloutState,
		binding: ExecutableSourceLaneBinding,
	) {
		val lane = exactActiveLane()
		if (lane == null ||
			!lane.matches(binding, ProductProjectionStage.EVENT_CANONICAL, rollout.revision) ||
			!lane.isCanonicalCaptureAuthorizedBy(database, rollout, catalog) ||
			!database.sourceDestinationOwnerDao().isExactOwner(
				spec.source.stableCode,
				spec.destination,
				spec.candidateOwner,
				SourceWriterGenerationContract.canonicalOwnerGeneration(
					binding.bindingGeneration,
				),
			)
		) {
			block(SourceWriterTransitionBlocker.POSTCONDITION_FAILED)
		}
	}

	private fun isCanonicalState(
		rollout: TrackingRolloutState,
		lane: SourceProductProjectionLaneEntity?,
		owner: SourceDestinationOwnerEntity?,
		binding: ExecutableSourceLaneBinding,
	): Boolean {
		val canonical = lane ?: return false
		return canonical.matches(
			binding,
			ProductProjectionStage.EVENT_CANONICAL,
			rollout.revision,
		) &&
			canonical.isCanonicalCaptureAuthorizedBy(rollout, catalog) &&
			owner.isExactCandidate(spec, canonical.bindingGeneration)
	}
}

@Singleton
internal class SourceWriterTransitionBoundary @Inject constructor(
	private val database: AppDatabase,
	private val startupGateProvider: Provider<TrackingStartupGate>,
	private val bootClockDomainProvider: BootClockDomainProvider,
	private val clock: Clock,
) {
	suspend fun run(
		source: SourceKind,
		phase: SourceWriterTransitionPhase,
		operation: suspend (LifecycleLeaseToken) -> SourceWriterTransitionResult,
	): SourceWriterTransitionResult {
		val startupGate = startupGateProvider.get()
		if (startupGate.reconcile() !is TrackingStartupResult.Ready) {
			return SourceWriterTransitionResult.StartupUnavailable(source, phase)
		}
		val generation = startupGate.currentGeneration
		return startupGate.withReadyGenerationOperation(generation) {
			withLease(source, phase, operation)
		} ?: SourceWriterTransitionResult.StartupUnavailable(source, phase)
	}

	suspend fun requireLease(lease: LifecycleLeaseToken) {
		val current = database.sourceProjectionStateDao().lease(lease.leaseName)
			?: block(SourceWriterTransitionBlocker.SESSION_LEASE_LOST)
		val nowElapsedNanos = clock.elapsedRealtimeNanos()
		// Exact saved owner/boot/generation and live expiry for the already acquired lease.
		@Suppress("ComplexCondition")
		if (bootClockDomainProvider.current() != lease.bootId ||
			current.ownerToken != lease.ownerToken ||
			current.bootId != lease.bootId ||
			current.generation != lease.generation ||
			current.expiresElapsedRealtimeNanos <= nowElapsedNanos
		) {
			block(SourceWriterTransitionBlocker.SESSION_LEASE_LOST)
		}
	}

	/** Shared idle checks; Location alone preserves its independently active all-day purpose. */
	suspend fun requireLocationSetupIdle() {
		val sessions = database.sourceSessionDao()
		when {
			sessions.hasLifecycleBoundaryBlocker() -> block(SourceWriterTransitionBlocker.LIFECYCLE_NOT_IDLE)
			sessions.hasIncompleteServiceRun() -> block(SourceWriterTransitionBlocker.SERVICE_RUN_NOT_FINALIZED)
			sessions.hasNonterminalLatestLifecycleAction() ->
                block(SourceWriterTransitionBlocker.LIFECYCLE_ACTION_NOT_TERMINAL)
			!database.isLocationSessionCaptureAdmissionFenced() ->
                block(SourceWriterTransitionBlocker.CAPTURE_CALLBACK_BARRIER_OPEN)
		}
	}

	fun refuse(blocker: SourceWriterTransitionBlocker): Nothing = block(blocker)

	private suspend fun withLease(
		source: SourceKind,
		phase: SourceWriterTransitionPhase,
		operation: suspend (LifecycleLeaseToken) -> SourceWriterTransitionResult,
	): SourceWriterTransitionResult {
		val lease = acquireLease("${source.name.lowercase()}-writer-transition:${UUID.randomUUID()}")
			?: return SourceWriterTransitionResult.Busy(source, phase)
		return try {
			try {
				operation(lease)
			} catch (blocked: SourceWriterTransitionBlockedException) {
				SourceWriterTransitionResult.Blocked(source, phase, blocked.blocker)
			}
		} finally {
			withContext(NonCancellable) { releaseLease(lease) }
		}
	}

	private suspend fun acquireLease(ownerToken: String): LifecycleLeaseToken? = database.withTransaction {
		val bootId = bootClockDomainProvider.current()
		val nowElapsedNanos = clock.elapsedRealtimeNanos()
		val nowMs = clock.currentTimeMillis()
		if (!hasUsableLeaseClock(bootId, nowElapsedNanos, nowMs)
		) {
			return@withTransaction null
		}
		val dao = database.sourceProjectionStateDao()
		val lease = SourceCoordinatorLeaseEntity(
			leaseName = SESSION_LEASE,
			ownerToken = ownerToken,
			acquiredAtMs = nowMs,
			expiresAtMs = nowMs + LEASE_DURATION_MILLIS,
			bootId = bootId,
			generation = 1L,
			acquiredElapsedRealtimeNanos = nowElapsedNanos,
			expiresElapsedRealtimeNanos = nowElapsedNanos + LEASE_DURATION_NANOS,
		)
		if (dao.insertLeaseIfAbsent(lease) < 0L && dao.acquireOrRenewLease(
				leaseName = SESSION_LEASE,
				ownerToken = ownerToken,
				bootId = bootId,
				nowMs = nowMs,
				expiresAtMs = lease.expiresAtMs,
				nowElapsedNanos = nowElapsedNanos,
				expiresElapsedNanos = lease.expiresElapsedRealtimeNanos,
			) != 1
		) {
			return@withTransaction null
		}
		val current = dao.lease(SESSION_LEASE) ?: return@withTransaction null
		if (current.ownerToken != ownerToken || current.bootId != bootId) {
			return@withTransaction null
		}
		LifecycleLeaseToken(SESSION_LEASE, ownerToken, bootId, current.generation)
	}

	private fun hasUsableLeaseClock(bootId: String, nowElapsedNanos: Long, nowMs: Long): Boolean {
		if (bootId.isBlank() || nowElapsedNanos < 0L || nowMs < 0L) {
			return false
		}
		return nowElapsedNanos <= Long.MAX_VALUE - LEASE_DURATION_NANOS &&
			nowMs <= Long.MAX_VALUE - LEASE_DURATION_MILLIS
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

	companion object {
		private const val SESSION_LEASE = "tracking-session-coordinator"
		private const val LEASE_DURATION_NANOS = 30_000_000_000L
		private const val LEASE_DURATION_MILLIS = 30_000L

		fun forTest(
			database: AppDatabase,
			dependencies: SourceWriterTransitionTestDependencies,
		) = SourceWriterTransitionBoundary(
			database,
			Provider { dependencies.startupGate },
			dependencies.bootClockDomainProvider,
			dependencies.clock,
		)
	}
}

/**
 * Existing-writer serialization required before a source-local candidate becomes canonical.
 *
 * Pressure must hold the PersistenceProcessor recovery/flush mutex, prove its in-memory Pressure
 * buffers empty, and prove no durable pending signal can still write under the old destination
 * owner. The owner/lane/rollout operation executes while that fence remains held. Steps' existing
 * quiescence method is not a valid substitute.
 */
interface LegacySourceWriterTransitionBoundary {
	/**
	 * Runs the operation only while the legacy source writer is acknowledged quiescent; otherwise returns
	 * null.
	 */
	suspend fun <T : Any> runIfQuiescent(
		source: SourceKind,
		operation: suspend () -> T,
	): T?

	/**
	 * Unconditional boundary for callers that already establish legacy-writer quiescence outside this
	 * collaborator.
	 */
	companion object {
		val ALWAYS = object : LegacySourceWriterTransitionBoundary {
			override suspend fun <T : Any> runIfQuiescent(
				source: SourceKind,
				operation: suspend () -> T,
			): T = operation()
		}
	}
}

/** Refuses legacy-writer mutation when no implementation can prove the physical writer is quiescent. */
@Singleton
class UnavailableLegacySourceWriterTransitionBoundary @Inject constructor() :
	LegacySourceWriterTransitionBoundary {
	override suspend fun <T : Any> runIfQuiescent(
		source: SourceKind,
		operation: suspend () -> T,
	): T? = null
}

internal data class SourceWriterTransitionTestDependencies(
	val startupGate: TrackingStartupGate,
	val bootClockDomainProvider: BootClockDomainProvider = BootClockDomainProvider { "test-boot" },
	val clock: Clock = FixedStepsWriterTransitionClock,
	val legacyWriterQuiescence: LegacySourceWriterTransitionBoundary =
		LegacySourceWriterTransitionBoundary.ALWAYS,
	val requestDrain: (SourceKind) -> Unit = {},
)

internal enum class SourceWriterTransitionSpec(
	val source: SourceKind,
	val binding: ExecutableSourceLaneBinding,
	val destination: String,
	val initialOwner: String?,
	val candidateOwner: String,
	val containedOwner: String,
	val requiresLegacyWriterQuiescence: Boolean,
) {
	ACTIVITY(
		SourceKind.ACTIVITY,
		ExecutableSourceLaneCatalog.ACTIVITY_SESSION_FACTS,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
		SourceDestinationOwnerEntity.OWNER_LEGACY_ACTIVITY_SNAPSHOT,
		SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
		CONTAINED_ACTIVITY_SESSION_OWNER,
		true,
	),
	PRESSURE(
		SourceKind.PRESSURE,
		ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
		SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
		CONTAINED_PRESSURE_SESSION_OWNER,
		true,
	),
	WIFI(
		SourceKind.WIFI,
		ExecutableSourceLaneCatalog.WIFI_SESSION_FACTS,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI,
		null,
		SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS,
		CONTAINED_WIFI_SESSION_OWNER,
		true,
	),
	CELL(
		SourceKind.CELL,
		ExecutableSourceLaneCatalog.CELL_SESSION_FACTS,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
		null,
		SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS,
		CONTAINED_CELL_SESSION_OWNER,
		true,
	);

	val finalBinding: ExecutableSourceLaneBinding
		get() = when (this) {
			ACTIVITY -> ExecutableSourceLaneCatalog.PREFERRED_ACTIVITY_SESSION_FACTS
			PRESSURE -> ExecutableSourceLaneCatalog.PREFERRED_PRESSURE_SESSION_FACTS
			WIFI -> ExecutableSourceLaneCatalog.PREFERRED_WIFI_SESSION_FACTS
			CELL -> ExecutableSourceLaneCatalog.PREFERRED_CELL_SESSION_FACTS
		}
}

private fun CollectedDataDeletionOperationEntity.isWriterRearmedClearForEpoch(epoch: Long): Boolean =
	targetCollectedDataEpoch == epoch && phase == CollectedDataDeletionOperationEntity.PHASE_WRITERS_REARMED &&
	retentionWorkExecutionId == null && retentionDestructivePlan == null

// Complete saved original-writer retirement tuple; cursor and destination checks remain separate.
@Suppress("ComplexCondition")
private fun SourceProductProjectionLaneEntity.hasValidOriginalRetirement(
	binding: ExecutableSourceLaneBinding,
): Boolean {
	val terminalAtMs = this.terminalAtMs
	return matchesSourceBinding(binding) &&
		status == SourceProductProjectionLaneEntity.STATUS_RETIRED &&
		!retentionRequired && activationOrdinal > 0L &&
		activatedRolloutRevision > 0L && terminalAtMs != null && terminalAtMs >= 0L &&
		terminalDisposition == SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN
}

private fun SourceWriterTransitionResult.isApplied(): Boolean =
	this is SourceWriterTransitionResult.Applied || this is SourceWriterTransitionResult.AlreadyApplied

private fun TrackingRolloutState.isSourceContained(source: SourceKind): Boolean =
	sourceOwners.getValue(source) == SourceOwner.CONTAINED &&
		productProjectionStages.getValue(source) == ProductProjectionStage.LEGACY_CANONICAL &&
		captureModeMasks.getValue(source) == 0L

private fun SourceDestinationOwnerEntity?.isExactCandidate(
	spec: SourceWriterTransitionSpec,
	bindingGeneration: Long,
): Boolean =
	this?.sourceKind == spec.source.stableCode &&
		destination == spec.destination &&
		owner == spec.candidateOwner &&
		ownerGeneration ==
			SourceWriterGenerationContract.canonicalOwnerGeneration(bindingGeneration)

private fun SourceWriterTransitionSpec.contractBinding(
	binding: ExecutableSourceLaneBinding,
): SourceWriterGenerationBinding = SourceWriterGenerationBinding(
	sourceKind = source.stableCode,
	destination = destination,
	candidateOwner = candidateOwner,
	containedOwner = containedOwner,
	bindingGeneration = binding.bindingGeneration,
	projectionId = binding.projectionId,
	projectionVersion = binding.projectionVersion,
	canonicalStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
)

private fun SourceProductProjectionLaneEntity.canonicalActivation(
	spec: SourceWriterTransitionSpec,
	writerOwner: String,
	writerOwnerGeneration: Long,
): SourceWriterCanonicalActivation = SourceWriterCanonicalActivation(
	sourceKind = sourceKind,
	destination = spec.destination,
	writerOwner = writerOwner,
	writerOwnerGeneration = writerOwnerGeneration,
	writerBindingGeneration = bindingGeneration,
	writerProjectionId = projectionId,
	writerProjectionVersion = projectionVersion,
	productStage = productStage,
	activationRevision = activatedRolloutRevision,
)

private fun SourceProductProjectionLaneEntity.matchesExactCanonicalActivation(
	spec: SourceWriterTransitionSpec,
	binding: ExecutableSourceLaneBinding,
	owner: SourceDestinationOwnerEntity?,
	expectedActivationRevision: Long,
): Boolean {
	val exactOwner = owner ?: return false
	return matchesSourceBinding(binding) &&
		spec.contractBinding(binding).matchesCanonicalActivation(
		canonicalActivation(
			spec = spec,
			writerOwner = exactOwner.owner,
			writerOwnerGeneration = exactOwner.ownerGeneration,
		),
		expectedActivationRevision,
	)
}

private fun SourceProductProjectionLaneEntity.isExactRetiredCanonical(
	spec: SourceWriterTransitionSpec,
	binding: ExecutableSourceLaneBinding,
	expectedActivationRevision: Long,
	expectedCutoffAdmissionOrdinal: Long,
): Boolean = status == SourceProductProjectionLaneEntity.STATUS_RETIRED &&
	terminalDisposition == SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN &&
	captureAdmissionCutoffOrdinal == expectedCutoffAdmissionOrdinal &&
	contiguousAdmissionOrdinal == expectedCutoffAdmissionOrdinal &&
	matchesSourceBinding(binding) &&
	spec.contractBinding(binding).matchesCanonicalActivation(
		canonicalActivation(
			spec = spec,
			writerOwner = spec.candidateOwner,
			writerOwnerGeneration = SourceWriterGenerationContract.canonicalOwnerGeneration(
				binding.bindingGeneration,
			),
		),
		expectedActivationRevision,
	)

private fun SourceProductProjectionLaneEntity.matchesSourceBinding(
	binding: ExecutableSourceLaneBinding,
): Boolean = sourceKind == binding.source.stableCode &&
	bindingGeneration == binding.bindingGeneration &&
	projectionId == binding.projectionId &&
	projectionVersion == binding.projectionVersion &&
	captureModeMask == binding.captureModeMask

private fun previousRevision(current: Long): Long {
	if (current <= 0L) {
		block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
	}
	return current - 1L
}

private fun nextRevision(current: Long): Long {
	if (current == Long.MAX_VALUE) {
		block(SourceWriterTransitionBlocker.REVISION_EXHAUSTED)
	}
	return current + 1L
}

private class SourceWriterTransitionBlockedException(
	val blocker: SourceWriterTransitionBlocker,
) : RuntimeException(blocker.name)

private fun block(blocker: SourceWriterTransitionBlocker): Nothing =
	throw SourceWriterTransitionBlockedException(blocker)

/** Coordinator compatibility aliases for the centralized destination-owner vocabulary. */
internal const val CONTAINED_ACTIVITY_SESSION_OWNER =
	SourceDestinationOwnerEntity.OWNER_CONTAINED_ACTIVITY_SESSION_FACTS
internal const val CONTAINED_PRESSURE_SESSION_OWNER =
	SourceDestinationOwnerEntity.OWNER_CONTAINED_PRESSURE_SESSION_FACTS
internal const val CONTAINED_WIFI_SESSION_OWNER =
	SourceDestinationOwnerEntity.OWNER_CONTAINED_WIFI_SESSION_FACTS
internal const val CONTAINED_CELL_SESSION_OWNER =
	SourceDestinationOwnerEntity.OWNER_CONTAINED_CELL_SESSION_FACTS
