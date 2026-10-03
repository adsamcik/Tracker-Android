package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import arrow.core.right
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ExplorationCellEntity
import com.adsamcik.tracker.shared.base.database.data.ExplorationStreakEntity
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual discovery DAO to windowed API. This is authored source, not executed device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowedDiscoveryStreakRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var provider: DefaultWindowedMetricsProvider
	private lateinit var zone: ZoneId

	@Before fun setUp() {
		val application: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(application)
		zone = ZoneId.systemDefault()
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		provider = DefaultWindowedMetricsProvider(database.dailySummaryDao(), database.explorationCellDao(),
			database.explorationStreakDao(), database.sessionSegmentDao(), database.exportLogDao(),
			dispatchers, SavedTripDistanceReader(database, dispatchers))
	}

	@After fun close() = database.close()

	private fun at(date: String, hour: Int = 12): Long =
		LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

	private suspend fun insert(token: String, time: Long, level: Int = 14, revisit: Long = time) {
		database.explorationCellDao().insert(ExplorationCellEntity(
			cellToken = token, level = level, quality = 2, firstDiscoveredAt = time,
			lastVisitedAt = revisit, centerLatE7 = 0, centerLonE7 = 0, createdAt = time,
		))
	}

	@Test fun `month streak cannot borrow preceding month discoveries or lifetime best`() = runTest {
		for (date in listOf("2025-12-30", "2025-12-31", "2026-01-01", "2026-01-02", "2026-01-04")) {
			insert(date, at(date))
		}
		database.explorationStreakDao().upsert(ExplorationStreakEntity(
			type = "DAILY_DISCOVERY", currentCount = 0, bestCount = 70, updatedAt = at("2026-01-04")))
		val month = TimeWindow.Interval(at("2026-01-01", 0), at("2026-02-01", 0) - 1L)
		provider.collect(MetricKeys.DAILY_STREAK, month) shouldBe 2L.right()
		provider.collect(MetricKeys.DAILY_STREAK, TimeWindow.Cumulative) shouldBe 70L.right()
	}

	@Test fun `partial day window filters exact timestamps before calendar membership`() = runTest {
		insert("outside-start", at("2026-01-01", 23))
		insert("start-boundary", at("2026-01-02", 12))
		insert("same-day", at("2026-01-02", 18))
		insert("end-boundary", at("2026-01-03", 12))
		insert("outside-end", at("2026-01-04", 1))
		provider.collect(MetricKeys.DAILY_STREAK,
			TimeWindow.Interval(at("2026-01-02", 12), at("2026-01-03", 12))) shouldBe 2L.right()
		provider.collect(MetricKeys.DAILY_STREAK,
			TimeWindow.Interval(at("2026-01-03", 12), at("2026-01-03", 12))) shouldBe 1L.right()
	}

	@Test fun `weekly query ignores old revisits and other exploration levels`() = runTest {
		insert("week-one", at("2026-01-01"))
		insert("week-two", at("2026-01-08"))
		insert("old-revisit", at("2025-06-01"), revisit = at("2026-01-15"))
		insert("other-level", at("2026-01-15"), level = 10)
		provider.collect(MetricKeys.WEEKLY_STREAK,
			TimeWindow.Interval(at("2026-01-01", 0), at("2026-02-01", 0) - 1L)) shouldBe 2L.right()
	}

	@Test fun `late insertion and retention deletion change the selected discovery streak`() = runTest {
		insert("day-three", at("2026-01-03"))
		insert("day-one", at("2026-01-01"))
		val range = TimeWindow.Interval(at("2026-01-01", 0), at("2026-01-04", 0) - 1L)
		provider.collect(MetricKeys.DAILY_STREAK, range) shouldBe 1L.right()
		insert("late-day-two", at("2026-01-02"))
		provider.collect(MetricKeys.DAILY_STREAK, range) shouldBe 3L.right()
		database.explorationCellDao().deleteOlderThan(at("2026-01-02", 0))
		provider.collect(MetricKeys.DAILY_STREAK, range) shouldBe 2L.right()
		database.explorationCellDao().deleteAll()
		provider.collect(MetricKeys.DAILY_STREAK, range) shouldBe 0L.right()
	}
}
