package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.TrackingRolloutStateEntity
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.throwables.shouldThrow
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
class TrackingRolloutStateStoreTest {
	private lateinit var database: AppDatabase
	private lateinit var store: RoomTrackingRolloutStateStore

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		store = RoomTrackingRolloutStateStore(database)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `missing row initializes contained without authorizing acquisition`() = runTest {
		store.load() shouldBe TrackingRolloutState.contained()
	}

	@Test
	fun `unreleased global canonical marker is contained as per-source shadow`() = runTest {
		database.trackingRolloutStateDao().save(
			TrackingRolloutStateEntity(
				revision = 7L,
				schemaVersion = 2,
				coordinatorMode = CoordinatorMode.EVENT.name,
				projectionMode = "EVENT_CANONICAL",
				sourceOwners = SourceKind.entries.joinToString(",") { source ->
					"${source.stableCode}:${SourceOwner.EVENT.name}"
				},
				semanticSettingsEnabled = true,
				batteryEstimateMode = BatteryEstimateMode.SOURCE_PLAN_QUALITATIVE.name,
				updatedAtMs = 1_000L,
			),
		)

		val contained = store.load()
		contained.revision shouldBe 8L
		contained.sourceOwners.values.toSet() shouldBe setOf(SourceOwner.CONTAINED)
		contained.productProjectionStages.values.toSet() shouldBe
			setOf(ProductProjectionStage.LEGACY_CANONICAL)
	}

	@Test
	fun `round trips one source shadow stage without promoting unrelated writers`() = runTest {
		val expected = TrackingRolloutState.eventShadow(setOf(SourceKind.STEPS), revision = 3)

		val activation = installSteps()

		activation.rollout shouldBe expected
		activation.lane.sourceKind shouldBe SourceKind.STEPS.stableCode
		activation.lane.projectionId shouldBe STEPS_OUTPUT_CONTRACT
		activation.lane.projectionVersion shouldBe 1
		activation.lane.activatedRolloutRevision shouldBe 3L
		activation.lane.activationOrdinal shouldBe 1L
		activation.lane.contiguousAdmissionOrdinal shouldBe 0L
		activation.lane.retentionRequired shouldBe true
		database.sourceProjectionStateDao().isProductLaneReachable(
			SourceKind.STEPS.stableCode,
			ProductProjectionStage.EVENT_SHADOW.name,
			3L,
		) shouldBe true
		database.sourceProjectionStateDao().isProductLaneReachable(
			SourceKind.STEPS.stableCode,
			ProductProjectionStage.EVENT_CANONICAL.name,
			3L,
		) shouldBe false
		database.sourceProjectionStateDao().isProductLaneReachable(
			SourceKind.STEPS.stableCode,
			ProductProjectionStage.EVENT_SHADOW.name,
			2L,
		) shouldBe false
		store.load() shouldBe expected
	}

	@Test
	fun `capture rollout cannot be saved before its exact source lane is installed`() = runTest {
		shouldThrow<IllegalArgumentException> {
			store.save(
				TrackingRolloutState.eventShadow(setOf(SourceKind.STEPS), revision = 3),
				1_000L,
			)
		}

		database.trackingRolloutStateDao().get() shouldBe null
	}

	@Test
	fun `persisted capture marker without a durable lane is repaired before provider use`() = runTest {
		database.trackingRolloutStateDao().save(eventShadowEntity(revision = 7L))

		store.load() shouldBe TrackingRolloutState.contained(revision = 8L)
		database.trackingRolloutStateDao().get()?.revision shouldBe 8L
	}

	@Test
	fun `lane with an uninitialized source cursor cannot authorize acquisition`() = runTest {
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				projectionId = STEPS_OUTPUT_CONTRACT,
				projectionVersion = 1,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
				activatedRolloutRevision = 7L,
				activationOrdinal = 5L,
				contiguousAdmissionOrdinal = 3L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1_000L,
				updatedAtMs = 1_000L,
			),
		)
		database.trackingRolloutStateDao().save(eventShadowEntity(revision = 7L))

		store.load() shouldBe TrackingRolloutState.contained(revision = 8L)
	}

	@Test
	fun `one source and one output contract cannot acquire competing writer generations`() = runTest {
		installSteps()

		shouldThrow<IllegalArgumentException> {
			store.installAndActivateShadowLane(
				source = SourceKind.STEPS,
				projectionId = STEPS_OUTPUT_CONTRACT,
				projectionVersion = 2,
				rolloutRevision = 4,
				updatedAtMs = 2_000L,
			)
		}
		shouldThrow<IllegalArgumentException> {
			store.installAndActivateShadowLane(
				source = SourceKind.PRESSURE,
				projectionId = STEPS_OUTPUT_CONTRACT,
				projectionVersion = 1,
				rolloutRevision = 4,
				updatedAtMs = 2_000L,
			)
		}

		store.load() shouldBe TrackingRolloutState.eventShadow(
			setOf(SourceKind.STEPS),
			revision = 3,
		)
		database.sourceProjectionStateDao().activeProductLanes().size shouldBe 1
	}

	@Test
	fun `global projection identity cannot be reused as a source writer generation`() = runTest {
		database.sourceProjectionStateDao().register(
			SourceProjectionRegistrationEntity(
				projectionId = STEPS_OUTPUT_CONTRACT,
				projectionVersion = 1,
				activationOrdinal = 1,
				retentionRequired = true,
				status = "ACTIVE",
				createdAtMs = 500L,
			),
		)

		shouldThrow<IllegalArgumentException> { installSteps() }
		database.sourceProjectionStateDao().activeProductLanes() shouldBe emptyList()
		database.trackingRolloutStateDao().get() shouldBe null
	}

	@Test
	fun `loading one-source rollout does not promote unrelated sources`() = runTest {
		val expected = TrackingRolloutState.eventShadow(setOf(SourceKind.STEPS), revision = 3)

		installSteps()

		store.load() shouldBe expected
		store.load().sourceOwners.getValue(SourceKind.LOCATION) shouldBe SourceOwner.CONTAINED
	}

	@Test
	fun `loading one shadow projection does not force canonical writers`() = runTest {
		val expected = TrackingRolloutState.eventShadow(setOf(SourceKind.STEPS), revision = 3)

		installSteps()

		store.load() shouldBe expected
		store.load().productProjectionStages.getValue(SourceKind.LOCATION) shouldBe
			ProductProjectionStage.LEGACY_CANONICAL
	}

	@Test
	fun `persisted compatibility rollout upgrades on load`() = runTest {
		store.save(TrackingRolloutState.legacy(revision = 3), 1_000L)

		store.load() shouldBe TrackingRolloutState.contained(revision = 4)
	}

	@Test
	fun `rollout revision cannot move backward or be overwritten`() = runTest {
		store.save(TrackingRolloutState.legacy(revision = 2), 1_000L)

		shouldThrow<IllegalArgumentException> {
			store.save(TrackingRolloutState.legacy(revision = 2), 2_000L)
		}
	}

	@Test
	fun `event coordinator refuses a source that persisted rollout does not own`() = runTest {
		RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", 1L, 1L)
		}.bootstrapFromLegacy(TrackingParamsState(legacySettingsMigrationCompleted = true))
		val ingress = io.mockk.mockk<com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress>()
		val coordinator = AuthoritativeSessionCoordinator(
			database,
			RoomSourcePlanStore(database, SourcePlanCodec()),
			com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeRegistry(emptySet()),
			com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory(ingress),
			TrackingCoordinator(database, ingress, com.adsamcik.tracker.tracker.source.projection.ProjectionDispatcher(database, emptySet())),
			com.adsamcik.tracker.tracker.source.projection.ActivityAutomaticStartActionRepository(
				database,
				ReadyTrackingStartupGate,
			),
			NoOpActivityAutomationDrainSignal,
			io.mockk.mockk(relaxed = true),
			store,
		)
		val plan = com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision(
			revision = 1,
			planId = "guard-test",
			createdAtMs = 1,
			plans = mapOf(
				SourceKind.STEPS to com.adsamcik.tracker.tracker.source.model.StepsPlan(1, true, 60_000, 15_000, false),
			),
			sourcePolicyRevision = 1,
		)

		coordinator.start(
			SessionStartRequest(
				ownerToken = "guard-test",
				origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
				plan = plan,
				rolloutRevision = 0,
				clockDomainId = "boot-1",
				foregroundCapabilityFlags = 0,
				wallTimeMs = 1,
				elapsedRealtimeNanos = 1,
				zoneId = "Europe/Prague",
			),
		).shouldBeInstanceOf<SessionStartResult.InvalidRollout>()
	}

	private suspend fun installSteps(): SourceProductLaneActivation =
		store.installAndActivateShadowLane(
			source = SourceKind.STEPS,
			projectionId = STEPS_OUTPUT_CONTRACT,
			projectionVersion = 1,
			rolloutRevision = 3,
			updatedAtMs = 1_000L,
		)

	private fun eventShadowEntity(revision: Long) = TrackingRolloutStateEntity(
		revision = revision,
		schemaVersion = TrackingRolloutState.CURRENT_SCHEMA_VERSION,
		coordinatorMode = CoordinatorMode.EVENT.name,
		projectionMode = SourceKind.entries.joinToString(",") { source ->
			val stage = if (source == SourceKind.STEPS) {
				ProductProjectionStage.EVENT_SHADOW
			} else {
				ProductProjectionStage.LEGACY_CANONICAL
			}
			"${source.stableCode}:${stage.name}"
		},
		sourceOwners = SourceKind.entries.joinToString(",") { source ->
			val owner = if (source == SourceKind.STEPS) SourceOwner.EVENT else SourceOwner.CONTAINED
			"${source.stableCode}:${owner.name}"
		},
		semanticSettingsEnabled = true,
		batteryEstimateMode = BatteryEstimateMode.SOURCE_PLAN_QUALITATIVE.name,
		updatedAtMs = 1_000L,
	)

	private companion object {
		const val STEPS_OUTPUT_CONTRACT = "steps-interval"
	}
}
