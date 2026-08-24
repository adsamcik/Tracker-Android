package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
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

		store.save(expected, 1_000L)

		store.load() shouldBe expected
	}

	@Test
	fun `loading one-source rollout does not promote unrelated sources`() = runTest {
		val expected = TrackingRolloutState.eventShadow(setOf(SourceKind.STEPS), revision = 3)

		store.save(expected, 1_000L)

		store.load() shouldBe expected
		store.load().sourceOwners.getValue(SourceKind.LOCATION) shouldBe SourceOwner.CONTAINED
	}

	@Test
	fun `loading one shadow projection does not force canonical writers`() = runTest {
		val expected = TrackingRolloutState.eventShadow(setOf(SourceKind.STEPS), revision = 3)

		store.save(expected, 1_000L)

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
}
