package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.DomainEventDao
import com.adsamcik.tracker.shared.base.database.data.DomainEventEntity
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies JSON serialization round-trip for all [DomainEvent] subtypes
 * through [DefaultDomainEventRepository]'s persist/getUnconsumed public API.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultDomainEventRepositoryTest {

	private val dao: DomainEventDao = mockk()
	private val repo = DefaultDomainEventRepository(dao)

	@Before
	fun setUp() {
		clearMocks(dao)
	}

	private suspend fun verifyRoundTrip(event: DomainEvent) {
		val entitySlot = slot<List<DomainEventEntity>>()
		coEvery { dao.insertAll(capture(entitySlot)) } just runs

		repo.persist(listOf(event))

		coEvery { dao.getUnconsumedFor(any()) } returns entitySlot.captured

		val result = repo.getUnconsumed("test-consumer")
		result shouldHaveSize 1
		result.first() shouldBe event
	}

	@Test
	fun `SessionStarted round-trip`() = runTest {
		verifyRoundTrip(
			DomainEvent.SessionStarted(
				timestampMs = EpochMs(1000L),
				processorId = "test",
				isUserInitiated = true,
				initialTier = PolicyTier.AMBIENT,
			),
		)
	}

	@Test
	fun `SessionEnded round-trip`() = runTest {
		verifyRoundTrip(
			DomainEvent.SessionEnded(
				timestampMs = EpochMs(2000L),
				processorId = "test",
				sessionId = 42L,
				totalDistance = DistanceM(123.5f),
				totalSteps = StepCount(456),
				duration = DurationMs(7890L),
			),
		)
	}

	@Test
	fun `TierChanged round-trip`() = runTest {
		verifyRoundTrip(
			DomainEvent.TierChanged(
				timestampMs = EpochMs(3000L),
				processorId = "test",
				fromTier = PolicyTier.AMBIENT,
				toTier = PolicyTier.ACTIVE,
				reason = "activity detected",
			),
		)
	}

	@Test
	fun `TripStarted round-trip with activity`() = runTest {
		verifyRoundTrip(
			DomainEvent.TripStarted(
				timestampMs = EpochMs(4000L),
				processorId = "test",
				triggerActivity = DetectedActivityType.WALKING,
			),
		)
	}

	@Test
	fun `TripStarted round-trip with null activity`() = runTest {
		verifyRoundTrip(
			DomainEvent.TripStarted(
				timestampMs = EpochMs(4500L),
				processorId = "test",
				triggerActivity = null,
			),
		)
	}

	@Test
	fun `TripCompleted round-trip`() = runTest {
		verifyRoundTrip(
			DomainEvent.TripCompleted(
				timestampMs = EpochMs(5000L),
				processorId = "test",
				tripStartMs = EpochMs(4000L),
				distance = DistanceM(500f),
				steps = StepCount(1000),
				duration = DurationMs(60_000L),
				primaryMode = TransportMode.WALK,
			),
		)
	}

	@Test
	fun `CellDiscovered round-trip`() = runTest {
		verifyRoundTrip(
			DomainEvent.CellDiscovered(
				timestampMs = EpochMs(6000L),
				processorId = "test",
				cellToken = "abc123",
				level = 5,
			),
		)
	}

	@Test
	fun `AchievementUnlocked round-trip`() = runTest {
		verifyRoundTrip(
			DomainEvent.AchievementUnlocked(
				timestampMs = EpochMs(7000L),
				processorId = "test",
				achievementId = "first_walk",
				tier = "gold",
			),
		)
	}

	@Test
	fun `AchievementProgress round-trip`() = runTest {
		verifyRoundTrip(
			DomainEvent.AchievementProgress(
				timestampMs = EpochMs(8000L),
				processorId = "test",
				achievementId = "distance_master",
				currentValue = 500L,
				targetValue = 1000L,
			),
		)
	}

	@Test
	fun `DailySummaryUpdated round-trip`() = runTest {
		verifyRoundTrip(
			DomainEvent.DailySummaryUpdated(
				timestampMs = EpochMs(9000L),
				processorId = "test",
				dayEpoch = 19500L,
				totalDistance = DistanceM(1000f),
				totalSteps = StepCount(5000),
				totalDuration = DurationMs(3_600_000L),
				tripCount = 3,
			),
		)
	}
}
