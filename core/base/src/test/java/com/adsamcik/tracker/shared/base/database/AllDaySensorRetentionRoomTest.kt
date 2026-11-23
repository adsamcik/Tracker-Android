package com.adsamcik.tracker.shared.base.database

import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.AllDaySensorObservationIdentity
import com.adsamcik.tracker.shared.base.database.data.ObservationStampColumns
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.allDayRetentionLowerBoundMs
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Authored regression source only; execution is deferred to the convergence batch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AllDaySensorRetentionRoomTest {
	private lateinit var database: AppDatabase
	@Before fun setup() { database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext()) }
	@After fun close() = database.close()

	@Test fun `cleanup and bounded native reads use uncertainty and the complete pressure window`() = runTest {
		val activityDao = database.activitySnapshotDao()
		activityDao.insert(activity("uncertain", 1_000L, 1L))
		activityDao.insert(activity("unknown", 1_001L, null))
		val activityAtFloor = activity("at-floor", 1_001L, 1L)
		activityDao.insert(activityAtFloor)
		activityDao.insert(activity("legacy", 1_000L, null).copy(sourceSignalId = "legacy"))

		val pressureDao = database.pressureSampleDao()
		val fractional = pressure("fractional", 1_100L, 0L, 100_000_001L)
		val pressureAtFloor = pressure("at-floor", 1_100L, 0L, 100_000_000L)
		pressureDao.insert(fractional)
		pressureDao.insert(pressure("uncertain", 1_101L, 2L, 100_000_000L))
		pressureDao.insert(pressure("unknown", 1_102L, null, null))
		pressureDao.insert(pressureAtFloor)
		pressureDao.insert(pressure("legacy", 1_100L, null, null).copy(sourceSignalId = "legacy"))

		assertEquals(999L, fractional.allDayRetentionLowerBoundMs())
		assertEquals(1_000L, pressureAtFloor.allDayRetentionLowerBoundMs())
		assertEquals(listOf(activityAtFloor.sourceSignalId), activityDao.nativeAllDayBetween(0L, 2_000L, 1, 1_000L)
			.map { it.sourceSignalId })
		assertEquals(listOf(pressureAtFloor.sourceSignalId), pressureDao.nativeAllDayBetween(0L, 2_000L, 1, 1_000L)
			.map { it.sourceSignalId })

		assertEquals(2, activityDao.deleteOlderThan(1_000L))
		assertEquals(3, pressureDao.deleteOlderThan(1_000L))
		assertEquals(setOf("legacy", activityAtFloor.sourceSignalId), activityDao.getAllBetween(0L, 2_000L)
			.map { it.sourceSignalId }.toSet())
		assertEquals(setOf("legacy", pressureAtFloor.sourceSignalId), pressureDao.getAllBetween(0L, 2_000L)
			.map { it.sourceSignalId }.toSet())
	}

	private fun activity(id: String, time: Long, uncertainty: Long?) = ActivitySnapshot(timeMs = time,
		activityType = 7, confidence = 80, isTransition = false, createdAt = time,
		sourceSignalId = AllDaySensorObservationIdentity.activity(id),
		observationStamp = ObservationStampColumns(timeUncertaintyMs = uncertainty))

	private fun pressure(id: String, time: Long, uncertainty: Long?, end: Long?) = PressureSample(timeMs = time,
		elapsedRealtimeNanos = end ?: 0L, pressureHpa = 1_010f, altitudeM = 0f, bucketId = null,
		createdAt = time, sourceSignalId = AllDaySensorObservationIdentity.pressure(id), sampleCount = 2,
		windowStartElapsedRealtimeNanos = 0L.takeIf { end != null }, windowEndElapsedRealtimeNanos = end,
		observationStamp = ObservationStampColumns(timeUncertaintyMs = uncertainty))
}
