package com.adsamcik.tracker.stats.data.repository

import arrow.core.Either
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.stats.api.error.StatsError
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefaultDailySummaryRepositoryTest {

	private val dao: DailySummaryDao = mockk()
	private val repository = DefaultDailySummaryRepository(dao)

	@Test
	fun `observeToday maps nullable dao result`() = runTest {
		every { dao.getByDayFlow(any()) } returns flowOf(
			DailySummaryEntity(
				dateEpochDay = 20_000L,
				totalDistanceM = 123.4f,
				totalSteps = 555,
				totalDurationMs = 3_600_000L,
				tripCount = 2,
				activeTrackingMs = -10L,
				lastUpdatedMs = 1L,
				createdAt = 1L,
			),
		)

		val today = repository.observeToday().first()
		today?.totalDistance?.raw shouldBe 123.4f
		today?.activeTrackingDuration?.raw shouldBe 0L
	}

	@Test
	fun `getBetween returns mapped summaries on success`() = runTest {
		coEvery { dao.getBetween(1L, 2L) } returns listOf(
			DailySummaryEntity(
				dateEpochDay = 1L,
				totalDistanceM = -50f,
				totalSteps = 10,
				totalDurationMs = -1L,
				tripCount = 1,
				activeTrackingMs = -2L,
				lastUpdatedMs = 1L,
				createdAt = 1L,
			),
		)

		val summary = resultValue(repository.getBetween(1L, 2L)).single()
		summary.totalDistance.raw shouldBe 0f
		summary.totalDuration.raw shouldBe 0L
		summary.activeTrackingDuration.raw shouldBe 0L
	}

	@Test
	fun `getBetween wraps exceptions as database error`() = runTest {
		val failure = RuntimeException("db read failed")
		coEvery { dao.getBetween(4L, 9L) } throws failure

		val error = resultError(repository.getBetween(4L, 9L))
		error shouldBe StatsError.DatabaseError("Failed to load daily summaries: db read failed", failure)
	}

	private fun resultError(result: Either<StatsError, *>): StatsError {
		return result.fold({ it }, { error("Expected Left but was Right") })
	}

	private fun <T> resultValue(result: Either<StatsError, T>): T {
		return result.fold({ error("Expected Right but was Left: $it") }, { it })
	}
}
