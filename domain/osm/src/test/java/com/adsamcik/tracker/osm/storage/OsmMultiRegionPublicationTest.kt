package com.adsamcik.tracker.osm.storage

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.OsmImportEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayCellEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room-level regression tests for V10's import-scoped graph storage contract.
 * They intentionally bypass the release-gated legacy worker and exercise the
 * future-coordinator DAO contract directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsmMultiRegionPublicationTest {

	private lateinit var context: Application
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `BUILDING overlap never replaces or leaks into READY region`() {
		runBlocking {
			val readyImport = insertImport(
				name = "A",
				status = OsmImportEntity.STATUS_READY,
				publishedRevision = 1L,
			)
			val readyInstanceId = insertInstance(
				importId = readyImport,
				osmWayId = 42L,
				osmVersion = 1,
				cellKeys = listOf(CELL_A),
			)

			val buildingImport = insertImport(
				name = "B",
				status = OsmImportEntity.STATUS_BUILDING,
				publishedRevision = 0L,
			)
			val buildingInstanceId = insertInstance(
				importId = buildingImport,
				osmWayId = 42L,
				osmVersion = 2,
				cellKeys = listOf(CELL_A),
			)

			database.osmWayCellDao().findWayIdsInCells(listOf(CELL_A))
				.shouldContainExactly(readyInstanceId)
			database.osmWayDao().findByIds(listOf(readyInstanceId, buildingInstanceId))
				.map { it.importId }
				.shouldContainExactly(readyImport)

			// Cancelling/deleting B must cascade only B's own local rows.
			database.osmImportDao().delete(buildingImport)
			database.osmWayCellDao().findWayIdsInCells(listOf(CELL_A))
				.shouldContainExactly(readyInstanceId)
			database.osmWayDao().findByIds(listOf(readyInstanceId))
				.single()
				.importId shouldBe readyImport
		}
	}

	@Test
	fun `winner is selected globally before cell filtering and deletion reveals prior copy`() {
		runBlocking {
			val oldImport = insertImport(
				name = "old",
				status = OsmImportEntity.STATUS_READY,
				publishedRevision = 1L,
			)
			val oldInstanceId = insertInstance(
				importId = oldImport,
				osmWayId = 99L,
				osmVersion = 1,
				cellKeys = listOf(CELL_A),
			)
			val newImport = insertImport(
				name = "new",
				status = OsmImportEntity.STATUS_READY,
				publishedRevision = 2L,
			)
			val newInstanceId = insertInstance(
				importId = newImport,
				osmWayId = 99L,
				osmVersion = 2,
				cellKeys = listOf(CELL_B),
			)

			// The newer geometry moved away: do not leak the old copy from CELL_A.
			database.osmWayCellDao().findWayIdsInCells(listOf(CELL_A)).shouldBeEmpty()
			database.osmWayCellDao().findWayIdsInCells(listOf(CELL_B))
				.shouldContainExactly(newInstanceId)

			database.osmImportDao().delete(newImport)
			database.osmWayCellDao().findWayIdsInCells(listOf(CELL_A))
				.shouldContainExactly(oldInstanceId)
		}
	}

	@Test
	fun `duplicate logical way in one import aborts rather than replacing`() {
		runBlocking {
			val importId = insertImport(
				name = "single",
				status = OsmImportEntity.STATUS_READY,
				publishedRevision = 1L,
			)
			insertInstance(importId, osmWayId = 7L, osmVersion = null, cellKeys = listOf(CELL_A))

			shouldThrow<SQLiteConstraintException> {
				database.osmWayDao().insertNewImportScopedInstances(
					listOf(newWay(importId, osmWayId = 7L, osmVersion = null)),
				)
			}
		}
	}

	@Test
	fun `legacy header cleanup cascades only its own local graph`() {
		runBlocking {
			val legacyImport = insertImport(
				name = "legacy",
				status = OsmImportEntity.STATUS_READY,
				publishedRevision = 1L,
				wayBboxEncodingVersion = OsmImportEntity.WAY_BBOX_ENCODING_LEGACY_ORDERED,
			)
			insertInstance(
				importId = legacyImport,
				osmWayId = 123L,
				osmVersion = 1,
				cellKeys = listOf(CELL_A),
			)
			val supportedImport = insertImport(
				name = "supported",
				status = OsmImportEntity.STATUS_READY,
				publishedRevision = 2L,
			)
			val supportedInstance = insertInstance(
				importId = supportedImport,
				osmWayId = 456L,
				osmVersion = 1,
				cellKeys = listOf(CELL_B),
			)

			database.osmImportDao().deleteImportsWithUnsupportedWayBboxEncoding(
				OsmImportEntity.WAY_BBOX_ENCODING_DIRECTED_V1,
			) shouldBe 1
			database.osmImportDao().count() shouldBe 1
			database.osmWayCellDao().findWayIdsInCells(listOf(CELL_B))
				.shouldContainExactly(supportedInstance)
			database.osmWayCellDao().count() shouldBe 1
		}
	}

	private suspend fun insertImport(
		name: String,
		status: String,
		publishedRevision: Long,
		wayBboxEncodingVersion: Int = OsmImportEntity.WAY_BBOX_ENCODING_DIRECTED_V1,
	): Long = database.osmImportDao().insert(
		OsmImportEntity(
			displayName = "$name.osm.pbf",
			fileUri = "content://test/$name.osm.pbf",
			importedAt = 1_700_000_000_000L + publishedRevision,
			wayCount = 1,
			nodeCount = 2,
			diagnosticMinLatitudeE7 = 500_000_000,
			diagnosticMaxLatitudeE7 = 500_001_000,
			diagnosticMinLongitudeE7 = 140_000_000,
			diagnosticMaxLongitudeE7 = 140_001_000,
			wayBboxEncodingVersion = wayBboxEncodingVersion,
			status = status,
			cellIndexBuilt = 1,
			publishedRevision = publishedRevision,
		),
	)

	private suspend fun insertInstance(
		importId: Long,
		osmWayId: Long,
		osmVersion: Int?,
		cellKeys: List<Long>,
	): Long {
		val wayInstanceId = database.osmWayDao().insertNewImportScopedInstances(
			listOf(newWay(importId, osmWayId, osmVersion)),
		).single()
		database.osmWayCellDao().insertAll(
			cellKeys.map { cellKey -> OsmWayCellEntity(cellKey, wayInstanceId) },
		)
		return wayInstanceId
	}

	private fun newWay(
		importId: Long,
		osmWayId: Long,
		osmVersion: Int?,
	): OsmWayEntity = OsmWayEntity(
		id = osmWayId,
		importId = importId,
		osmVersion = osmVersion,
		name = "way-$osmWayId",
		roadClass = "residential",
		maxspeedKmh = 50,
		maxspeedExplicit = 1,
		isOneway = 0,
		geomPolylineE7 = byteArrayOf(2, 0, 0),
		bboxMinLatE7 = 500_000_000,
		bboxMaxLatE7 = 500_001_000,
		bboxMinLonE7 = 140_000_000,
		bboxMaxLonE7 = 140_001_000,
	).asNewImportScopedInstance()

	private companion object {
		const val CELL_A = 10L
		const val CELL_B = 20L
	}
}
