package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailySummaryDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: DailySummaryDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.dailySummaryDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	private fun createSummary(
		dateEpochDay: Long,
		distanceM: Float = 1000f,
		steps: Int = 500,
		durationMs: Long = 3600_000L,
		tripCount: Int = 2,
		activeTrackingMs: Long = 3000_000L,
		lastUpdatedMs: Long = System.currentTimeMillis()
	) = DailySummaryEntity(
		dateEpochDay = dateEpochDay,
		totalDistanceM = distanceM,
		totalSteps = steps,
		totalDurationMs = durationMs,
		tripCount = tripCount,
		activeTrackingMs = activeTrackingMs,
		lastUpdatedMs = lastUpdatedMs,
		createdAt = System.currentTimeMillis()
	)

	@Test
	fun getByDayReturnsNullForMissingDay() = runBlocking {
		val result = dao.getByDay(19000L)
		assertNull(result)
	}

	@Test
	fun insertAndGetByDay() = runBlocking {
		val summary = createSummary(19000L, distanceM = 5000f, steps = 2000)
		dao.insert(summary)

		val result = dao.getByDay(19000L)
		assertNotNull(result)
		assertEquals(19000L, result!!.dateEpochDay)
		assertEquals(5000f, result.totalDistanceM)
		assertEquals(2000, result.totalSteps)
	}

	@Test
	fun upsertInsertsNewRow() = runBlocking {
		dao.upsert(
			dateEpochDay = 19000L,
			totalDistanceM = 1500f,
			totalSteps = 750,
			totalDurationMs = 1800_000L,
			tripCount = 1,
			activeTrackingMs = 1500_000L,
			lastUpdatedMs = 100L
		)

		val result = dao.getByDay(19000L)
		assertNotNull(result)
		assertEquals(1500f, result!!.totalDistanceM)
		assertEquals(750, result.totalSteps)
		assertEquals(1, result.tripCount)
	}

	@Test
	fun upsertUpdatesExistingRow() = runBlocking {
		// Insert first
		dao.upsert(
			dateEpochDay = 19000L,
			totalDistanceM = 1000f,
			totalSteps = 500,
			totalDurationMs = 1800_000L,
			tripCount = 1,
			activeTrackingMs = 1000_000L,
			lastUpdatedMs = 100L
		)

		// Upsert with new values
		dao.upsert(
			dateEpochDay = 19000L,
			totalDistanceM = 2000f,
			totalSteps = 1000,
			totalDurationMs = 3600_000L,
			tripCount = 3,
			activeTrackingMs = 2000_000L,
			lastUpdatedMs = 200L
		)

		val result = dao.getByDay(19000L)
		assertNotNull(result)
		assertEquals(2000f, result!!.totalDistanceM)
		assertEquals(1000, result.totalSteps)
		assertEquals(3, result.tripCount)
	}

	@Test
	fun upsertPreservesCreatedAt() = runBlocking {
		// First insert sets createdAt
		dao.upsert(
			dateEpochDay = 19000L,
			totalDistanceM = 1000f,
			totalSteps = 500,
			totalDurationMs = 1800_000L,
			tripCount = 1,
			activeTrackingMs = 1000_000L,
			lastUpdatedMs = 100L
		)
		val original = dao.getByDay(19000L)!!
		val originalCreatedAt = original.createdAt

		// Second upsert should preserve createdAt
		dao.upsert(
			dateEpochDay = 19000L,
			totalDistanceM = 2000f,
			totalSteps = 1000,
			totalDurationMs = 3600_000L,
			tripCount = 2,
			activeTrackingMs = 2000_000L,
			lastUpdatedMs = 200L
		)

		val updated = dao.getByDay(19000L)!!
		assertEquals(originalCreatedAt, updated.createdAt)
	}

	@Test
	fun getBetweenReturnsMatchingDays() = runBlocking {
		dao.insert(createSummary(19000L))
		dao.insert(createSummary(19001L))
		dao.insert(createSummary(19002L))
		dao.insert(createSummary(19005L))

		val results = dao.getBetween(19000L, 19002L)
		assertEquals(3, results.size)
		assertEquals(19000L, results[0].dateEpochDay)
		assertEquals(19001L, results[1].dateEpochDay)
		assertEquals(19002L, results[2].dateEpochDay)
	}

	@Test
	fun getBetweenReturnsEmptyForNoMatches() = runBlocking {
		dao.insert(createSummary(19000L))

		val results = dao.getBetween(19005L, 19010L)
		assertEquals(0, results.size)
	}

	@Test
	fun getByDayFlowEmitsUpdates() = runBlocking {
		// Initial: null
		val initial = dao.getByDayFlow(19000L).first()
		assertNull(initial)

		// After insert
		dao.insert(createSummary(19000L, distanceM = 1234f))
		val afterInsert = dao.getByDayFlow(19000L).first()
		assertNotNull(afterInsert)
		assertEquals(1234f, afterInsert!!.totalDistanceM)
	}

	@Test
	fun deleteAllRemovesAllRows() = runBlocking {
		dao.insert(createSummary(19000L))
		dao.insert(createSummary(19001L))

		dao.deleteAll()

		assertNull(dao.getByDay(19000L))
		assertNull(dao.getByDay(19001L))
	}

	@Test
	fun deleteOlderThanRemovesOldRows() = runBlocking {
		dao.insert(createSummary(19000L))
		dao.insert(createSummary(19001L))
		dao.insert(createSummary(19005L))

		val deleted = dao.deleteOlderThan(19002L)
		assertEquals(2, deleted)
		assertNull(dao.getByDay(19000L))
		assertNotNull(dao.getByDay(19005L))
	}

	@Test
	fun getAllBeforeReturnsDaysBefore() = runBlocking {
		dao.insert(createSummary(19000L))
		dao.insert(createSummary(19001L))
		dao.insert(createSummary(19002L))
		dao.insert(createSummary(19005L))

		val results = dao.getAllBefore(19002L)
		assertEquals(2, results.size)
		assertEquals(19000L, results[0].dateEpochDay)
		assertEquals(19001L, results[1].dateEpochDay)
	}

	@Test
	fun getAllBeforeReturnsEmptyWhenNoneMatch() = runBlocking {
		dao.insert(createSummary(19005L))

		val results = dao.getAllBefore(19000L)
		assertEquals(0, results.size)
	}
}
