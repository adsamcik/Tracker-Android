package com.adsamcik.tracker.game.event

import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.data.ExplorationStreakEntity
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class ExplorationStreakTrackerTest {
	private val streakDao: ExplorationStreakDao = mockk(relaxed = true)
	private val tracker = ExplorationStreakTracker()

	@Test
	fun `first exploration discovery starts streak`() = runTest {
		val date = LocalDate.of(2026, 1, 10)
		val timestamp = date.startOfDayMs() + 5_000L
		coEvery { streakDao.getByType(ExplorationStreakTracker.STREAK_TYPE_DAILY) } returns null

		val upsertSlot = slot<ExplorationStreakEntity>()
		coEvery { streakDao.upsert(capture(upsertSlot)) } returns Unit

		tracker.onCellDiscovered(
			streakDao = streakDao,
			event = cellDiscoveredEvent(timestampMs = timestamp),
			wasNewCell = true,
		)

		coVerify(exactly = 1) { streakDao.getByType(ExplorationStreakTracker.STREAK_TYPE_DAILY) }
		coVerify(exactly = 1) { streakDao.upsert(any()) }
		val captured = upsertSlot.captured
		assert(captured.type == ExplorationStreakTracker.STREAK_TYPE_DAILY)
		assert(captured.currentCount == 1)
		assert(captured.bestCount == 1)
		assert(captured.lastIncrementDay == date.toEpochDay())
		assert(captured.updatedAt == timestamp)
		coVerify(exactly = 0) { streakDao.incrementStreak(any(), any(), any()) }
	}

	@Test
	fun `new day discovery increments existing streak`() = runTest {
		val previousDay = LocalDate.of(2026, 1, 10)
		val currentDay = previousDay.plusDays(1)
		val currentTimestamp = currentDay.startOfDayMs() + 10_000L
		coEvery { streakDao.getByType(ExplorationStreakTracker.STREAK_TYPE_DAILY) } returns ExplorationStreakEntity(
			type = ExplorationStreakTracker.STREAK_TYPE_DAILY,
			currentCount = 1,
			bestCount = 4,
			lastIncrementDay = previousDay.toEpochDay(),
			updatedAt = previousDay.startOfDayMs(),
		)

		tracker.onCellDiscovered(
			streakDao = streakDao,
			event = cellDiscoveredEvent(timestampMs = currentTimestamp),
			wasNewCell = true,
		)

		coVerify(exactly = 1) {
			streakDao.incrementStreak(
				type = ExplorationStreakTracker.STREAK_TYPE_DAILY,
				epochDay = currentDay.toEpochDay(),
				updatedAt = currentTimestamp,
			)
		}
		coVerify(exactly = 0) { streakDao.upsert(any()) }
	}

	@Test
	fun `same day discovery does not double count streak`() = runTest {
		val date = LocalDate.of(2026, 1, 10)
		coEvery { streakDao.getByType(ExplorationStreakTracker.STREAK_TYPE_DAILY) } returns ExplorationStreakEntity(
			type = ExplorationStreakTracker.STREAK_TYPE_DAILY,
			currentCount = 3,
			bestCount = 5,
			lastIncrementDay = date.toEpochDay(),
			updatedAt = date.startOfDayMs(),
		)

		tracker.onCellDiscovered(
			streakDao = streakDao,
			event = cellDiscoveredEvent(timestampMs = date.startOfDayMs() + 20_000L),
			wasNewCell = true,
		)

		coVerify(exactly = 1) { streakDao.getByType(ExplorationStreakTracker.STREAK_TYPE_DAILY) }
		coVerify(exactly = 0) { streakDao.incrementStreak(any(), any(), any()) }
		coVerify(exactly = 0) { streakDao.upsert(any()) }
	}

	@Test
	fun `discovery after gap resets current streak to one and keeps best`() = runTest {
		val oldDay = LocalDate.of(2026, 1, 10)
		val newDay = oldDay.plusDays(3)
		val newTimestamp = newDay.startOfDayMs() + 30_000L
		coEvery { streakDao.getByType(ExplorationStreakTracker.STREAK_TYPE_DAILY) } returns ExplorationStreakEntity(
			type = ExplorationStreakTracker.STREAK_TYPE_DAILY,
			currentCount = 6,
			bestCount = 9,
			lastIncrementDay = oldDay.toEpochDay(),
			updatedAt = oldDay.startOfDayMs(),
		)

		val upsertSlot = slot<ExplorationStreakEntity>()
		coEvery { streakDao.upsert(capture(upsertSlot)) } returns Unit

		tracker.onCellDiscovered(
			streakDao = streakDao,
			event = cellDiscoveredEvent(timestampMs = newTimestamp),
			wasNewCell = true,
		)

		coVerify(exactly = 1) { streakDao.upsert(any()) }
		val captured = upsertSlot.captured
		assert(captured.type == ExplorationStreakTracker.STREAK_TYPE_DAILY)
		assert(captured.currentCount == 1)
		assert(captured.bestCount == 9)
		assert(captured.lastIncrementDay == newDay.toEpochDay())
		assert(captured.updatedAt == newTimestamp)
		coVerify(exactly = 0) { streakDao.incrementStreak(any(), any(), any()) }
	}

	@Test
	fun `non exploration level discovery is ignored`() = runTest {
		tracker.onCellDiscovered(
			streakDao = streakDao,
			event = cellDiscoveredEvent(level = 12),
			wasNewCell = true,
		)

		coVerify(exactly = 0) { streakDao.getByType(any()) }
		coVerify(exactly = 0) { streakDao.incrementStreak(any(), any(), any()) }
		coVerify(exactly = 0) { streakDao.upsert(any()) }
	}

	@Test
	fun `existing cell revisit is ignored`() = runTest {
		tracker.onCellDiscovered(
			streakDao = streakDao,
			event = cellDiscoveredEvent(),
			wasNewCell = false,
		)

		coVerify(exactly = 0) { streakDao.getByType(any()) }
		coVerify(exactly = 0) { streakDao.incrementStreak(any(), any(), any()) }
		coVerify(exactly = 0) { streakDao.upsert(any()) }
	}

	private fun cellDiscoveredEvent(
		timestampMs: Long = LocalDate.of(2026, 1, 10).startOfDayMs(),
		level: Int = ExplorationStreakTracker.EXPLORATION_LEVEL,
	) = DomainEvent.CellDiscovered(
		timestampMs = EpochMs(timestampMs),
		processorId = "test-processor",
		cellToken = "cell-token-$timestampMs-$level",
		level = level,
		centerLatE7 = 0,
		centerLonE7 = 0,
		quality = 3,
		seasonBit = 1,
	)

	private fun LocalDate.startOfDayMs(): Long =
		atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
