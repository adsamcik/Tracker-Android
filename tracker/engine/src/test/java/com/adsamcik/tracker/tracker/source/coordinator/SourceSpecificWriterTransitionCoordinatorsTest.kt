package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterCompletedFullDeletion
import com.adsamcik.tracker.shared.base.database.data.SourceWriterFullDeletionRearmAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceWriterHistoricalProvenance
import com.adsamcik.tracker.shared.base.database.data.SourceWriterRearmAuthorityInput
import com.adsamcik.tracker.shared.base.database.data.SourceWriterRearmCapability
import com.adsamcik.tracker.shared.base.database.data.SourceWriterRearmDeletionSupport
import com.adsamcik.tracker.shared.base.database.data.SourceWriterRearmFactsSupport
import com.adsamcik.tracker.shared.base.database.data.SourceWriterRearmMaintenanceSupport
import com.adsamcik.tracker.shared.base.database.data.SourceWriterRearmReaderSupport
import com.adsamcik.tracker.shared.base.database.data.SourceWriterRearmSupportDeclaration
import com.adsamcik.tracker.shared.base.database.data.SourceWriterRearmTransferSupport
import com.adsamcik.tracker.shared.base.database.data.SourceWriterRearmWriterSupport
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.tracker.pipeline.persistence.ExclusiveTrackingPersistenceLifecycleLease
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceSpecificWriterTransitionCoordinatorsTest {
	private lateinit var database: AppDatabase
	private lateinit var catalog: ExecutableSourceLaneCatalog

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		catalog = ExecutableSourceLaneCatalog()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `Activity install remains dormant until explicit atomic activation`() = runTest {
		seedContainedRollout()
		seedInitialOwner(SourceWriterTransitionSpec.ACTIVITY)
		val coordinator = ActivityCapturedFactWriterTransitionCoordinator(
			database,
			catalog,
			dependencies(),
		)

		coordinator.installInertCandidate(1L, 10L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
		rollout().isAcquisitionReachable(SourceKind.ACTIVITY) shouldBe false

		val activated = coordinator.activateCandidate(2L, 11L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()

		activated.ownerGeneration shouldBe SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
		assertCanonical(SourceWriterTransitionSpec.ACTIVITY, expectedRevision = 3L)
	}

	@Test
	fun `Pressure activation is blocked while the legacy persistence owner is not quiescent`() = runTest {
		seedContainedRollout()
		seedInitialOwner(SourceWriterTransitionSpec.PRESSURE)
		val requestedSources = mutableListOf<SourceKind>()
		val activeLegacyWriter = object : LegacySourceWriterTransitionBoundary {
			override suspend fun <T : Any> runIfQuiescent(
				source: SourceKind,
				operation: suspend () -> T,
			): T? {
				requestedSources += source
				return null
			}
		}
		val coordinator = PressureSessionFactWriterTransitionCoordinator(
			database,
			catalog,
			dependencies(legacyWriterQuiescence = activeLegacyWriter),
		)
		coordinator.installInertCandidate(1L, 10L)

		val blocked = coordinator.activateCandidate(2L, 11L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Blocked>()

		blocked.blocker shouldBe SourceWriterTransitionBlocker.LEGACY_WRITER_NOT_QUIESCENT
		requestedSources shouldBe listOf(SourceKind.PRESSURE)
		rollout().isAcquisitionReachable(SourceKind.PRESSURE) shouldBe false
		database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		)?.owner shouldBe SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE
	}

	@Test
	fun `Pressure activation waits outside coordinator lease until live teardown releases persistence`() =
		runTest {
			seedContainedRollout()
			seedInitialOwner(SourceWriterTransitionSpec.PRESSURE)
			val persistenceLease = ExclusiveTrackingPersistenceLifecycleLease()
			val persistence = mockk<PersistenceProcessor>()
			every { persistence.isPipelineActiveForPersistenceLifecycle() } returns false
			every { persistence.hasUnrecoverablePersistenceStateForLifecycleFence() } returns false
			every { persistence.hasUnsettledPersistenceStateForPressureFence() } returns false
			coEvery { persistence.drainOrphanedSignals() } returns true
			val coordinator = PressureSessionFactWriterTransitionCoordinator(
				database,
				catalog,
				dependencies(
					legacyWriterQuiescence =
						PersistenceLegacySourceWriterTransitionBoundary(
							persistence,
							persistenceLease,
						),
				),
			)
			coordinator.installInertCandidate(1L, 10L)
				.shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
			val live = persistenceLease.acquireLivePipeline()
			val activation = async {
				coordinator.activateCandidate(2L, 11L)
			}

			yield()
			val leaseDao = database.sourceProjectionStateDao()
			leaseDao.acquireOrRenewLease(
				leaseName = SESSION_COORDINATOR_LEASE,
				ownerToken = "teardown",
				bootId = BOOT_ID,
				nowMs = 1_000L,
				expiresAtMs = 2_000L,
				nowElapsedNanos = 1_000L,
				expiresElapsedNanos = 2_000L,
			) shouldBe 1
			val teardownLease = requireNotNull(leaseDao.lease(SESSION_COORDINATOR_LEASE))
			teardownLease.ownerToken shouldBe "teardown"
			leaseDao.releaseLease(
				SESSION_COORDINATOR_LEASE,
				"teardown",
				BOOT_ID,
				teardownLease.generation,
				1_000L,
				1_000L,
			) shouldBe 1
			live.release()

			activation.await().shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
			assertCanonical(SourceWriterTransitionSpec.PRESSURE, expectedRevision = 3L)
		}

	@Test
	fun `Activity activation cancellation while waiting never acquires coordinator lease`() = runTest {
		seedContainedRollout()
		seedInitialOwner(SourceWriterTransitionSpec.ACTIVITY)
		val persistenceLease = ExclusiveTrackingPersistenceLifecycleLease()
		val persistence = mockk<PersistenceProcessor>()
		every { persistence.isPipelineActiveForPersistenceLifecycle() } returns false
		every { persistence.hasUnrecoverablePersistenceStateForLifecycleFence() } returns false
		every { persistence.hasUnsettledPersistenceStateForPressureFence() } returns false
		coEvery { persistence.drainOrphanedSignals() } returns true
		val coordinator = ActivityCapturedFactWriterTransitionCoordinator(
			database,
			catalog,
			dependencies(
				legacyWriterQuiescence =
					PersistenceLegacySourceWriterTransitionBoundary(
						persistence,
						persistenceLease,
					),
			),
		)
		coordinator.installInertCandidate(1L, 10L)
		val live = persistenceLease.acquireLivePipeline()
		val activation = async {
			coordinator.activateCandidate(2L, 11L)
		}

		yield()
		activation.cancelAndJoin()
		database.sourceProjectionStateDao().acquireOrRenewLease(
			leaseName = SESSION_COORDINATOR_LEASE,
			ownerToken = "activity-teardown",
			bootId = BOOT_ID,
			nowMs = 1_000L,
			expiresAtMs = 2_000L,
			nowElapsedNanos = 1_000L,
			expiresElapsedNanos = 2_000L,
		) shouldBe 1
		live.release()
		rollout().isAcquisitionReachable(SourceKind.ACTIVITY) shouldBe false
		io.mockk.coVerify(exactly = 0) { persistence.drainOrphanedSignals() }
	}

	@Test
	fun `Pressure activation rechecks rollout revision after persistence wait`() = runTest {
		seedContainedRollout()
		seedInitialOwner(SourceWriterTransitionSpec.PRESSURE)
		val persistenceLease = ExclusiveTrackingPersistenceLifecycleLease()
		val persistence = mockk<PersistenceProcessor>()
		every { persistence.isPipelineActiveForPersistenceLifecycle() } returns false
		every { persistence.hasUnrecoverablePersistenceStateForLifecycleFence() } returns false
		every { persistence.hasUnsettledPersistenceStateForPressureFence() } returns false
		coEvery { persistence.drainOrphanedSignals() } returns true
		val coordinator = PressureSessionFactWriterTransitionCoordinator(
			database,
			catalog,
			dependencies(
				legacyWriterQuiescence =
					PersistenceLegacySourceWriterTransitionBoundary(
						persistence,
						persistenceLease,
					),
			),
		)
		coordinator.installInertCandidate(1L, 10L)
		val live = persistenceLease.acquireLivePipeline()
		val activation = async {
			coordinator.activateCandidate(2L, 11L)
		}
		yield()
		val changed = rollout().copy(revision = 3L)
		database.trackingRolloutStateDao().save(changed.toEntity(10L))
		live.release()

		activation.await().shouldBeInstanceOf<SourceWriterTransitionResult.Blocked>()
			.blocker shouldBe SourceWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED
		database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		)?.owner shouldBe SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE
	}

	@Test
	fun `Pressure rollback uses a contained owner rather than advertising legacy generation three`() =
		runTest {
			seedContainedRollout()
			seedInitialOwner(SourceWriterTransitionSpec.PRESSURE)
			val coordinator = PressureSessionFactWriterTransitionCoordinator(
				database,
				catalog,
				dependencies(),
			)
			coordinator.installInertCandidate(1L, 10L)
			coordinator.activateCandidate(2L, 11L)
			coordinator.beginCandidateRollback(3L, 12L)
			coordinator.completeCandidateRollback(4L, 0L, 13L)

			database.sourceDestinationOwnerDao().get(
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			)?.let { owner ->
				owner.owner shouldBe CONTAINED_PRESSURE_SESSION_OWNER
				owner.ownerGeneration shouldBe 3L
			}
			rollout().isAcquisitionReachable(SourceKind.PRESSURE) shouldBe false
		}

	@Test
	fun `Wi-Fi first activation installs exact owner only inside the canonical transaction`() = runTest {
		seedContainedRollout()
		val coordinator = WifiSessionFactWriterTransitionCoordinator(database, catalog, dependencies())

		coordinator.installInertCandidate(1L, 10L)
		database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI,
		) shouldBe null

		coordinator.activateCandidate(2L, 11L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()

		assertCanonical(SourceWriterTransitionSpec.WIFI, expectedRevision = 3L)
	}

	@Test
	fun `Cell rollback contains capture requests drains the exact lane and cannot silently rearm`() = runTest {
		seedContainedRollout()
		val drains = mutableListOf<SourceKind>()
		val coordinator = CellSessionFactWriterTransitionCoordinator(
			database,
			catalog,
			dependencies(requestDrain = { source -> drains += source }),
		)
		coordinator.installInertCandidate(1L, 10L)
		coordinator.activateCandidate(2L, 11L)

		val begun = coordinator.beginCandidateRollback(3L, 12L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
		begun.cutoffAdmissionOrdinal shouldBe 0L
		drains shouldBe listOf(SourceKind.CELL)
		rollout().isAcquisitionReachable(SourceKind.CELL) shouldBe false

		coordinator.completeCandidateRollback(4L, 0L, 13L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
		database.sourceProjectionStateDao().activeProductLane(SourceKind.CELL.stableCode) shouldBe null
		database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_CELL,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
		)?.let { owner ->
			owner.owner shouldBe CONTAINED_CELL_SESSION_OWNER
			owner.ownerGeneration shouldBe 3L
		}

		coordinator.rearmAfterFullDeletion(14L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Blocked>()
			.blocker shouldBe SourceWriterTransitionBlocker.FULL_DELETION_PROOF_UNAVAILABLE
	}

	@Test
	fun `monotonic rearm contract supports repeated owner and binding generations`() = runTest {
		val firstDeletion = completedDeletion(generation = 1L)
		val secondDeletion = completedDeletion(generation = 2L)
		val authority = ExactFullDeletionAuthority(firstDeletion, secondDeletion)
		val rearmCatalog = cellRearmCatalog(
			declaration = cellRearmDeclaration(authority = authority),
		)
		RoomTrackingRolloutStateStore(database, rearmCatalog).load() shouldBe
			TrackingRolloutState.contained(revision = 1L)
		val coordinator = CellSessionFactWriterTransitionCoordinator(
			database,
			rearmCatalog,
			dependencies(),
		)

		coordinator.installInertCandidate(1L, 10L)
		coordinator.activateCandidate(2L, 11L)
		coordinator.beginCandidateRollback(3L, 12L)
		coordinator.completeCandidateRollback(4L, 0L, 13L)
		coordinator.rearmAfterFullDeletion(firstDeletion, 14L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
		database.sourceProjectionStateDao().activeProductLane(SourceKind.CELL.stableCode)
			?.bindingGeneration shouldBe 2L
		coordinator.activateCandidate(5L, 15L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
		database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_CELL,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
		)?.ownerGeneration shouldBe 4L

		coordinator.beginCandidateRollback(6L, 16L)
		coordinator.completeCandidateRollback(7L, 0L, 17L)
		coordinator.rearmAfterFullDeletion(secondDeletion, 18L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
		database.sourceProjectionStateDao().activeProductLane(SourceKind.CELL.stableCode)
			?.bindingGeneration shouldBe 3L
		database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_CELL,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
		)?.ownerGeneration shouldBe 5L
		authority.inputs.map { it.completedFullDeletion } shouldBe
			listOf(firstDeletion, secondDeletion)
	}

	@Test
	fun `catalog rejects duplicate or conflicting source rearm declarations`() {
		val authority = ExactFullDeletionAuthority(completedDeletion())
		val exact = MonotonicRearmSourceWriterSupport(
			SourceKind.CELL,
			cellRearmDeclaration(authority = authority),
		)
		val conflicting = exact.copy(
			declaration = cellRearmDeclaration(
				authority = authority,
				capability = CELL_REARM_CAPABILITY.copy(destination = "WRONG_DESTINATION"),
			),
		)

		shouldThrow<IllegalArgumentException> {
			ExecutableSourceLaneCatalog(
				setOf(ExecutableSourceLaneCatalog.CELL_SESSION_FACTS),
				listOf(exact, exact),
			)
		}
		shouldThrow<IllegalArgumentException> {
			ExecutableSourceLaneCatalog(
				setOf(ExecutableSourceLaneCatalog.CELL_SESSION_FACTS),
				listOf(exact, conflicting),
			)
		}
	}

	@Test
	fun `catalog rejects a declaration whose exact source identity is wrong`() {
		val authority = ExactFullDeletionAuthority(completedDeletion())
		val wrongCapabilities = listOf(
			CELL_REARM_CAPABILITY.copy(destination = "WRONG_DESTINATION"),
			CELL_REARM_CAPABILITY.copy(candidateOwner = "WRONG_CANDIDATE"),
			CELL_REARM_CAPABILITY.copy(containedOwner = "WRONG_CONTAINED"),
			CELL_REARM_CAPABILITY.copy(projectionId = "wrong-projection"),
			CELL_REARM_CAPABILITY.copy(projectionVersion = 2),
			CELL_REARM_CAPABILITY.copy(
				canonicalStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			),
		)

		wrongCapabilities.forEach { capability ->
			shouldThrow<IllegalArgumentException> {
				cellRearmCatalog(
					cellRearmDeclaration(authority = authority, capability = capability),
				)
			}
		}
	}

	@Test
	fun `partial six surface support blocks before full deletion authority`() = runTest {
		val deletion = completedDeletion()
		val authority = ExactFullDeletionAuthority(deletion)
		val rearmCatalog = cellRearmCatalog(
			cellRearmDeclaration(
				authority = authority,
				transferSupported = false,
			),
		)
		val coordinator = prepareCellRollback(rearmCatalog)

		coordinator.rearmAfterFullDeletion(deletion, 14L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Blocked>()
			.blocker shouldBe SourceWriterTransitionBlocker.REARM_BINDING_CONTRACT_UNAVAILABLE
		authority.inputs shouldBe emptyList()
	}

	@Test
	fun `full deletion receipt mismatch fails closed in the real coordinator`() = runTest {
		val authorizedDeletion = completedDeletion(generation = 7L)
		val presentedDeletion = completedDeletion(generation = 8L)
		val authority = ExactFullDeletionAuthority(authorizedDeletion)
		val rearmCatalog = cellRearmCatalog(
			cellRearmDeclaration(authority = authority),
		)
		val coordinator = prepareCellRollback(rearmCatalog)

		coordinator.rearmAfterFullDeletion(presentedDeletion, 14L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Blocked>()
			.blocker shouldBe SourceWriterTransitionBlocker.FULL_DELETION_PROOF_UNAVAILABLE
		database.sourceProjectionStateDao().activeProductLane(SourceKind.CELL.stableCode) shouldBe null
	}

	@Test
	fun `retired writer identity mismatch blocks rearm before source authority`() = runTest {
		val deletion = completedDeletion()
		val authority = ExactFullDeletionAuthority(deletion)
		val rearmCatalog = cellRearmCatalog(
			cellRearmDeclaration(authority = authority),
		)
		val coordinator = prepareCellRollback(rearmCatalog)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_product_projection_lane SET projection_id = ? WHERE source_kind = ?",
			arrayOf<Any>("wrong-cell-projection", SourceKind.CELL.stableCode),
		)

		coordinator.rearmAfterFullDeletion(deletion, 14L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Blocked>()
			.blocker shouldBe SourceWriterTransitionBlocker.BINDING_NOT_EXECUTABLE
		authority.inputs shouldBe emptyList()
	}

	@Test
	fun `last representable contained owner blocks successor overflow before authority`() = runTest {
		val deletion = completedDeletion()
		val authority = ExactFullDeletionAuthority(deletion)
		val rearmCatalog = cellRearmCatalog(
			cellRearmDeclaration(authority = authority),
		)
		RoomTrackingRolloutStateStore(database, rearmCatalog).load()
		val finalBindingGeneration = Long.MAX_VALUE / 2L
		seedRetiredCellGeneration(
			bindingGeneration = finalBindingGeneration,
			containedOwnerGeneration = Long.MAX_VALUE,
		)
		val coordinator = CellSessionFactWriterTransitionCoordinator(
			database,
			rearmCatalog,
			dependencies(),
		)

		coordinator.rearmAfterFullDeletion(deletion, 20L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Blocked>()
			.blocker shouldBe SourceWriterTransitionBlocker.REARM_BINDING_CONTRACT_UNAVAILABLE
		authority.inputs shouldBe emptyList()
		database.sourceProjectionStateDao().activeProductLane(SourceKind.CELL.stableCode) shouldBe null
	}

	@Test
	fun `rollback AlreadyApplied rejects wrong canonical stage or activation revision`() = runTest {
		seedContainedRollout()
		val coordinator = CellSessionFactWriterTransitionCoordinator(
			database,
			catalog,
			dependencies(),
		)
		coordinator.installInertCandidate(1L, 10L)
		coordinator.activateCandidate(2L, 11L)
		coordinator.beginCandidateRollback(3L, 12L)

		coordinator.beginCandidateRollback(3L, 13L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.AlreadyApplied>()
		updateCellLaneIdentity(
			productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			activatedRolloutRevision = 3L,
		)
		coordinator.beginCandidateRollback(3L, 14L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Blocked>()
			.blocker shouldBe SourceWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED

		updateCellLaneIdentity(
			productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
			activatedRolloutRevision = 2L,
		)
		coordinator.beginCandidateRollback(3L, 15L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Blocked>()
			.blocker shouldBe SourceWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED
	}

	@Test
	fun `corrupt fenced lane cannot impersonate an already begun rollback`() = runTest {
		seedContainedRollout()
		val coordinator = WifiSessionFactWriterTransitionCoordinator(database, catalog, dependencies())
		coordinator.installInertCandidate(1L, 10L)
		coordinator.activateCandidate(2L, 11L)
		coordinator.beginCandidateRollback(3L, 12L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_product_projection_lane SET projection_id = ? WHERE source_kind = ?",
			arrayOf<Any>("corrupt-wifi-writer", SourceKind.WIFI.stableCode),
		)

		coordinator.beginCandidateRollback(3L, 13L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Blocked>()
			.blocker shouldBe SourceWriterTransitionBlocker.BINDING_NOT_EXECUTABLE
	}

	@Test
	fun `an unexpired lifecycle lease fences every source-specific transition`() = runTest {
		seedContainedRollout()
		seedInitialOwner(SourceWriterTransitionSpec.ACTIVITY)
		database.sourceProjectionStateDao().insertLeaseIfAbsent(
			SourceCoordinatorLeaseEntity(
				leaseName = "tracking-session-coordinator",
				ownerToken = "active-session-owner",
				acquiredAtMs = 500L,
				expiresAtMs = 2_000L,
				bootId = BOOT_ID,
				generation = 7L,
				acquiredElapsedRealtimeNanos = 500L,
				expiresElapsedRealtimeNanos = 2_000L,
			),
		)
		val coordinator = ActivityCapturedFactWriterTransitionCoordinator(
			database,
			catalog,
			dependencies(),
		)

		coordinator.installInertCandidate(1L, 10L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Busy>()

		database.sourceProjectionStateDao().activeProductLane(SourceKind.ACTIVITY.stableCode) shouldBe null
		rollout().isAcquisitionReachable(SourceKind.ACTIVITY) shouldBe false
	}

	private suspend fun seedContainedRollout() {
		RoomTrackingRolloutStateStore(database, catalog).load() shouldBe
			TrackingRolloutState.contained(revision = 1L)
	}

	private suspend fun seedInitialOwner(spec: SourceWriterTransitionSpec) {
		val owner = requireNotNull(spec.initialOwner)
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = spec.source.stableCode,
				destination = spec.destination,
				owner = owner,
				ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
				updatedAtMs = 0L,
			),
		)
	}

	private suspend fun prepareCellRollback(
		rearmCatalog: ExecutableSourceLaneCatalog,
	): CellSessionFactWriterTransitionCoordinator {
		RoomTrackingRolloutStateStore(database, rearmCatalog).load() shouldBe
			TrackingRolloutState.contained(revision = 1L)
		val coordinator = CellSessionFactWriterTransitionCoordinator(
			database,
			rearmCatalog,
			dependencies(),
		)
		coordinator.installInertCandidate(1L, 10L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
		coordinator.activateCandidate(2L, 11L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
		coordinator.beginCandidateRollback(3L, 12L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
		coordinator.completeCandidateRollback(4L, 0L, 13L)
			.shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
		return coordinator
	}

	private suspend fun seedRetiredCellGeneration(
		bindingGeneration: Long,
		containedOwnerGeneration: Long,
	) {
		database.trackingRolloutStateDao().save(
			TrackingRolloutState.contained(revision = 19L).toEntity(updatedAtMs = 1L),
		)
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceKind.CELL.stableCode,
				bindingGeneration = bindingGeneration,
				projectionId = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				projectionVersion = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				captureModeMask =
					ExecutableSourceLaneCatalog.CELL_SESSION_FACTS.captureModeMask,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = 18L,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				captureAdmissionCutoffOrdinal = 0L,
				retentionRequired = false,
				status = SourceProductProjectionLaneEntity.STATUS_RETIRED,
				terminalDisposition =
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
				terminalAtMs = 1L,
				installedAtMs = 0L,
				updatedAtMs = 1L,
			),
		)
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceKind.CELL.stableCode,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
				owner = SourceDestinationOwnerEntity.OWNER_CONTAINED_CELL_SESSION_FACTS,
				ownerGeneration = containedOwnerGeneration,
				updatedAtMs = 1L,
			),
		)
	}

	private fun updateCellLaneIdentity(
		productStage: String,
		activatedRolloutRevision: Long,
	) {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_product_projection_lane " +
				"SET product_stage = ?, activated_rollout_revision = ? WHERE source_kind = ?",
			arrayOf<Any>(productStage, activatedRolloutRevision, SourceKind.CELL.stableCode),
		)
	}

	private suspend fun assertCanonical(
		spec: SourceWriterTransitionSpec,
		expectedRevision: Long,
	) {
		val owner = requireNotNull(
			database.sourceDestinationOwnerDao().get(spec.source.stableCode, spec.destination),
		)
		owner.owner shouldBe spec.candidateOwner
		owner.ownerGeneration shouldBe SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
		val lane = requireNotNull(
			database.sourceProjectionStateDao().activeProductLane(spec.source.stableCode),
		)
		lane.bindingGeneration shouldBe spec.binding.bindingGeneration
		lane.projectionId shouldBe spec.binding.projectionId
		lane.projectionVersion shouldBe spec.binding.projectionVersion
		lane.productStage shouldBe SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL
		lane.activatedRolloutRevision shouldBe expectedRevision
		rollout().let { state ->
			state.revision shouldBe expectedRevision
			state.isCaptureReachable(
				spec.source,
				CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
			) shouldBe true
			SourceKind.entries.filter { it != spec.source }.all { source ->
				!state.isAcquisitionReachable(source)
			} shouldBe true
		}
	}

	private suspend fun rollout(): TrackingRolloutState =
		requireNotNull(database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull())

	private fun dependencies(
		legacyWriterQuiescence: LegacySourceWriterTransitionBoundary =
			LegacySourceWriterTransitionBoundary.ALWAYS,
		requestDrain: (SourceKind) -> Unit = {},
	) = SourceWriterTransitionTestDependencies(
		startupGate = ReadyStartupGate,
		bootClockDomainProvider = BootClockDomainProvider { BOOT_ID },
		clock = FixedClock(fixedTimeMillis = 1_000L, fixedRealtimeNanos = 1_000L),
		legacyWriterQuiescence = legacyWriterQuiescence,
		requestDrain = requestDrain,
	)

	private fun completedDeletion(
		generation: Long = 1L,
	): SourceWriterCompletedFullDeletion = SourceWriterCompletedFullDeletion(
		collectedDataEpoch = generation,
		sourceDeletionGeneration = generation,
		sourceEvidenceRevision = generation,
		deletedSourceEventHighWaterOrdinal = generation - 1L,
		completedAtMs = generation,
	)

	private fun cellRearmCatalog(
		declaration: SourceWriterRearmSupportDeclaration,
	): ExecutableSourceLaneCatalog = ExecutableSourceLaneCatalog(
		bindings = setOf(ExecutableSourceLaneCatalog.CELL_SESSION_FACTS),
		monotonicRearmSupport = listOf(
			MonotonicRearmSourceWriterSupport(SourceKind.CELL, declaration),
		),
	)

	private fun cellRearmDeclaration(
		authority: SourceWriterFullDeletionRearmAuthority,
		capability: SourceWriterRearmCapability = CELL_REARM_CAPABILITY,
		transferSupported: Boolean = true,
	): SourceWriterRearmSupportDeclaration {
		val supportsHistorical = { provenance: SourceWriterHistoricalProvenance ->
			capability.bindingForHistoricalProvenance(provenance) != null
		}
		return SourceWriterRearmSupportDeclaration(
			capability = capability,
			writer = SourceWriterRearmWriterSupport { binding ->
				binding == capability.binding(binding.bindingGeneration)
			},
			facts = SourceWriterRearmFactsSupport(supportsHistorical),
			readers = SourceWriterRearmReaderSupport(supportsHistorical),
			maintenance = SourceWriterRearmMaintenanceSupport(supportsHistorical),
			transfer = SourceWriterRearmTransferSupport { provenance ->
				transferSupported && supportsHistorical(provenance)
			},
			deletion = SourceWriterRearmDeletionSupport(supportsHistorical),
			fullDeletionAuthority = authority,
		)
	}

	private class ExactFullDeletionAuthority(
		vararg accepted: SourceWriterCompletedFullDeletion,
	) : SourceWriterFullDeletionRearmAuthority {
		private val accepted = accepted.toMutableList()
		val inputs = mutableListOf<SourceWriterRearmAuthorityInput>()

		override suspend fun <T : Any> runIfAuthorized(
			input: SourceWriterRearmAuthorityInput,
			operation: suspend () -> T,
		): T? {
			inputs += input
			if (!accepted.remove(input.completedFullDeletion)) return null
			return operation()
		}
	}

	private object ReadyStartupGate : TrackingStartupGate {
		override val isReady: Boolean = true
		override val currentGeneration: Long = 1L

		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			TrackingStartupResult.Ready(false, 0L)
	}

	private companion object {
		const val BOOT_ID = "test-boot"
		const val SESSION_COORDINATOR_LEASE = "tracking-session-coordinator"
		val CELL_REARM_CAPABILITY = SourceWriterRearmCapability(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
			destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
			candidateOwner = SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS,
			containedOwner = SourceDestinationOwnerEntity.OWNER_CONTAINED_CELL_SESSION_FACTS,
			projectionId = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			projectionVersion = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			canonicalStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		)
	}
}
