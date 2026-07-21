package com.adsamcik.tracker.osm.reindex

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.OsmImportEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayCellEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsmModuleInitializerTest {
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
	fun `startup removes abandoned BUILDING import with children and preserves READY import`() {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
		val reindexer = mockk<OsmWayCellReindexer>()
		val reindexStarted = CompletableDeferred<Unit>()
		coEvery { reindexer.reindexIfNeeded() } coAnswers {
			reindexStarted.complete(Unit)
			0
		}

		runBlocking {
			val buildingId = seedImport("building.osm.pbf", OsmImportEntity.STATUS_BUILDING)
			val readyId = seedImport("ready.osm.pbf", OsmImportEntity.STATUS_READY)
			seedWayAndCell(101, buildingId, 41)
			seedWayAndCell(202, readyId, 42)
		}

		OsmModuleInitializer(
			appScope = scope,
			dispatchers = TestDispatchersProvider(Dispatchers.Unconfined),
			osmImportDao = database.osmImportDao(),
			reindexer = reindexer,
		).initialize()

		runBlocking {
			reindexStarted.await()
			database.osmImportDao().count() shouldBe 1
			database.osmImportDao().readyCount() shouldBe 1
			database.osmWayDao().count() shouldBe 1
			database.osmWayCellDao().count() shouldBe 1
		}
		importNames() shouldContainExactly listOf("ready.osm.pbf")
		wayIds() shouldContainExactly listOf(202L)
		coVerify(exactly = 1) { reindexer.reindexIfNeeded() }
		scope.cancel()
	}

	private suspend fun seedImport(displayName: String, status: String): Long =
		database.osmImportDao().insert(
			OsmImportEntity(
				displayName = displayName,
				fileUri = "content://test/$displayName",
				importedAt = 1_700_000_000_000L,
				wayCount = 1,
				nodeCount = 2,
				minLatE7 = 500_000_000,
				maxLatE7 = 500_001_000,
				minLonE7 = 144_000_000,
				maxLonE7 = 144_001_000,
				status = status,
				cellIndexBuilt = 1,
			),
		)

	private suspend fun seedWayAndCell(wayId: Long, importId: Long, cellKey: Long) {
		database.osmWayDao().insertAll(
			listOf(
				OsmWayEntity(
					id = wayId,
					importId = importId,
					name = null,
					roadClass = "RESIDENTIAL",
					maxspeedKmh = 50,
					maxspeedExplicit = 1,
					isOneway = 0,
					geomPolylineE7 = byteArrayOf(1),
					bboxMinLatE7 = 500_000_000,
					bboxMaxLatE7 = 500_001_000,
					bboxMinLonE7 = 144_000_000,
					bboxMaxLonE7 = 144_001_000,
				),
			),
		)
		database.osmWayCellDao().insertAll(
			listOf(OsmWayCellEntity(cellKey = cellKey, wayId = wayId)),
		)
	}

	private fun importNames(): List<String> =
		database.openHelper.readableDatabase.query(
			"SELECT display_name FROM osm_import ORDER BY id",
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) add(cursor.getString(0))
			}
		}

	private fun wayIds(): List<Long> =
		database.openHelper.readableDatabase.query(
			"SELECT id FROM osm_way ORDER BY id",
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) add(cursor.getLong(0))
			}
		}
}
