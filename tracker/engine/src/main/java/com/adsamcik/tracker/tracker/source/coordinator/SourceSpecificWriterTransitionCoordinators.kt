package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
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
	FULL_DELETION_PROOF_UNAVAILABLE,
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

	suspend fun rearmAfterFullDeletion(
		completedFullDeletion: SourceWriterCompletedFullDeletion,
		updatedAtMs: Long,
	) = engine.rearmAfterFullDeletion(completedFullDeletion, updatedAtMs)
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

	suspend fun rearmAfterFullDeletion(
		completedFullDeletion: SourceWriterCompletedFullDeletion,
		updatedAtMs: Long,
	) = engine.rearmAfterFullDeletion(completedFullDeletion, updatedAtMs)
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

	suspend fun rearmAfterFullDeletion(
		completedFullDeletion: SourceWriterCompletedFullDeletion,
		updatedAtMs: Long,
	) = engine.rearmAfterFullDeletion(completedFullDeletion, updatedAtMs)
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

internal class SourceWriterTransitionEngine(
	private val database: AppDatabase,
	private val catalog: ExecutableSourceLaneCatalog,
	private val boundary: SourceWriterTransitionBoundary,
	private val legacyWriterQuiescence: LegacySourceWriterTransitionBoundary,
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
		val activation: suspend () -> SourceWriterTransitionResult = {
			boundary.run(
				spec.source,
				SourceWriterTransitionPhase.ACTIVATE_CANDIDATE,
			) { lease ->
				database.withTransaction {
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
					requireInitialOwner(owner, binding)
					val shadow = lane ?: block(SourceWriterTransitionBlocker.ACTIVE_LANE_MISSING)
					if (!shadow.matches(binding, ProductProjectionStage.EVENT_SHADOW, rollout.revision)) {
						block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
					}
					if (database.sourceProjectionStateDao().registration(
							binding.projectionId,
							binding.projectionVersion,
						) != null
					) block(SourceWriterTransitionBlocker.GLOBAL_WRITER_COLLISION)
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
					SourceWriterTransitionResult.Applied(
						spec.source,
						SourceWriterTransitionPhase.ACTIVATE_CANDIDATE,
						canonicalRevision,
						SourceWriterGenerationContract.canonicalOwnerGeneration(
							binding.bindingGeneration,
						),
						null,
					)
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
				requireRunBoundary()
				if (!isCanonicalState(rollout, lane, owner, binding)) {
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
				val binding = executableBinding(lane)
				val owner = currentOwner()
				val retired = database.sourceProjectionStateDao().productLane(
					spec.source.stableCode,
					binding.bindingGeneration,
					binding.projectionId,
					binding.projectionVersion,
				)
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
				requireRunBoundary()
				if (!owner.isExactCandidate(spec, binding.bindingGeneration)) {
					block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
				}
				val draining = lane ?: block(SourceWriterTransitionBlocker.ACTIVE_LANE_MISSING)
				if (!draining.matchesSourceBinding(binding) ||
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
				) block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
				SourceWriterTransitionResult.Applied(
					spec.source,
					SourceWriterTransitionPhase.COMPLETE_CANDIDATE_ROLLBACK,
					rollout.revision,
					containedOwnerGeneration,
					expectedCutoffAdmissionOrdinal,
				)
			}
		}
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
				val rollout = currentRollout()
				requireContained(rollout)
				requireRunBoundary()
				if (exactActiveLane() != null) {
					block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
				}
				val retired = database.sourceProjectionStateDao()
					.latestProductLane(spec.source.stableCode)
					?: block(SourceWriterTransitionBlocker.ACTIVE_LANE_MISSING)
				if (retired.status != SourceProductProjectionLaneEntity.STATUS_RETIRED ||
					retired.productStage != SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL ||
					retired.activatedRolloutRevision <= 0L ||
					retired.captureAdmissionCutoffOrdinal == null ||
					retired.contiguousAdmissionOrdinal != retired.captureAdmissionCutoffOrdinal ||
					retired.terminalDisposition !=
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN
				) block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
				val currentBinding = catalog.bindingFor(retired)
					?: block(SourceWriterTransitionBlocker.BINDING_NOT_EXECUTABLE)
				val owner = currentOwner()
				if (owner?.owner != spec.containedOwner ||
					owner.ownerGeneration !=
					SourceWriterGenerationContract.containedOwnerGeneration(
						currentBinding.bindingGeneration,
					)
				) block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
				val deletion = completedFullDeletion
					?: block(SourceWriterTransitionBlocker.FULL_DELETION_PROOF_UNAVAILABLE)
				val authorityInput = SourceWriterRearmAuthorityInput(
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
				val rearmBinding = catalog.nextRearmBinding(
					current = currentBinding,
					input = authorityInput,
					destination = spec.destination,
					candidateOwner = spec.candidateOwner,
					containedOwner = spec.containedOwner,
				) ?: block(SourceWriterTransitionBlocker.REARM_BINDING_CONTRACT_UNAVAILABLE)
				RearmSnapshot(
					rollout = rollout,
					currentBinding = currentBinding,
					rearmBinding = rearmBinding,
					authorityInput = authorityInput,
					containedOwnerGeneration = owner.ownerGeneration,
					retiredLane = retired,
				)
			}
			snapshot.rearmBinding.declaration.runIfFullySupportedAndAuthorized(
				snapshot.authorityInput,
			) { authorizedNextBinding ->
				if (authorizedNextBinding != snapshot.rearmBinding.nextContractBinding) {
					block(SourceWriterTransitionBlocker.REARM_BINDING_CONTRACT_UNAVAILABLE)
				}
				val activation = database.withTransaction {
					boundary.requireLease(lease)
					val current = currentRollout()
					if (current != snapshot.rollout || exactActiveLane() != null) {
						block(SourceWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED)
					}
					val owner = currentOwner()
					if (owner?.owner != spec.containedOwner ||
						owner.ownerGeneration != snapshot.containedOwnerGeneration
					) block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
					val retired = database.sourceProjectionStateDao()
						.latestProductLane(spec.source.stableCode)
					if (retired == null ||
						retired != snapshot.retiredLane ||
						catalog.bindingFor(retired) != snapshot.currentBinding
					) block(SourceWriterTransitionBlocker.ACTIVE_LANE_CHANGED)
					val nextRevision = nextRevision(snapshot.rollout.revision)
					RoomTrackingRolloutStateStore(database, catalog)
						.rearmInertShadowLane(snapshot.rearmBinding, nextRevision, updatedAtMs)
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
		if (lanes.size > 1) block(SourceWriterTransitionBlocker.MULTIPLE_ACTIVE_LANES)
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

	private fun requireInitialOwner(
		owner: SourceDestinationOwnerEntity?,
		binding: ExecutableSourceLaneBinding,
	) {
		val bindingGeneration = binding.bindingGeneration
		if (bindingGeneration == 1L) {
			val expected = spec.initialOwner
			if (expected == null) {
				if (owner != null) block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
				return
			}
			if (owner == null) block(SourceWriterTransitionBlocker.DESTINATION_OWNER_MISSING)
			if (owner?.owner != expected ||
				owner.ownerGeneration != SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
			) block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
			return
		}
		val previousBinding = bindingGeneration - 1L
		if (owner?.owner != spec.containedOwner ||
			owner.ownerGeneration !=
			SourceWriterGenerationContract.containedOwnerGeneration(previousBinding)
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

	private suspend fun promoteOwner(
		owner: SourceDestinationOwnerEntity?,
		binding: ExecutableSourceLaneBinding,
		updatedAtMs: Long,
	) {
		val canonicalGeneration =
			SourceWriterGenerationContract.canonicalOwnerGeneration(binding.bindingGeneration)
		val initialOwnerName = spec.initialOwner
		if (binding.bindingGeneration == 1L && initialOwnerName == null) {
			val inserted = database.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = spec.source.stableCode,
					destination = spec.destination,
					owner = spec.candidateOwner,
					ownerGeneration = canonicalGeneration,
					updatedAtMs = updatedAtMs,
				),
			)
			if (inserted < 0L) block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
			return
		}
		val initial = owner ?: block(SourceWriterTransitionBlocker.DESTINATION_OWNER_MISSING)
		val expectedOwner = if (binding.bindingGeneration == 1L) {
			requireNotNull(initialOwnerName)
		} else {
			spec.containedOwner
		}
		if (database.sourceDestinationOwnerDao().compareAndSetOwner(
				sourceKind = spec.source.stableCode,
				destination = spec.destination,
				expectedOwner = expectedOwner,
				expectedOwnerGeneration = initial.ownerGeneration,
				newOwner = spec.candidateOwner,
				newOwnerGeneration = canonicalGeneration,
				updatedAtMs = updatedAtMs,
			) != 1
		) block(SourceWriterTransitionBlocker.DESTINATION_OWNER_CHANGED)
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
		) block(SourceWriterTransitionBlocker.POSTCONDITION_FAILED)
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

/**
 * Existing-writer serialization required before a source-local candidate becomes canonical.
 *
 * Pressure must hold the PersistenceProcessor recovery/flush mutex, prove its in-memory Pressure
 * buffers empty, and prove no durable pending signal can still write under the old destination
 * owner. The owner/lane/rollout operation executes while that fence remains held. Steps' existing
 * quiescence method is not a valid substitute.
 */
interface LegacySourceWriterTransitionBoundary {
	suspend fun <T : Any> runIfQuiescent(
		source: SourceKind,
		operation: suspend () -> T,
	): T?

	companion object {
		val ALWAYS = object : LegacySourceWriterTransitionBoundary {
			override suspend fun <T : Any> runIfQuiescent(
				source: SourceKind,
				operation: suspend () -> T,
			): T = operation()
		}
	}
}

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
	if (current <= 0L) block(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
	return current - 1L
}

private fun nextRevision(current: Long): Long {
	if (current == Long.MAX_VALUE) block(SourceWriterTransitionBlocker.REVISION_EXHAUSTED)
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
