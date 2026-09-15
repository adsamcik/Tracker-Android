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

sealed interface SourceWriterTransitionResult {
	val source: SourceKind
	val phase: SourceWriterTransitionPhase

	data class Applied(
		override val source: SourceKind,
		override val phase: SourceWriterTransitionPhase,
		val rolloutRevision: Long,
		val ownerGeneration: Long?,
		val cutoffAdmissionOrdinal: Long?,
	) : SourceWriterTransitionResult

	data class AlreadyApplied(
		override val source: SourceKind,
		override val phase: SourceWriterTransitionPhase,
		val rolloutRevision: Long,
		val ownerGeneration: Long?,
		val cutoffAdmissionOrdinal: Long?,
	) : SourceWriterTransitionResult

	data class Blocked(
		override val source: SourceKind,
		override val phase: SourceWriterTransitionPhase,
		val blocker: SourceWriterTransitionBlocker,
	) : SourceWriterTransitionResult

	data class StartupUnavailable(
		override val source: SourceKind,
		override val phase: SourceWriterTransitionPhase,
	) : SourceWriterTransitionResult

	data class Busy(
		override val source: SourceKind,
		override val phase: SourceWriterTransitionPhase,
	) : SourceWriterTransitionResult
}

enum class SourceWriterTransitionPhase {
	INSTALL_INERT_CANDIDATE,
	ACTIVATE_CANDIDATE,
	BEGIN_CANDIDATE_ROLLBACK,
	COMPLETE_CANDIDATE_ROLLBACK,
	REARM_AFTER_FULL_DELETION,
}

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
	POSTCONDITION_FAILED,
}

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

	suspend fun installInertCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.installInertCandidate(expectedRolloutRevision, updatedAtMs)

	suspend fun activateCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.activateCandidate(expectedRolloutRevision, updatedAtMs)

	suspend fun beginCandidateRollback(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.beginCandidateRollback(expectedRolloutRevision, updatedAtMs)

	suspend fun completeCandidateRollback(
		expectedContainedRolloutRevision: Long,
		expectedCutoffAdmissionOrdinal: Long,
		updatedAtMs: Long,
	) = engine.completeCandidateRollback(
		expectedContainedRolloutRevision,
		expectedCutoffAdmissionOrdinal,
		updatedAtMs,
	)

	suspend fun rearmAfterFullDeletion(updatedAtMs: Long) = engine.rearmAfterFullDeletion(updatedAtMs)
}

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

	suspend fun installInertCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.installInertCandidate(expectedRolloutRevision, updatedAtMs)

	suspend fun activateCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.activateCandidate(expectedRolloutRevision, updatedAtMs)

	suspend fun beginCandidateRollback(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.beginCandidateRollback(expectedRolloutRevision, updatedAtMs)

	suspend fun completeCandidateRollback(
		expectedContainedRolloutRevision: Long,
		expectedCutoffAdmissionOrdinal: Long,
		updatedAtMs: Long,
	) = engine.completeCandidateRollback(
		expectedContainedRolloutRevision,
		expectedCutoffAdmissionOrdinal,
		updatedAtMs,
	)

	suspend fun rearmAfterFullDeletion(updatedAtMs: Long) = engine.rearmAfterFullDeletion(updatedAtMs)
}

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

	suspend fun installInertCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.installInertCandidate(expectedRolloutRevision, updatedAtMs)

	suspend fun activateCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.activateCandidate(expectedRolloutRevision, updatedAtMs)

	suspend fun beginCandidateRollback(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.beginCandidateRollback(expectedRolloutRevision, updatedAtMs)

	suspend fun completeCandidateRollback(
		expectedContainedRolloutRevision: Long,
		expectedCutoffAdmissionOrdinal: Long,
		updatedAtMs: Long,
	) = engine.completeCandidateRollback(
		expectedContainedRolloutRevision,
		expectedCutoffAdmissionOrdinal,
		updatedAtMs,
	)

	suspend fun rearmAfterFullDeletion(updatedAtMs: Long) = engine.rearmAfterFullDeletion(updatedAtMs)
}

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

	suspend fun installInertCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.installInertCandidate(expectedRolloutRevision, updatedAtMs)

	suspend fun activateCandidate(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.activateCandidate(expectedRolloutRevision, updatedAtMs)

	suspend fun beginCandidateRollback(expectedRolloutRevision: Long, updatedAtMs: Long) =
		engine.beginCandidateRollback(expectedRolloutRevision, updatedAtMs)

	suspend fun completeCandidateRollback(
		expectedContainedRolloutRevision: Long,
		expectedCutoffAdmissionOrdinal: Long,
		updatedAtMs: Long,
	) = engine.completeCandidateRollback(
		expectedContainedRolloutRevision,
		expectedCutoffAdmissionOrdinal,
		updatedAtMs,
	)

	suspend fun rearmAfterFullDeletion(updatedAtMs: Long) = engine.rearmAfterFullDeletion(updatedAtMs)
}

@Singleton
internal class SourceWriterTransitionEngineFactory private constructor(
	private val database: AppDatabase,
	private val catalog: ExecutableSourceLaneCatalog,
	private val boundary: SourceWriterTransitionBoundary,
	private val legacyWriterQuiescence: LegacySourceWriterQuiescence,
	private val requestDrain: (SourceKind) -> Unit,
) {
	@Inject
	constructor(
		database: AppDatabase,
		catalog: ExecutableSourceLaneCatalog,
		boundary: SourceWriterTransitionBoundary,
		persistenceProcessorProvider: Provider<PersistenceProcessor>,
		recoveryProvider: Provider<SourcePipelineRecovery>,
	) : this(
		database,
		catalog,
		boundary,
		PersistenceLegacySourceWriterQuiescence(persistenceProcessorProvider),
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

internal class SourceWriterTransitionEngine(
	private val database: AppDatabase,
	private val catalog: ExecutableSourceLaneCatalog,
	private val boundary: SourceWriterTransitionBoundary,
	private val legacyWriterQuiescence: LegacySourceWriterQuiescence,
	private val requestDrain: (SourceKind) -> Unit,
	private val spec: SourceWriterTransitionSpec,
) {
	suspend fun installInertCandidate(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
	): SourceWriterTransitionResult {
		require(expectedRolloutRevision >= 0L)
		require(updatedAtMs >= 0L)
		return boundary.run(
			spec.source,
			SourceWriterTransitionPhase.INSTALL_INERT_CANDIDATE,
		) { lease ->
			database.withTransaction {
				boundary.requireLease(lease)
				val rollout = currentRollout()
				val active = exactActiveLane()
				if (active?.matches(spec.binding, ProductProjectionStage.EVENT_SHADOW, rollout.revision) == true &&
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
				requireRunBoundary()
				if (!catalog.owns(spec.binding)) block(SourceWriterTransitionBlocker.BINDING_NOT_EXECUTABLE)
				if (active != null) {
					block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
				}
				if (database.sourceProjectionStateDao().latestProductLane(spec.source.stableCode) != null) {
					block(SourceWriterTransitionBlocker.REARM_BINDING_CONTRACT_UNAVAILABLE)
				}
				if (database.sourceProjectionStateDao().registration(
						spec.binding.projectionId,
						spec.binding.projectionVersion,
					) != null
				) block(SourceWriterTransitionBlocker.GLOBAL_WRITER_COLLISION)
				if (database.sourceProjectionStateDao().productLanesByProjection(
						spec.binding.projectionId,
						spec.binding.projectionVersion,
					).any { lane -> lane.sourceKind != spec.source.stableCode }
				) block(SourceWriterTransitionBlocker.GLOBAL_WRITER_COLLISION)
				val revision = nextRevision(expectedRolloutRevision)
				val activation = RoomTrackingRolloutStateStore(database, catalog).installInertShadowLane(
					spec.binding,
					revision,
					updatedAtMs,
				)
				SourceWriterTransitionResult.Applied(
					spec.source,
					SourceWriterTransitionPhase.INSTALL_INERT_CANDIDATE,
					activation.rollout.revision,
					currentOwner()?.ownerGeneration,
					null,
				)
			}
		}
	}

	suspend fun activateCandidate(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
	): SourceWriterTransitionResult {
		require(expectedRolloutRevision >= 0L)
		require(updatedAtMs >= 0L)
		return boundary.run(
			spec.source,
			SourceWriterTransitionPhase.ACTIVATE_CANDIDATE,
		) { lease ->
			val operation: suspend () -> SourceWriterTransitionResult = {
				database.withTransaction {
					boundary.requireLease(lease)
					val rollout = currentRollout()
					val lane = exactActiveLane()
					val owner = currentOwner()
					if (isCanonicalState(rollout, lane, owner) &&
						rollout.revision == successfulRevisionAfter(expectedRolloutRevision)
					) {
						return@withTransaction SourceWriterTransitionResult.AlreadyApplied(
							spec.source,
							SourceWriterTransitionPhase.ACTIVATE_CANDIDATE,
							rollout.revision,
							owner?.ownerGeneration,
							null,
						)
					}
					requireExpectedRevision(rollout, expectedRolloutRevision)
					requireContained(rollout)
					requireRunBoundary()
					requireInitialOwner(owner)
					val shadow = lane ?: block(SourceWriterTransitionBlocker.ACTIVE_LANE_MISSING)
					if (!shadow.matches(spec.binding, ProductProjectionStage.EVENT_SHADOW, rollout.revision)) {
						block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
					}
					if (database.sourceProjectionStateDao().registration(
							spec.binding.projectionId,
							spec.binding.projectionVersion,
						) != null
					) block(SourceWriterTransitionBlocker.GLOBAL_WRITER_COLLISION)
					val highWater = database.liveSourceProjectionActivationOrdinal() - 1L
					if (shadow.contiguousAdmissionOrdinal != highWater) {
						block(SourceWriterTransitionBlocker.SHADOW_CURSOR_BEHIND)
					}
					val canonicalRevision = nextRevision(rollout.revision)
					promoteLane(shadow, canonicalRevision, updatedAtMs)
					promoteOwner(owner, updatedAtMs)
					val canonical = rollout.copy(
						revision = canonicalRevision,
						sourceOwners = rollout.sourceOwners + (spec.source to SourceOwner.EVENT),
						productProjectionStages = rollout.productProjectionStages +
							(spec.source to ProductProjectionStage.EVENT_CANONICAL),
						captureModeMasks = rollout.captureModeMasks +
							(spec.source to spec.binding.captureModeMask),
					)
					database.trackingRolloutStateDao().save(canonical.toEntity(updatedAtMs))
					verifyCanonicalPostcondition(canonical)
					SourceWriterTransitionResult.Applied(
						spec.source,
						SourceWriterTransitionPhase.ACTIVATE_CANDIDATE,
						canonicalRevision,
						SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
						null,
					)
				}
			}
			if (spec.requiresLegacyWriterQuiescence) {
				legacyWriterQuiescence.runIfQuiescent(operation)
					?: SourceWriterTransitionResult.Blocked(
						spec.source,
						SourceWriterTransitionPhase.ACTIVATE_CANDIDATE,
						SourceWriterTransitionBlocker.LEGACY_WRITER_NOT_QUIESCENT,
					)
			} else {
				operation()
			}
		}
	}

	suspend fun beginCandidateRollback(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
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
				val owner = currentOwner()
				val cutoff = lane?.captureAdmissionCutoffOrdinal
				if (cutoff != null && rollout.revision == successfulRevisionAfter(expectedRolloutRevision) &&
					rollout.isSourceContained(spec.source) && owner.isExactCandidate(spec)
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
				requireRunBoundary()
				if (!isCanonicalState(rollout, lane, owner)) {
					block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
				}
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
				) block(SourceWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
				SourceWriterTransitionResult.Applied(
					spec.source,
					SourceWriterTransitionPhase.BEGIN_CANDIDATE_ROLLBACK,
					containedRevision,
					owner?.ownerGeneration,
					cutoffOrdinal,
				)
			}
		}
		if (result is SourceWriterTransitionResult.Applied ||
			result is SourceWriterTransitionResult.AlreadyApplied
		) {
			requestDrain(spec.source)
		}
		return result
	}

	suspend fun completeCandidateRollback(
		expectedContainedRolloutRevision: Long,
		expectedCutoffAdmissionOrdinal: Long,
		updatedAtMs: Long,
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
				val owner = currentOwner()
				val retired = database.sourceProjectionStateDao().productLane(
					spec.source.stableCode,
					spec.binding.bindingGeneration,
					spec.binding.projectionId,
					spec.binding.projectionVersion,
				)
				if (rollout.revision == expectedContainedRolloutRevision &&
					lane == null &&
					owner?.owner == spec.rollbackOwner &&
					owner.ownerGeneration == ROLLBACK_OWNER_GENERATION &&
					retired?.status == SourceProductProjectionLaneEntity.STATUS_RETIRED &&
					retired.captureAdmissionCutoffOrdinal == expectedCutoffAdmissionOrdinal
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
				requireRunBoundary()
				if (!owner.isExactCandidate(spec)) {
					block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
				}
				val draining = lane ?: block(SourceWriterTransitionBlocker.ACTIVE_LANE_MISSING)
				if (!draining.matchesSourceBinding(spec.binding) ||
					draining.productStage != SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL ||
					draining.captureAdmissionCutoffOrdinal != expectedCutoffAdmissionOrdinal
				) block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
				if (draining.contiguousAdmissionOrdinal != expectedCutoffAdmissionOrdinal) {
					block(SourceWriterTransitionBlocker.ROLLBACK_CURSOR_BEHIND)
				}
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
				) block(SourceWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
				val candidate = requireNotNull(owner)
				if (database.sourceDestinationOwnerDao().compareAndSetOwner(
						sourceKind = spec.source.stableCode,
						destination = spec.destination,
						expectedOwner = spec.candidateOwner,
						expectedOwnerGeneration = candidate.ownerGeneration,
						newOwner = spec.rollbackOwner,
						newOwnerGeneration = ROLLBACK_OWNER_GENERATION,
						updatedAtMs = updatedAtMs,
					) != 1
				) block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
				SourceWriterTransitionResult.Applied(
					spec.source,
					SourceWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK,
					rollout.revision,
					ROLLBACK_OWNER_GENERATION,
					expectedCutoffAdmissionOrdinal,
				)
			}
		}
	}

	suspend fun rearmAfterFullDeletion(updatedAtMs: Long): SourceWriterTransitionResult {
		require(updatedAtMs >= 0L)
		// Core manifest/owner validation currently recognizes only generation-1 bindings and the
		// first candidate owner generation. Returning a typed blocker prevents an ABA reactivation.
		return SourceWriterTransitionResult.Blocked(
			spec.source,
			SourceWriterTransitionPhase.REARM_AFTER_FULL_DELETION,
			SourceWriterTransitionBlocker.REARM_BINDING_CONTRACT_UNAVAILABLE,
		)
	}

	private suspend fun currentRollout(): TrackingRolloutState =
		database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull()
			?: block(SourceWriterTransitionBlocker.ROLLOUT_MISSING_OR_UNREADABLE)

	private suspend fun exactActiveLane(): SourceProductProjectionLaneEntity? {
		val lanes = database.sourceProjectionStateDao().allActiveProductLanes()
			.filter { lane -> lane.sourceKind == spec.source.stableCode }
		if (lanes.size > 1) block(SourceWriterTransitionBlocker.MULTIPLE_ACTIVE_LANES)
		return lanes.singleOrNull()
	}

	private suspend fun currentOwner(): SourceDestinationOwnerEntity? =
		database.sourceDestinationOwnerDao().get(spec.source.stableCode, spec.destination)

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
		if (current.revision != expected) block(SourceWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED)
	}

	private fun requireContained(rollout: TrackingRolloutState) {
		if (!rollout.isSourceContained(spec.source)) {
			block(SourceWriterTransitionBlocker.ROLLOUT_NOT_CONTAINED)
		}
	}

	private fun requireInitialOwner(owner: SourceDestinationOwnerEntity?) {
		val expected = spec.initialOwner
		if (expected == null) {
			if (owner != null) block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
			return
		}
		if (owner == null) block(SourceWriterTransitionBlocker.DESTINATION_OWNER_MISSING)
		if (owner?.owner != expected ||
			owner.ownerGeneration != SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
		) block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
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
		) block(SourceWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
	}

	private suspend fun promoteOwner(owner: SourceDestinationOwnerEntity?, updatedAtMs: Long) {
		val initialOwnerName = spec.initialOwner
		if (initialOwnerName == null) {
			val inserted = database.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = spec.source.stableCode,
					destination = spec.destination,
					owner = spec.candidateOwner,
					ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
					updatedAtMs = updatedAtMs,
				),
			)
			if (inserted < 0L) block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
			return
		}
		val initial = owner ?: block(SourceWriterTransitionBlocker.DESTINATION_OWNER_MISSING)
		if (database.sourceDestinationOwnerDao().compareAndSetOwner(
				sourceKind = spec.source.stableCode,
				destination = spec.destination,
				expectedOwner = initialOwnerName,
				expectedOwnerGeneration = initial.ownerGeneration,
				newOwner = spec.candidateOwner,
				newOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
				updatedAtMs = updatedAtMs,
			) != 1
		) block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
	}

	private suspend fun verifyCanonicalPostcondition(rollout: TrackingRolloutState) {
		val lane = exactActiveLane()
		if (lane == null ||
			!lane.matches(spec.binding, ProductProjectionStage.EVENT_CANONICAL, rollout.revision) ||
			!lane.isCanonicalCaptureAuthorizedBy(database, rollout, catalog) ||
			!database.sourceDestinationOwnerDao().isExactOwner(
				spec.source.stableCode,
				spec.destination,
				spec.candidateOwner,
				SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			)
		) block(SourceWriterTransitionBlocker.POSTCONDITION_FAILED)
	}

	private fun isCanonicalState(
		rollout: TrackingRolloutState,
		lane: SourceProductProjectionLaneEntity?,
		owner: SourceDestinationOwnerEntity?,
	): Boolean {
		val canonical = lane ?: return false
		return canonical.matches(
			spec.binding,
			ProductProjectionStage.EVENT_CANONICAL,
			rollout.revision,
		) &&
			canonical.isCanonicalCaptureAuthorizedBy(rollout, catalog) &&
			owner.isExactCandidate(spec)
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
		if (bootClockDomainProvider.current() != lease.bootId ||
			current.ownerToken != lease.ownerToken ||
			current.bootId != lease.bootId ||
			current.generation != lease.generation ||
			current.expiresElapsedRealtimeNanos <= nowElapsedNanos
		) block(SourceWriterTransitionBlocker.SESSION_LEASE_LOST)
	}

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
			releaseLease(lease)
		}
	}

	private suspend fun acquireLease(ownerToken: String): LifecycleLeaseToken? = database.withTransaction {
		val bootId = bootClockDomainProvider.current()
		val nowElapsedNanos = clock.elapsedRealtimeNanos()
		val nowMs = clock.currentTimeMillis()
		if (bootId.isBlank() || nowElapsedNanos < 0L || nowMs < 0L ||
			nowElapsedNanos > Long.MAX_VALUE - LEASE_DURATION_NANOS ||
			nowMs > Long.MAX_VALUE - LEASE_DURATION_MILLIS
		) return@withTransaction null
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
		) return@withTransaction null
		val current = dao.lease(SESSION_LEASE) ?: return@withTransaction null
		if (current.ownerToken != ownerToken || current.bootId != bootId) return@withTransaction null
		LifecycleLeaseToken(SESSION_LEASE, ownerToken, bootId, current.generation)
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

internal interface LegacySourceWriterQuiescence {
	suspend fun <T : Any> runIfQuiescent(operation: suspend () -> T): T?

	companion object {
		val ALWAYS = object : LegacySourceWriterQuiescence {
			override suspend fun <T : Any> runIfQuiescent(operation: suspend () -> T): T = operation()
		}
	}
}

private class PersistenceLegacySourceWriterQuiescence(
	private val persistenceProcessorProvider: Provider<PersistenceProcessor>,
) : LegacySourceWriterQuiescence {
	override suspend fun <T : Any> runIfQuiescent(operation: suspend () -> T): T? =
		persistenceProcessorProvider.get().withLegacyStepsWriterQuiesced(operation)
}

internal data class SourceWriterTransitionTestDependencies(
	val startupGate: TrackingStartupGate,
	val bootClockDomainProvider: BootClockDomainProvider = BootClockDomainProvider { "test-boot" },
	val clock: Clock = FixedStepsWriterTransitionClock,
	val legacyWriterQuiescence: LegacySourceWriterQuiescence = LegacySourceWriterQuiescence.ALWAYS,
	val requestDrain: (SourceKind) -> Unit = {},
)

internal enum class SourceWriterTransitionSpec(
	val source: SourceKind,
	val binding: ExecutableSourceLaneBinding,
	val destination: String,
	val initialOwner: String?,
	val candidateOwner: String,
	val rollbackOwner: String,
	val requiresLegacyWriterQuiescence: Boolean,
) {
	ACTIVITY(
		SourceKind.ACTIVITY,
		ExecutableSourceLaneCatalog.ACTIVITY_SESSION_FACTS,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
		SourceDestinationOwnerEntity.OWNER_LEGACY_ACTIVITY_SNAPSHOT,
		SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
		SourceDestinationOwnerEntity.OWNER_LEGACY_ACTIVITY_SNAPSHOT,
		true,
	),
	PRESSURE(
		SourceKind.PRESSURE,
		ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
		SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
		SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
		true,
	),
	WIFI(
		SourceKind.WIFI,
		ExecutableSourceLaneCatalog.WIFI_SESSION_FACTS,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI,
		null,
		SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS,
		CONTAINED_WIFI_SESSION_OWNER,
		false,
	),
	CELL(
		SourceKind.CELL,
		ExecutableSourceLaneCatalog.CELL_SESSION_FACTS,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
		null,
		SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS,
		CONTAINED_CELL_SESSION_OWNER,
		false,
	),
}

private fun TrackingRolloutState.isSourceContained(source: SourceKind): Boolean =
	sourceOwners.getValue(source) == SourceOwner.CONTAINED &&
		productProjectionStages.getValue(source) == ProductProjectionStage.LEGACY_CANONICAL &&
		captureModeMasks.getValue(source) == 0L

private fun SourceDestinationOwnerEntity?.isExactCandidate(spec: SourceWriterTransitionSpec): Boolean =
	this?.sourceKind == spec.source.stableCode &&
		destination == spec.destination &&
		owner == spec.candidateOwner &&
		ownerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION

private fun SourceProductProjectionLaneEntity.matchesSourceBinding(
	binding: ExecutableSourceLaneBinding,
): Boolean = sourceKind == binding.source.stableCode &&
	bindingGeneration == binding.bindingGeneration &&
	projectionId == binding.projectionId &&
	projectionVersion == binding.projectionVersion &&
	captureModeMask == binding.captureModeMask

private fun nextRevision(current: Long): Long {
	if (current == Long.MAX_VALUE) block(SourceWriterTransitionBlocker.REVISION_EXHAUSTED)
	return current + 1L
}

private class SourceWriterTransitionBlockedException(
	val blocker: SourceWriterTransitionBlocker,
) : RuntimeException(blocker.name)

private fun block(blocker: SourceWriterTransitionBlocker): Nothing =
	throw SourceWriterTransitionBlockedException(blocker)

private const val ROLLBACK_OWNER_GENERATION = 3L
/** Parent-owned core constants are required before a later re-arm generation can be executable. */
internal const val CONTAINED_WIFI_SESSION_OWNER = "CONTAINED_WIFI_SESSION_FACTS"
internal const val CONTAINED_CELL_SESSION_OWNER = "CONTAINED_CELL_SESSION_FACTS"
