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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
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

	@Test
	fun `startup preserves BUILDING import created after its startup marker`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val scope = CoroutineScope(SupervisorJob() + dispatcher)
		val reindexer = mockk<OsmWayCellReindexer>()
		val reindexStarted = CompletableDeferred<Unit>()
		coEvery { reindexer.reindexIfNeeded() } coAnswers {
			reindexStarted.complete(Unit)
			0
		}
		seedImport(
			displayName = "abandoned.osm.pbf",
			status = OsmImportEntity.STATUS_BUILDING,
			importedAt = 1_700_000_000_000L,
		)

		OsmModuleInitializer(
			appScope = scope,
			dispatchers = TestDispatchersProvider(dispatcher),
			osmImportDao = database.osmImportDao(),
			reindexer = reindexer,
		).initialize()
		seedImport(
			displayName = "restarting.osm.pbf",
			status = OsmImportEntity.STATUS_BUILDING,
			importedAt = Long.MAX_VALUE,
		)

		advanceUntilIdle()
		reindexStarted.await()

		database.osmImportDao().count() shouldBe 1
		importNames() shouldContainExactly listOf("restarting.osm.pbf")
		coVerify(exactly = 1) { reindexer.reindexIfNeeded() }
		scope.cancel()
	}

	@Test
	fun `startup deletes development imports with legacy ordered way bboxes`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val scope = CoroutineScope(SupervisorJob() + dispatcher)
		val reindexer = mockk<OsmWayCellReindexer>()
		val reindexStarted = CompletableDeferred<Unit>()
		coEvery { reindexer.reindexIfNeeded() } coAnswers {
			reindexStarted.complete(Unit)
			0
		}
		val legacyId = seedImport(
			displayName = "legacy-bbox.osm.pbf",
			status = OsmImportEntity.STATUS_READY,
			wayBboxEncodingVersion = OsmImportEntity.WAY_BBOX_ENCODING_LEGACY_ORDERED,
		)
		seedWayAndCell(303, legacyId, 43)
		database.osmImportDao().getLatest()!!.wayBboxEncodingVersion shouldBe
			OsmImportEntity.WAY_BBOX_ENCODING_LEGACY_ORDERED

		OsmModuleInitializer(
			appScope = scope,
			dispatchers = TestDispatchersProvider(dispatcher),
			osmImportDao = database.osmImportDao(),
			reindexer = reindexer,
		).initialize()
		advanceUntilIdle()
		reindexStarted.await()

		database.osmImportDao().count() shouldBe 0
		database.osmWayDao().count() shouldBe 0
		database.osmWayCellDao().count() shouldBe 0
		coVerify(exactly = 1) { reindexer.reindexIfNeeded() }
		scope.cancel()
	}

	private suspend fun seedImport(
		displayName: String,
		status: String,
		importedAt: Long = 1_700_000_000_000L,
		wayBboxEncodingVersion: Int = OsmImportEntity.WAY_BBOX_ENCODING_DIRECTED_V1,
	): Long =
		database.osmImportDao().insert(
			OsmImportEntity(
				displayName = displayName,
				fileUri = "content://test/$displayName",
				importedAt = importedAt,
				wayCount = 1,
				nodeCount = 2,
				diagnosticMinLatitudeE7 = 500_000_000,
				diagnosticMaxLatitudeE7 = 500_001_000,
				diagnosticMinLongitudeE7 = 144_000_000,
				diagnosticMaxLongitudeE7 = 144_001_000,
				wayBboxEncodingVersion = wayBboxEncodingVersion,
				status = status,
				cellIndexBuilt = 1,
			),
		)

	private suspend fun seedWayAndCell(wayId: Long, importId: Long, cellKey: Long) {
		val wayInstanceId = database.osmWayDao().insertNewImportScopedInstances(
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
				).asNewImportScopedInstance(),
			),
		).single()
		database.osmWayCellDao().insertAll(
			listOf(OsmWayCellEntity(cellKey = cellKey, wayId = wayInstanceId)),
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
			"SELECT osm_way_id FROM osm_way ORDER BY osm_way_id",
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) add(cursor.getLong(0))
			}
		}
}
