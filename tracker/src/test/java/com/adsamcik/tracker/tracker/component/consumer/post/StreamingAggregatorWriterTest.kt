package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.LiveStatsDao
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.tracker.component.producer.StepDataProducer
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test suite for [StreamingAggregatorWriter].
 *
 * Tests the bridge component that connects the tracker pipeline to stats-engine's
 * [com.adsamcik.tracker.stats.engine.aggregator.StreamingAggregator].
 *
 * Key behaviors verified:
 * - Step and distance accumulation across multiple tracking cycles
 * - Periodic flush to live_stats table every 30 seconds
 * - Session finalization to daily_summary on disable
 * - Day baseline seeding from existing daily_summary
 * - Empty session handling
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StreamingAggregatorWriterTest {

	private lateinit var writer: StreamingAggregatorWriter
	private lateinit var context: Context
	private lateinit var mockDatabase: AppDatabase
	private lateinit var mockDailySummaryDao: DailySummaryDao
	private lateinit var mockLiveStatsDao: LiveStatsDao

	@Before
	fun setup() {
		context = mockk(relaxed = true)
		mockDatabase = mockk(relaxed = true)
		mockDailySummaryDao = mockk(relaxed = true)
		mockLiveStatsDao = mockk(relaxed = true)

		every { mockDatabase.dailySummaryDao() } returns mockDailySummaryDao
		every { mockDatabase.liveStatsDao() } returns mockLiveStatsDao

		mockkObject(AppDatabase.Companion)
		every { AppDatabase.database(any()) } returns mockDatabase

		mockkObject(Time)

		writer = StreamingAggregatorWriter()
	}

	@After
	fun teardown() {
		unmockkObject(AppDatabase.Companion)
		unmockkObject(Time)
	}

	private fun mockTime(ms: Long) {
		every { Time.nowMillis } returns ms
	}

	private fun createTempData(timeMs: Long, steps: Int? = null): MutableCollectionTempData {
		val tempData = MutableCollectionTempData(timeMs, timeMs * 1_000_000)
		if (steps != null) {
			tempData.set(StepDataProducer.NEW_STEPS_ARG, steps)
		}
		return tempData
	}

	private fun createCollectionData(
		latitude: Double? = null,
		longitude: Double? = null,
		speed: Float? = null,
	): CollectionData {
		val data = mockk<CollectionData>(relaxed = true)
		if (latitude != null && longitude != null) {
			val location = mockk<Location>()
			every { location.latitude } returns latitude
			every { location.longitude } returns longitude
			every { location.speed } returns speed
			every { location.horizontalAccuracy } returns 10f
			every { data.location } returns location
		} else {
			every { data.location } returns null
		}
		every { data.activity } returns null
		return data
	}

	private fun createSession(): TrackerSession = mockk(relaxed = true)

	// --- Accumulation Tests ---

	@Test
	fun stepsAccumulateAcrossMultipleCycles() = runTest {
		val baseTime = 1_000_000_000L
		mockTime(baseTime)
		coEvery { mockDailySummaryDao.getByDay(any()) } returns null

		writer.onEnable(context)

		writer.onNewData(
			context, createSession(),
			createCollectionData(),
			createTempData(baseTime, steps = 10)
		)
		writer.onNewData(
			context, createSession(),
			createCollectionData(),
			createTempData(baseTime + 10_000, steps = 20)
		)
		writer.onNewData(
			context, createSession(),
			createCollectionData(),
			createTempData(baseTime + 20_000, steps = 15)
		)

		mockTime(baseTime + 25_000)
		writer.onDisable(context)

		coVerify {
			mockDailySummaryDao.upsert(
				dateEpochDay = any(),
				totalDistanceM = any(),
				totalSteps = 45,
				totalDurationMs = any(),
				tripCount = any(),
				activeTrackingMs = any(),
				lastUpdatedMs = any()
			)
		}
	}

	@Test
	fun distanceAccumulatesFromLocationUpdates() = runTest {
		val baseTime = 1_000_000_000L
		mockTime(baseTime)
		coEvery { mockDailySummaryDao.getByDay(any()) } returns null

		writer.onEnable(context)

		// Provide initial location
		writer.onNewData(
			context, createSession(),
			createCollectionData(latitude = 50.0, longitude = 14.0),
			createTempData(baseTime)
		)
		// Move ~111m north
		writer.onNewData(
			context, createSession(),
			createCollectionData(latitude = 50.001, longitude = 14.0),
			createTempData(baseTime + 5_000)
		)

		mockTime(baseTime + 10_000)
		writer.onDisable(context)

		// Distance between 50.0,14.0 and 50.001,14.0 is approximately 111 meters
		coVerify {
			mockDailySummaryDao.upsert(
				dateEpochDay = any(),
				totalDistanceM = match { it > 100f && it < 120f },
				totalSteps = any(),
				totalDurationMs = any(),
				tripCount = any(),
				activeTrackingMs = any(),
				lastUpdatedMs = any()
			)
		}
	}

	@Test
	fun noDataCyclesDoNotAccumulate() = runTest {
		val baseTime = 1_000_000_000L
		mockTime(baseTime)
		coEvery { mockDailySummaryDao.getByDay(any()) } returns null

		writer.onEnable(context)

		// Single cycle with no steps or location delta
		writer.onNewData(
			context, createSession(),
			createCollectionData(),
			createTempData(baseTime)
		)

		mockTime(baseTime + 5_000)
		writer.onDisable(context)

		coVerify {
			mockDailySummaryDao.upsert(
				dateEpochDay = any(),
				totalDistanceM = 0f,
				totalSteps = 0,
				totalDurationMs = any(),
				tripCount = any(),
				activeTrackingMs = any(),
				lastUpdatedMs = any()
			)
		}
	}

	// --- Flush Interval Tests ---

	@Test
	fun noFlushBefore30Seconds() = runTest {
		val baseTime = 1_000_000_000L
		mockTime(baseTime)
		coEvery { mockDailySummaryDao.getByDay(any()) } returns null

		writer.onEnable(context)

		// Feed data at 15 seconds (before 30s interval)
		writer.onNewData(
			context, createSession(),
			createCollectionData(),
			createTempData(baseTime + 15_000, steps = 5)
		)

		// No flush should occur before 30s
		coVerify(exactly = 0) {
			mockLiveStatsDao.upsert(any(), any(), any(), any(), any(), any(), any(), any())
		}

		mockTime(baseTime + 20_000)
		writer.onDisable(context)
	}

	@Test
	fun flushTriggeredAfter30Seconds() = runTest {
		val baseTime = 1_000_000_000L
		mockTime(baseTime)
		coEvery { mockDailySummaryDao.getByDay(any()) } returns null

		writer.onEnable(context)

		// Advance mock time past 30s threshold before calling onNewData
		mockTime(baseTime + 31_000)
		writer.onNewData(
			context, createSession(),
			createCollectionData(),
			createTempData(baseTime + 31_000, steps = 10)
		)

		// Allow async flush to complete
		Thread.sleep(200)

		// Flush should have occurred
		coVerify(atLeast = 1) {
			mockLiveStatsDao.upsert(any(), any(), any(), any(), any(), any(), any(), any())
		}

		mockTime(baseTime + 35_000)
		writer.onDisable(context)
	}

	// --- onDisable Tests ---

	@Test
	fun onDisableMaterializesToDailySummaryAndClearsLiveStats() = runTest {
		val baseTime = 1_000_000_000L
		mockTime(baseTime)
		coEvery { mockDailySummaryDao.getByDay(any()) } returns null

		writer.onEnable(context)

		writer.onNewData(
			context, createSession(),
			createCollectionData(),
			createTempData(baseTime, steps = 25)
		)

		mockTime(baseTime + 60_000)
		writer.onDisable(context)

		// Should write final snapshot to daily_summary
		coVerify(exactly = 1) {
			mockDailySummaryDao.upsert(
				dateEpochDay = any(),
				totalDistanceM = any(),
				totalSteps = 25,
				totalDurationMs = match { it >= 0L },
				tripCount = any(),
				activeTrackingMs = any(),
				lastUpdatedMs = any()
			)
		}

		// Should clear live_stats
		coVerify(exactly = 1) { mockLiveStatsDao.clear() }
	}

	// --- Day Baseline Tests ---

	@Test
	fun dayBaselineAddedToSessionTotals() = runTest {
		val baseTime = 1_000_000_000L
		mockTime(baseTime)

		// Existing daily summary with prior session data
		val existingSummary = DailySummaryEntity(
			dateEpochDay = baseTime / Time.DAY_IN_MILLISECONDS,
			totalDistanceM = 500f,
			totalSteps = 1000,
			totalDurationMs = 3600_000L,
			tripCount = 2,
			activeTrackingMs = 3000_000L,
			lastUpdatedMs = baseTime - 1000,
			createdAt = baseTime - 10000
		)
		coEvery { mockDailySummaryDao.getByDay(any()) } returns existingSummary

		writer.onEnable(context)

		// New session adds 200 steps
		writer.onNewData(
			context, createSession(),
			createCollectionData(),
			createTempData(baseTime, steps = 200)
		)

		mockTime(baseTime + 5_000)
		writer.onDisable(context)

		// Should write day totals (baseline + session)
		coVerify {
			mockDailySummaryDao.upsert(
				dateEpochDay = any(),
				totalDistanceM = 500f, // baseline preserved (no new distance)
				totalSteps = 1200, // baseline + session
				totalDurationMs = any(),
				tripCount = any(),
				activeTrackingMs = any(),
				lastUpdatedMs = any()
			)
		}
	}

	// --- Empty State Tests ---

	@Test
	fun enableAndImmediateDisableWithNoDataStillMaterializes() = runTest {
		val baseTime = 1_000_000_000L
		mockTime(baseTime)
		coEvery { mockDailySummaryDao.getByDay(any()) } returns null

		writer.onEnable(context)
		writer.onDisable(context)

		// Should still materialize zero totals
		coVerify(exactly = 1) {
			mockDailySummaryDao.upsert(
				dateEpochDay = any(),
				totalDistanceM = 0f,
				totalSteps = 0,
				totalDurationMs = any(),
				tripCount = any(),
				activeTrackingMs = any(),
				lastUpdatedMs = any()
			)
		}
		coVerify(exactly = 1) { mockLiveStatsDao.clear() }
	}

	// --- Distance Calculation Integration Test ---

	@Test
	fun distanceCalculationUsesAndroidLocationDistanceBetween() = runTest {
		val baseTime = 1_000_000_000L
		mockTime(baseTime)
		coEvery { mockDailySummaryDao.getByDay(any()) } returns null

		writer.onEnable(context)

		// Prague coordinates: ~1km apart
		writer.onNewData(
			context, createSession(),
			createCollectionData(latitude = 50.0755, longitude = 14.4378),
			createTempData(baseTime)
		)
		writer.onNewData(
			context, createSession(),
			createCollectionData(latitude = 50.0855, longitude = 14.4378),
			createTempData(baseTime + 10_000)
		)

		mockTime(baseTime + 15_000)
		writer.onDisable(context)

		// Distance should be approximately 1112 meters (0.01 degrees latitude)
		coVerify {
			mockDailySummaryDao.upsert(
				dateEpochDay = any(),
				totalDistanceM = match { it > 1000f && it < 1200f },
				totalSteps = any(),
				totalDurationMs = any(),
				tripCount = any(),
				activeTrackingMs = any(),
				lastUpdatedMs = any()
			)
		}
	}

	// --- Multiple Flush Cycles Test ---

	@Test
	fun multipleCyclesTriggerMultipleFlushes() = runTest {
		val baseTime = 1_000_000_000L
		mockTime(baseTime)
		coEvery { mockDailySummaryDao.getByDay(any()) } returns null

		writer.onEnable(context)

		// First cycle at T+0
		writer.onNewData(
			context, createSession(),
			createCollectionData(),
			createTempData(baseTime, steps = 5)
		)

		// Second cycle at T+31s (triggers first flush)
		mockTime(baseTime + 31_000)
		writer.onNewData(
			context, createSession(),
			createCollectionData(),
			createTempData(baseTime + 31_000, steps = 10)
		)
		Thread.sleep(100)

		// Third cycle at T+62s (triggers second flush)
		mockTime(baseTime + 62_000)
		writer.onNewData(
			context, createSession(),
			createCollectionData(),
			createTempData(baseTime + 62_000, steps = 15)
		)
		Thread.sleep(100)

		mockTime(baseTime + 70_000)
		writer.onDisable(context)

		// Should have flushed at least twice
		coVerify(atLeast = 2) {
			mockLiveStatsDao.upsert(any(), any(), any(), any(), any(), any(), any(), any())
		}

		// Final total should be sum of all steps
		coVerify {
			mockDailySummaryDao.upsert(
				dateEpochDay = any(),
				totalDistanceM = any(),
				totalSteps = 30,
				totalDurationMs = any(),
				tripCount = any(),
				activeTrackingMs = any(),
				lastUpdatedMs = any()
			)
		}
	}
}
