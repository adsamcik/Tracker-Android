package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ExplorationStreakEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorationStreakDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: ExplorationStreakDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.explorationStreakDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun getByTypeReturnsNullForMissing() = runBlocking {
		val result = dao.getByType("nonexistent")
		assertNull(result)
	}

	@Test
	fun upsertInsertsAndRetrievesByType() = runBlocking {
		val streak = ExplorationStreakEntity(
			type = "DAILY_DISCOVERY",
			currentCount = 5,
			bestCount = 10,
			lastIncrementDay = 19000L,
			updatedAt = 1000L
		)
		dao.upsert(streak)

		val result = dao.getByType("DAILY_DISCOVERY")
		assertNotNull(result)
		assertEquals("DAILY_DISCOVERY", result!!.type)
		assertEquals(5, result.currentCount)
		assertEquals(10, result.bestCount)
	}

	@Test
	fun upsertReplacesExistingStreak() = runBlocking {
		dao.upsert(ExplorationStreakEntity(type = "DAILY_DISCOVERY", currentCount = 3, bestCount = 3, updatedAt = 100L))
		dao.upsert(ExplorationStreakEntity(type = "DAILY_DISCOVERY", currentCount = 4, bestCount = 4, updatedAt = 200L))

		val result = dao.getByType("DAILY_DISCOVERY")
		assertEquals(4, result!!.currentCount)
		assertEquals(4, result.bestCount)
		assertEquals(200L, result.updatedAt)
	}

	@Test
	fun incrementStreakIncrementsCountAndUpdatesBest() = runBlocking {
		dao.upsert(ExplorationStreakEntity(type = "DAILY_DISCOVERY", currentCount = 3, bestCount = 5, updatedAt = 100L))

		dao.incrementStreak(type = "DAILY_DISCOVERY", epochDay = 19001L, updatedAt = 200L)

		val result = dao.getByType("DAILY_DISCOVERY")
		assertEquals(4, result!!.currentCount)
		assertEquals(5, result.bestCount) // best was already 5, current is 4
		assertEquals(19001L, result.lastIncrementDay)
		assertEquals(200L, result.updatedAt)
	}

	@Test
	fun incrementStreakUpdatesBestWhenCurrentExceedsBest() = runBlocking {
		dao.upsert(ExplorationStreakEntity(type = "CHAIN", currentCount = 5, bestCount = 5, updatedAt = 100L))

		dao.incrementStreak(type = "CHAIN", epochDay = 19002L, updatedAt = 300L)

		val result = dao.getByType("CHAIN")
		assertEquals(6, result!!.currentCount)
		assertEquals(6, result.bestCount) // best should update to match new current
	}

	@Test
	fun resetStreakSetsCurrentCountToZero() = runBlocking {
		dao.upsert(ExplorationStreakEntity(type = "DAILY_DISCOVERY", currentCount = 7, bestCount = 10, updatedAt = 100L))

		dao.resetStreak(type = "DAILY_DISCOVERY", updatedAt = 500L)

		val result = dao.getByType("DAILY_DISCOVERY")
		assertEquals(0, result!!.currentCount)
		assertEquals(10, result.bestCount) // best unchanged
		assertEquals(500L, result.updatedAt)
	}

	@Test
	fun getAllReturnsAllStreaks() = runBlocking {
		dao.upsert(ExplorationStreakEntity(type = "DAILY_DISCOVERY", currentCount = 1, updatedAt = 100L))
		dao.upsert(ExplorationStreakEntity(type = "WEEKLY_EXPLORER", currentCount = 2, updatedAt = 200L))

		val all = dao.getAll()
		assertEquals(2, all.size)
	}

	@Test
	fun deleteAllRemovesAllStreaks() = runBlocking {
		dao.upsert(ExplorationStreakEntity(type = "a", updatedAt = 100L))
		dao.upsert(ExplorationStreakEntity(type = "b", updatedAt = 200L))

		dao.deleteAll()

		assertTrue(dao.getAll().isEmpty())
		assertNull(dao.getByType("a"))
	}
}
