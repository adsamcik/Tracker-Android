package com.adsamcik.tracker.osm.reindex

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.osm.io.OsmGridIndex
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.OsmImportEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayCellEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Validates the contract used by the v31->v32 migration recovery path:
 * given a database with `osm_import` + `osm_way` rows but an empty
 * `osm_way_cell`, [OsmWayCellReindexer] rebuilds the cell rows under the
 * current [OsmGridIndex] cell size with no polyline decode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsmWayCellReindexerTest {

	private lateinit var context: Application
	private lateinit var database: AppDatabase
	private lateinit var reindexer: OsmWayCellReindexer

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		reindexer = OsmWayCellReindexer(
			osmImportDao = database.osmImportDao(),
			osmWayDao = database.osmWayDao(),
			osmWayCellDao = database.osmWayCellDao(),
			dispatchers = TestDispatchersProvider(Dispatchers.Unconfined),
		)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `reindexIfNeeded rebuilds cell index from preserved bbox columns`() {
		runBlocking {
		val importId = seedImport()
		val expectedCellsForWay1 = OsmGridIndex.cellKeysForBbox(
			minLatE7 = 500_000_000,
			maxLatE7 = 500_100_000,
			minLonE7 = 144_000_000,
			maxLonE7 = 144_100_000,
		).toList()
		val expectedCellsForWay2 = OsmGridIndex.cellKeysForBbox(
			minLatE7 = 500_500_000,
			maxLatE7 = 500_700_000,
			minLonE7 = 144_500_000,
			maxLonE7 = 144_800_000,
		).toList()
		seedWay(id = 100L, importId = importId, bbox = Bbox(500_000_000, 500_100_000, 144_000_000, 144_100_000))
		seedWay(id = 200L, importId = importId, bbox = Bbox(500_500_000, 500_700_000, 144_500_000, 144_800_000))

		database.osmWayCellDao().count() shouldBe 0

		val rowsWritten = reindexer.reindexIfNeeded()

		val expectedTotal = expectedCellsForWay1.size + expectedCellsForWay2.size
		rowsWritten shouldBe expectedTotal
		database.osmWayCellDao().count() shouldBe expectedTotal
		cellKeysForWay(100L) shouldContainExactlyInAnyOrder expectedCellsForWay1
		cellKeysForWay(200L) shouldContainExactlyInAnyOrder expectedCellsForWay2
		}
	}

	@Test
	fun `reindexIfNeeded marks cell_index_built after successful completion`() {
		runBlocking {
		val importId = seedImport()
		seedWay(id = 100L, importId = importId, bbox = Bbox(500_000_000, 500_100_000, 144_000_000, 144_100_000))

		database.osmImportDao().hasUnbuiltCellIndex() shouldBe true

		reindexer.reindexIfNeeded()

		database.osmImportDao().hasUnbuiltCellIndex() shouldBe false
		getCellIndexBuilt(importId) shouldBe 1
		}
	}

	@Test
	fun `reindexIfNeeded is a no-op when cell_index_built is already 1`() {
		runBlocking {
		val importId = seedImport(cellIndexBuilt = 1)
		seedWay(id = 100L, importId = importId, bbox = Bbox(500_000_000, 500_100_000, 144_000_000, 144_100_000))
		// Pre-existing cell row simulating a completed prior reindex.
		database.osmWayCellDao().insertAll(listOf(OsmWayCellEntity(cellKey = 42L, wayId = 100L)))

		val rowsWritten = reindexer.reindexIfNeeded()

		rowsWritten shouldBe 0
		database.osmWayCellDao().count() shouldBe 1
		cellKeysForWay(100L) shouldContainExactlyInAnyOrder listOf(42L)
		}
	}

	@Test
	fun `reindexIfNeeded recovers from sticky partial reindex (crash mid-batch)`() {
		runBlocking {
		// Simulate: import exists with cell_index_built = 0 AND some
		// pre-existing osm_way_cell rows from a prior interrupted reindex.
		val importId = seedImport()
		seedWay(id = 100L, importId = importId, bbox = Bbox(500_000_000, 500_100_000, 144_000_000, 144_100_000))
		seedWay(id = 200L, importId = importId, bbox = Bbox(500_500_000, 500_700_000, 144_500_000, 144_800_000))
		// Stale partial cells from interrupted reindex.
		database.osmWayCellDao().insertAll(listOf(
			OsmWayCellEntity(cellKey = 42L, wayId = 100L),
			OsmWayCellEntity(cellKey = 99L, wayId = 200L),
		))
		database.osmWayCellDao().count() shouldBe 2

		// cell_index_built = 0 → reindex triggers despite existing cells.
		val rowsWritten = reindexer.reindexIfNeeded()

		(rowsWritten > 0) shouldBe true
		database.osmImportDao().hasUnbuiltCellIndex() shouldBe false
		getCellIndexBuilt(importId) shouldBe 1
		// Both ways are now fully indexed.
		val distinctWays = allWayIdsInCellIndex().sorted()
		distinctWays shouldBe listOf(100L, 200L)
		}
	}

	@Test
	fun `reindexIfNeeded is a no-op when osm_import is empty`() {
		runBlocking {
		// osm_way is empty too because of FK, but assert via the controller path.
		val rowsWritten = reindexer.reindexIfNeeded()

		rowsWritten shouldBe 0
		database.osmWayCellDao().count() shouldBe 0
		}
	}

	@Test
	fun `reindex pages large imports without holding everything in memory`() {
		runBlocking {
		val importId = seedImport()
		// 7 ways, page size 3 → 3 + 3 + 1 batches.
		val seededIds = (1L..7L).toList()
		seededIds.forEach { id ->
			seedWay(
				id = id,
				importId = importId,
				bbox = Bbox(
					minLatE7 = 500_000_000 + (id * 100_000).toInt(),
					maxLatE7 = 500_000_000 + (id * 100_000).toInt() + 50_000,
					minLonE7 = 144_000_000 + (id * 100_000).toInt(),
					maxLonE7 = 144_000_000 + (id * 100_000).toInt() + 50_000,
				),
			)
		}

		val rowsWritten = reindexer.reindex(pageSize = 3)

		(rowsWritten >= seededIds.size) shouldBe true
		// Every seeded way appears in the rebuilt index — query directly
		// (using findWayIdsInCells over a wide bbox would exceed SQLite's
		// 999-variable limit at the 0.01° cell size).
		val distinctWays = allWayIdsInCellIndex().sorted()
		distinctWays shouldBe seededIds
		}
	}

	private suspend fun seedImport(cellIndexBuilt: Int = 0): Long {
		return database.osmImportDao().insert(
			OsmImportEntity(
				displayName = "Prague.osm.pbf",
				fileUri = "content://test/prague.osm.pbf",
				importedAt = 1_700_000_000_000L,
				wayCount = 2,
				nodeCount = 8,
				minLatE7 = 500_000_000,
				maxLatE7 = 500_700_000,
				minLonE7 = 144_000_000,
				maxLonE7 = 144_800_000,
				cellIndexBuilt = cellIndexBuilt,
			),
		)
	}

	private data class Bbox(
		val minLatE7: Int,
		val maxLatE7: Int,
		val minLonE7: Int,
		val maxLonE7: Int,
	)

	private suspend fun seedWay(id: Long, importId: Long, bbox: Bbox) {
		database.osmWayDao().insertAll(
			listOf(
				OsmWayEntity(
					id = id,
					importId = importId,
					name = "Test Way $id",
					roadClass = "residential",
					maxspeedKmh = 50,
					maxspeedExplicit = 1,
					isOneway = 0,
					geomPolylineE7 = ByteArray(0),
					bboxMinLatE7 = bbox.minLatE7,
					bboxMaxLatE7 = bbox.maxLatE7,
					bboxMinLonE7 = bbox.minLonE7,
					bboxMaxLonE7 = bbox.maxLonE7,
				),
			),
		)
	}

	private fun getCellIndexBuilt(importId: Long): Int {
		val cursor = database.openHelper.readableDatabase.query(
			"SELECT cell_index_built FROM osm_import WHERE id = ?",
			arrayOf(importId),
		)
		return cursor.use { c ->
			c.moveToFirst()
			c.getInt(0)
		}
	}

	private suspend fun cellKeysForWay(wayId: Long): List<Long> {
		// Query osm_way_cell directly via the open helper so the test
		// observes exactly what the reindexer wrote, without depending on
		// the DAO offering a per-way lookup (it intentionally doesn't,
		// because production code only ever queries by cell).
		val cursor = database.openHelper.readableDatabase.query(
			"SELECT cell_key FROM osm_way_cell WHERE way_id = ? ORDER BY cell_key",
			arrayOf(wayId),
		)
		return cursor.use { c ->
			buildList {
				while (c.moveToNext()) add(c.getLong(0))
			}
		}
	}

	private fun allWayIdsInCellIndex(): List<Long> {
		val cursor = database.openHelper.readableDatabase.query(
			"SELECT DISTINCT way_id FROM osm_way_cell",
		)
		return cursor.use { c ->
			buildList {
				while (c.moveToNext()) add(c.getLong(0))
			}
		}
	}
}
