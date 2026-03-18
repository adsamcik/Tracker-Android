package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LiveStatsEntity
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
class LiveStatsDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: LiveStatsDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.liveStatsDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun getReturnsNullWhenEmpty() = runBlocking {
		val result = dao.get()
		assertNull(result)
	}

	@Test
	fun upsertAndGet() = runBlocking {
		dao.upsert(
			dateEpochDay = 19000L,
			sessionDistanceM = 100f,
			sessionSteps = 50,
			sessionDurationMs = 60_000L,
			dayTotalDistanceM = 500f,
			dayTotalSteps = 1000,
			dayTotalDurationMs = 3600_000L,
			lastUpdatedMs = 12345L
		)

		val result = dao.get()
		assertNotNull(result)
		assertEquals(0, result!!.id)
		assertEquals(19000L, result.dateEpochDay)
		assertEquals(100f, result.sessionDistanceM)
		assertEquals(50, result.sessionSteps)
		assertEquals(60_000L, result.sessionDurationMs)
		assertEquals(500f, result.dayTotalDistanceM)
		assertEquals(1000, result.dayTotalSteps)
		assertEquals(3600_000L, result.dayTotalDurationMs)
		assertEquals(12345L, result.lastUpdatedMs)
	}

	@Test
	fun upsertReplacesExistingRow() = runBlocking {
		// First upsert
		dao.upsert(
			dateEpochDay = 19000L,
			sessionDistanceM = 100f,
			sessionSteps = 50,
			sessionDurationMs = 60_000L,
			dayTotalDistanceM = 500f,
			dayTotalSteps = 1000,
			dayTotalDurationMs = 3600_000L,
			lastUpdatedMs = 100L
		)

		// Second upsert with different values
		dao.upsert(
			dateEpochDay = 19000L,
			sessionDistanceM = 200f,
			sessionSteps = 100,
			sessionDurationMs = 120_000L,
			dayTotalDistanceM = 700f,
			dayTotalSteps = 1200,
			dayTotalDurationMs = 3660_000L,
			lastUpdatedMs = 200L
		)

		// Should have only one row with updated values
		val result = dao.get()
		assertNotNull(result)
		assertEquals(200f, result!!.sessionDistanceM)
		assertEquals(100, result.sessionSteps)
		assertEquals(700f, result.dayTotalDistanceM)
	}

	@Test
	fun clearRemovesRow() = runBlocking {
		dao.upsert(
			dateEpochDay = 19000L,
			sessionDistanceM = 100f,
			sessionSteps = 50,
			sessionDurationMs = 60_000L,
			dayTotalDistanceM = 500f,
			dayTotalSteps = 1000,
			dayTotalDurationMs = 3600_000L,
			lastUpdatedMs = 100L
		)

		dao.clear()
		assertNull(dao.get())
	}

	@Test
	fun deleteAllRemovesRow() = runBlocking {
		dao.upsert(
			dateEpochDay = 19000L,
			sessionDistanceM = 100f,
			sessionSteps = 50,
			sessionDurationMs = 60_000L,
			dayTotalDistanceM = 500f,
			dayTotalSteps = 1000,
			dayTotalDurationMs = 3600_000L,
			lastUpdatedMs = 100L
		)

		dao.deleteAll()
		assertNull(dao.get())
	}

	@Test
	fun getFlowEmitsNull() = runBlocking {
		val result = dao.getFlow().first()
		assertNull(result)
	}

	@Test
	fun getFlowEmitsAfterUpsert() = runBlocking {
		dao.upsert(
			dateEpochDay = 19000L,
			sessionDistanceM = 100f,
			sessionSteps = 50,
			sessionDurationMs = 60_000L,
			dayTotalDistanceM = 500f,
			dayTotalSteps = 1000,
			dayTotalDurationMs = 3600_000L,
			lastUpdatedMs = 100L
		)

		val result = dao.getFlow().first()
		assertNotNull(result)
		assertEquals(100f, result!!.sessionDistanceM)
	}

	@Test
	fun singleRowConstraint() = runBlocking {
		// Insert via insert (from BaseDao) should also work with id=0
		val entity = LiveStatsEntity(
			id = 0,
			dateEpochDay = 19000L,
			sessionDistanceM = 100f,
			sessionSteps = 50,
			sessionDurationMs = 60_000L,
			dayTotalDistanceM = 500f,
			dayTotalSteps = 1000,
			dayTotalDurationMs = 3600_000L,
			lastUpdatedMs = 100L
		)
		dao.insert(entity)

		val result = dao.get()
		assertNotNull(result)
		assertEquals(100f, result!!.sessionDistanceM)
	}
}
