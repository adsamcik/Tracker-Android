package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import kotlinx.coroutines.flow.first
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
class AchievementProgressDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: AchievementProgressDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.achievementProgressDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun getByIdReturnsNullForMissing() = runBlocking {
		val result = dao.getById("nonexistent")
		assertNull(result)
	}

	@Test
	fun upsertInsertsAndRetrievesById() = runBlocking {
		val progress = AchievementProgressEntity(
			achievementId = "explore_100_cells",
			currentValue = 42,
			targetValue = 100,
			tier = 1,
			updatedAt = 1000L
		)
		dao.upsert(progress)

		val result = dao.getById("explore_100_cells")
		assertNotNull(result)
		assertEquals("explore_100_cells", result!!.achievementId)
		assertEquals(42L, result.currentValue)
		assertEquals(100L, result.targetValue)
		assertEquals(1, result.tier)
		assertNull(result.unlockedAt)
	}

	@Test
	fun upsertReplacesExistingProgress() = runBlocking {
		dao.upsert(AchievementProgressEntity(
			achievementId = "test_ach",
			currentValue = 10,
			targetValue = 50,
			updatedAt = 100L
		))
		dao.upsert(AchievementProgressEntity(
			achievementId = "test_ach",
			currentValue = 50,
			targetValue = 50,
			unlockedAt = 500L,
			updatedAt = 200L
		))

		val result = dao.getById("test_ach")
		assertEquals(50L, result!!.currentValue)
		assertEquals(500L, result.unlockedAt)
	}

	@Test
	fun getUnlockedReturnsOnlyUnlockedAchievements() = runBlocking {
		dao.upsert(AchievementProgressEntity(
			achievementId = "locked",
			currentValue = 5,
			targetValue = 100,
			updatedAt = 100L
		))
		dao.upsert(AchievementProgressEntity(
			achievementId = "unlocked",
			currentValue = 100,
			targetValue = 100,
			unlockedAt = 999L,
			updatedAt = 200L
		))

		val unlocked = dao.getUnlocked()
		assertEquals(1, unlocked.size)
		assertEquals("unlocked", unlocked[0].achievementId)
	}

	@Test
	fun getAllFlowEmitsUpdates() = runBlocking {
		val initial = dao.getAllFlow().first()
		assertTrue(initial.isEmpty())

		dao.upsert(AchievementProgressEntity(
			achievementId = "flow_test",
			currentValue = 1,
			targetValue = 10,
			updatedAt = 100L
		))
		val afterInsert = dao.getAllFlow().first()
		assertEquals(1, afterInsert.size)
		assertEquals("flow_test", afterInsert[0].achievementId)
	}

	@Test
	fun deleteAllRemovesAllProgress() = runBlocking {
		dao.upsert(AchievementProgressEntity(achievementId = "a", targetValue = 10, updatedAt = 100L))
		dao.upsert(AchievementProgressEntity(achievementId = "b", targetValue = 20, updatedAt = 200L))

		dao.deleteAll()

		assertTrue(dao.getAll().isEmpty())
		assertNull(dao.getById("a"))
	}
}
