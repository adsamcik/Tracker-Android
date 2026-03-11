package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.LiveStatsDao
import com.adsamcik.tracker.shared.base.database.data.LiveStatsEntity
import com.adsamcik.tracker.stats.api.repository.LiveStats
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefaultLiveStatsRepositoryTest {

	private val dao: LiveStatsDao = mockk()
	private val repository = DefaultLiveStatsRepository(dao)

	@Test
	fun `observeLiveStats returns defaults when dao emits null`() = runTest {
		every { dao.getFlow() } returns flowOf(null)

		repository.observeLiveStats().first() shouldBe LiveStats()
	}

	@Test
	fun `observeLiveStats maps entity and coerces negative values`() = runTest {
		every { dao.getFlow() } returns flowOf(
			LiveStatsEntity(
				dateEpochDay = 20_000L,
				sessionDistanceM = -1f,
				sessionSteps = -5,
				sessionDurationMs = -50L,
				dayTotalDistanceM = -2f,
				dayTotalSteps = -10,
				dayTotalDurationMs = 0L,
				lastUpdatedMs = 123L,
			),
		)

		val stats = repository.observeLiveStats().first()
		stats.sessionDistance shouldBe DistanceM.ZERO
		stats.sessionSteps shouldBe StepCount.ZERO
		stats.sessionDuration shouldBe DurationMs.ZERO
		stats.dayTotalDistance shouldBe DistanceM.ZERO
		stats.dayTotalSteps shouldBe StepCount.ZERO
	}

	@Test
	fun `updateLiveStats writes expected values to dao`() = runTest {
		coJustRun { dao.upsert(any(), any(), any(), any(), any(), any(), any(), any()) }
		val stats = LiveStats(
			sessionDistance = DistanceM(10f),
			sessionSteps = StepCount(200),
			sessionDuration = DurationMs(8_000L),
			dayTotalDistance = DistanceM(50f),
			dayTotalSteps = StepCount(700),
		)

		repository.updateLiveStats(stats)

		coVerify {
			dao.upsert(
				any(),
				sessionDistanceM = 10f,
				sessionSteps = 200,
				sessionDurationMs = 8_000L,
				dayTotalDistanceM = 50f,
				dayTotalSteps = 700,
				dayTotalDurationMs = 8_000L,
				lastUpdatedMs = any(),
			)
		}
	}

	@Test
	fun `clear delegates to dao`() = runTest {
		coEvery { dao.clear() } returns Unit

		repository.clear()

		coVerify(exactly = 1) { dao.clear() }
	}
}
