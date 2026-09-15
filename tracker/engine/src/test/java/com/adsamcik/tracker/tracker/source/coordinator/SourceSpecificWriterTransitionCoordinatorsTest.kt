package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
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
		val activeLegacyWriter = object : LegacySourceWriterQuiescence {
			override suspend fun <T : Any> runIfQuiescent(operation: suspend () -> T): T? = null
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
		rollout().isAcquisitionReachable(SourceKind.PRESSURE) shouldBe false
		database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		)?.owner shouldBe SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE
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
			.blocker shouldBe SourceWriterTransitionBlocker.REARM_BINDING_CONTRACT_UNAVAILABLE
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
		legacyWriterQuiescence: LegacySourceWriterQuiescence = LegacySourceWriterQuiescence.ALWAYS,
		requestDrain: (SourceKind) -> Unit = {},
	) = SourceWriterTransitionTestDependencies(
		startupGate = ReadyStartupGate,
		bootClockDomainProvider = BootClockDomainProvider { BOOT_ID },
		clock = FixedClock(fixedTimeMillis = 1_000L, fixedRealtimeNanos = 1_000L),
		legacyWriterQuiescence = legacyWriterQuiescence,
		requestDrain = requestDrain,
	)

	private object ReadyStartupGate : TrackingStartupGate {
		override val isReady: Boolean = true
		override val currentGeneration: Long = 1L

		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			TrackingStartupResult.Ready(false, 0L)
	}

	private companion object {
		const val BOOT_ID = "test-boot"
	}
}
