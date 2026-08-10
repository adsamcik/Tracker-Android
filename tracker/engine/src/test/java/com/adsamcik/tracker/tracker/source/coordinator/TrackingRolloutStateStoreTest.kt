package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
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
	fun `missing row initializes event-canonical ownership`() = runTest {
		store.load() shouldBe TrackingRolloutState.eventCanonical()
	}

	@Test
	fun `round trips event-canonical ownership`() = runTest {
		val expected = TrackingRolloutState.eventCanonical(revision = 3)

		store.save(expected, 1_000L)

		store.load() shouldBe expected
	}

	@Test
	fun `persisted compatibility rollout upgrades on load`() = runTest {
		store.save(TrackingRolloutState.legacy(revision = 3), 1_000L)

		store.load() shouldBe TrackingRolloutState.eventCanonical(revision = 4)
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
		val ingress = io.mockk.mockk<com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress>()
		val coordinator = AuthoritativeSessionCoordinator(
			database,
			RoomSourcePlanStore(database, SourcePlanCodec()),
			com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeRegistry(emptySet()),
			com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory(ingress),
			TrackingCoordinator(database, ingress, com.adsamcik.tracker.tracker.source.projection.ProjectionDispatcher(database, emptySet())),
			store,
		)
		val plan = com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision(
			revision = 1,
			planId = "guard-test",
			createdAtMs = 1,
			plans = mapOf(
				SourceKind.STEPS to com.adsamcik.tracker.tracker.source.model.StepsPlan(1, true, 0, 1_000, false),
			),
		)

		coordinator.start(
			SessionStartRequest(
				ownerToken = "guard-test",
				origin = SessionStartOrigin.MANUAL_FOREGROUND,
				plan = plan,
				rolloutRevision = 0,
				clockDomainId = "boot-1",
				foregroundCapabilityFlags = 0,
				wallTimeMs = 1,
				elapsedRealtimeNanos = 1,
			),
		).shouldBeInstanceOf<SessionStartResult.InvalidRollout>()
	}
}
