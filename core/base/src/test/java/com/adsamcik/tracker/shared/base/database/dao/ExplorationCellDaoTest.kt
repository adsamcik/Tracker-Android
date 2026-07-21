package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ExplorationCellEntity
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
class ExplorationCellDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: ExplorationCellDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.explorationCellDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	private fun createCell(
		cellToken: String = "token_1",
		level: Int = 14,
		quality: Int = 3,
		firstDiscoveredAt: Long = 1000L,
		lastVisitedAt: Long = 2000L,
		visitCount: Int = 1,
		seasonBitmask: Int = 0,
		centerLatE7: Int = 490000000,
		centerLonE7: Int = 140000000,
		createdAt: Long = System.currentTimeMillis()
	) = ExplorationCellEntity(
		cellToken = cellToken,
		level = level,
		quality = quality,
		firstDiscoveredAt = firstDiscoveredAt,
		lastVisitedAt = lastVisitedAt,
		visitCount = visitCount,
		seasonBitmask = seasonBitmask,
		centerLatE7 = centerLatE7,
		centerLonE7 = centerLonE7,
		createdAt = createdAt
	)

	@Test
	fun insertAndGetByToken() = runBlocking {
		val cell = createCell(cellToken = "abc123")
		dao.insert(cell)

		val result = dao.getByToken("abc123")
		assertNotNull(result)
		assertEquals("abc123", result!!.cellToken)
		assertEquals(14, result.level)
		assertEquals(3, result.quality)
	}

	@Test
	fun getByTokenReturnsNullForMissing() = runBlocking {
		val result = dao.getByToken("nonexistent")
		assertNull(result)
	}

	@Test
	fun insertIgnoresDuplicateCellToken() = runBlocking {
		val cell1 = createCell(cellToken = "dup", quality = 3)
		val cell2 = createCell(cellToken = "dup", quality = 5)

		val id1 = dao.insert(cell1)
		val id2 = dao.insert(cell2)

		assertTrue(id1 > 0)
		assertEquals(-1L, id2)

		val result = dao.getByToken("dup")
		assertEquals(3, result!!.quality)
	}

	@Test
	fun updateVisitIncrementsVisitCountAndUpdatesFields() = runBlocking {
		val cell = createCell(cellToken = "upd", quality = 1, visitCount = 1, seasonBitmask = 0)
		dao.insert(cell)

		dao.updateVisit(token = "upd", quality = 3, lastVisitedAt = 9999L, seasonBit = 1)

		val result = dao.getByToken("upd")
		assertNotNull(result)
		assertEquals(3, result!!.quality)
		assertEquals(9999L, result.lastVisitedAt)
		assertEquals(2, result.visitCount)
		assertEquals(1, result.seasonBitmask)
	}

	@Test
	fun explorationOutOfOrderEvent_doesNotRegressMetadata() = runBlocking {
		dao.insert(
			createCell(
				cellToken = "out-of-order",
				quality = 5,
				lastVisitedAt = 1_000L,
				visitCount = 0,
			),
		)

		dao.updateVisit(token = "out-of-order", quality = 2, lastVisitedAt = 500L, seasonBit = 1)

		val result = requireNotNull(dao.getByToken("out-of-order"))
		assertEquals(5, result.quality)
		assertEquals(1_000L, result.lastVisitedAt)
		assertEquals(1, result.visitCount)
	}

	@Test
	fun countAtLevelReturnsCorrectCount() = runBlocking {
		dao.insert(createCell(cellToken = "a", level = 14))
		dao.insert(createCell(cellToken = "b", level = 14))
		dao.insert(createCell(cellToken = "c", level = 12))

		assertEquals(2, dao.countAtLevel(14))
		assertEquals(1, dao.countAtLevel(12))
		assertEquals(0, dao.countAtLevel(10))
	}

	@Test
	fun getAllTokensAtLevelReturnsCorrectTokens() = runBlocking {
		dao.insert(createCell(cellToken = "a14", level = 14))
		dao.insert(createCell(cellToken = "b14", level = 14))
		dao.insert(createCell(cellToken = "c12", level = 12))

		val tokens = dao.getAllTokensAtLevel(14)
		assertEquals(2, tokens.size)
		assertTrue(tokens.contains("a14"))
		assertTrue(tokens.contains("b14"))
	}

	@Test
	fun countDiscoveredSinceFiltersCorrectly() = runBlocking {
		dao.insert(createCell(cellToken = "old", level = 14, firstDiscoveredAt = 100L))
		dao.insert(createCell(cellToken = "new1", level = 14, firstDiscoveredAt = 500L))
		dao.insert(createCell(cellToken = "new2", level = 14, firstDiscoveredAt = 600L))

		assertEquals(2, dao.countDiscoveredSince(sinceMs = 500L, level = 14))
		assertEquals(1, dao.countDiscoveredSince(sinceMs = 600L, level = 14))
		assertEquals(0, dao.countDiscoveredSince(sinceMs = 1000L, level = 14))
	}

	@Test
	fun countAtLevelFlowEmitsUpdates() = runBlocking {
		val initial = dao.countAtLevelFlow(14).first()
		assertEquals(0, initial)

		dao.insert(createCell(cellToken = "flow_test", level = 14))
		val afterInsert = dao.countAtLevelFlow(14).first()
		assertEquals(1, afterInsert)
	}

	@Test
	fun deleteAllRemovesAllCells() = runBlocking {
		dao.insert(createCell(cellToken = "x"))
		dao.insert(createCell(cellToken = "y"))

		dao.deleteAll()

		assertEquals(0, dao.countAtLevel(14))
		assertNull(dao.getByToken("x"))
	}

	@Test
	fun getCellsInBoundsFiltersByLevelAndBox() = runBlocking {
		// Inside the box at level 14.
		dao.insert(createCell(cellToken = "inside", level = 14, centerLatE7 = 500_500_000, centerLonE7 = 144_500_000))
		// Correct level but longitude outside the box.
		dao.insert(createCell(cellToken = "out_lon", level = 14, centerLatE7 = 500_500_000, centerLonE7 = 150_000_000))
		// Inside the box but a different level.
		dao.insert(createCell(cellToken = "wrong_level", level = 12, centerLatE7 = 500_500_000, centerLonE7 = 144_500_000))

		val result = dao.getCellsInBounds(
			level = 14,
			minLatE7 = 500_000_000,
			maxLatE7 = 501_000_000,
			minLonE7 = 144_000_000,
			maxLonE7 = 145_000_000,
			limit = 100,
		)

		assertEquals(1, result.size)
		assertEquals("inside", result.first().cellToken)
	}

	@Test
	fun getCellsInBoundsRespectsLimit() = runBlocking {
		repeat(5) { i ->
			dao.insert(createCell(cellToken = "c$i", level = 14, centerLatE7 = 500_000_000 + i, centerLonE7 = 144_000_000))
		}

		val result = dao.getCellsInBounds(
			level = 14,
			minLatE7 = 400_000_000,
			maxLatE7 = 600_000_000,
			minLonE7 = 100_000_000,
			maxLonE7 = 200_000_000,
			limit = 3,
		)

		assertEquals(3, result.size)
	}

	@Test
	fun getCellsInWrappedBoundsReturnsBothAntimeridianSidesWithOneLimit() = runBlocking {
		dao.insert(createCell(cellToken = "east", centerLatE7 = 0, centerLonE7 = 1_750_000_000))
		dao.insert(createCell(cellToken = "west", centerLatE7 = 0, centerLonE7 = -1_750_000_000))
		dao.insert(createCell(cellToken = "middle", centerLatE7 = 0, centerLonE7 = 0))

		val result = dao.getCellsInWrappedBounds(
			level = 14,
			minLatE7 = -100_000_000,
			maxLatE7 = 100_000_000,
			westLonE7 = 1_700_000_000,
			eastLonE7 = -1_700_000_000,
			limit = 2,
		)

		assertEquals(listOf("east", "west"), result.map { it.cellToken })
	}
}
