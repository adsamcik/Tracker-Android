package com.adsamcik.tracker.tracker.di

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.LiveStatsDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.database.data.LiveStatsEntity
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefaultDailySummaryProviderTest {

	private val tripDao = mockk<TripDao>()
	private val dailySummaryDao = mockk<DailySummaryDao>()
	private val liveStatsDao = mockk<LiveStatsDao>()

	@Test
	fun `raw Steps-only materialized rows do not fabricate product presence`() = runTest {
		val epochDay = todayEpochDay()
		coEvery { liveStatsDao.get() } returns liveStats(epochDay, steps = 4_000)
		coEvery { dailySummaryDao.getByDay(epochDay) } returns dailySummary(epochDay, steps = 4_000)
		coEvery { tripDao.getTodaySummary(any(), any()) } returns null
		val provider = provider(StandardTestDispatcher(testScheduler))

		provider.fetchTodaySummary() shouldBe null

		coVerify(exactly = 1) { tripDao.getTodaySummary(any(), any()) }
	}

	@Test
	fun `daily duration remains visible without inventing a session count`() = runTest {
		val epochDay = todayEpochDay()
		coEvery { liveStatsDao.get() } returns null
		coEvery { dailySummaryDao.getByDay(epochDay) } returns dailySummary(
			epochDay = epochDay,
			steps = 9_999,
			durationMs = 60_000L,
			tripCount = 0,
		)
		val provider = provider(StandardTestDispatcher(testScheduler))

		val summary = provider.fetchTodaySummary()

		summary?.totalDurationMs shouldBe 60_000L
		summary?.sessionCount shouldBe 0
		coVerify(exactly = 0) { tripDao.getTodaySummary(any(), any()) }
	}

	@Test
	fun `live duration remains visible while raw Steps-only live state is omitted`() = runTest {
		val epochDay = todayEpochDay()
		val provider = provider(StandardTestDispatcher(testScheduler))
		every { liveStatsDao.getFlow() } returns flowOf(liveStats(epochDay, steps = 4_000))

		provider.observeTodayLive().first() shouldBe null

		every { liveStatsDao.getFlow() } returns flowOf(
			liveStats(epochDay, steps = 4_000, durationMs = 60_000L),
		)
		val summary = provider.observeTodayLive().first()
		summary?.totalDurationMs shouldBe 60_000L
		summary?.sessionCount shouldBe 1
	}

	@Test
	fun `non-Step evidence recognizes each independent product fact`() {
		hasNonStepSummaryEvidence(distanceM = 1f, durationMs = 0L, sessionCount = 0) shouldBe true
		hasNonStepSummaryEvidence(distanceM = 0f, durationMs = 1L, sessionCount = 0) shouldBe true
		hasNonStepSummaryEvidence(distanceM = 0f, durationMs = 0L, sessionCount = 1) shouldBe true
		hasNonStepSummaryEvidence(distanceM = 0f, durationMs = 0L, sessionCount = 0) shouldBe false
	}

	private fun provider(dispatcher: CoroutineDispatcher) = DefaultDailySummaryProvider(
		tripDao = tripDao,
		dailySummaryDao = dailySummaryDao,
		liveStatsDao = liveStatsDao,
		ioDispatcher = dispatcher,
	)

	private fun todayEpochDay(): Long = Time.todayMillis / Time.DAY_IN_MILLISECONDS

	private fun liveStats(
		epochDay: Long,
		steps: Int,
		distanceM: Float = 0f,
		durationMs: Long = 0L,
	) = LiveStatsEntity(
		dateEpochDay = epochDay,
		sessionDistanceM = distanceM,
		sessionSteps = steps,
		sessionDurationMs = durationMs,
		dayTotalDistanceM = distanceM,
		dayTotalSteps = steps,
		dayTotalDurationMs = durationMs,
		lastUpdatedMs = Time.nowMillis,
	)

	private fun dailySummary(
		epochDay: Long,
		steps: Int,
		distanceM: Float = 0f,
		durationMs: Long = 0L,
		tripCount: Int = 0,
	) = DailySummaryEntity(
		dateEpochDay = epochDay,
		totalDistanceM = distanceM,
		totalSteps = steps,
		totalDurationMs = durationMs,
		tripCount = tripCount,
		activeTrackingMs = durationMs,
		lastUpdatedMs = Time.nowMillis,
		createdAt = Time.nowMillis,
		calendarZoneId = null,
	)
}
