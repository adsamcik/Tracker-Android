package com.adsamcik.tracker.osm.imp

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.adsamcik.tracker.osm.OsmRoadClass
import com.adsamcik.tracker.osm.io.OsmParseException
import com.adsamcik.tracker.osm.io.OsmParseStats
import com.adsamcik.tracker.osm.io.ParsedOsmWay
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.OsmImportEntity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsmImportWorkerTest {
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
	fun `successful import stays BUILDING during parsing and becomes READY after data is committed`() {
		runBlocking {
			val parser = OsmImportWorker.OsmImportParser { _, _, _, _, onWayBatch ->
				statusOfOnlyImport() shouldBe OsmImportEntity.STATUS_BUILDING
				database.osmImportDao().readyCount() shouldBe 0
				onWayBatch(listOf(parsedWay()))
				statusOfOnlyImport() shouldBe OsmImportEntity.STATUS_BUILDING
				OsmParseStats(
					nodeCount = 2,
					wayCount = 1,
					minLatE7 = 500_000_000,
					maxLatE7 = 500_001_000,
					minLonE7 = 144_000_000,
					maxLonE7 = 144_001_000,
				)
			}

			val result = buildWorker(parser).doWork()

			(result is ListenableWorker.Result.Success) shouldBe true
			statusOfOnlyImport() shouldBe OsmImportEntity.STATUS_READY
			database.osmImportDao().readyCount() shouldBe 1
			database.osmWayDao().count() shouldBe 1
			database.osmWayCellDao().count() shouldBe 1
			finalCounts() shouldBe (1L to 2L)
		}
	}

	@Test
	fun `caught parse failure deletes the BUILDING import`() {
		runBlocking {
			val parser = OsmImportWorker.OsmImportParser { _, _, _, _, _ ->
				statusOfOnlyImport() shouldBe OsmImportEntity.STATUS_BUILDING
				throw OsmParseException.TooManyNodes(nodeCount = 10, limit = 5)
			}

			val result = buildWorker(parser).doWork()

			(result is ListenableWorker.Result.Failure) shouldBe true
			database.osmImportDao().count() shouldBe 0
			database.osmWayDao().count() shouldBe 0
			database.osmWayCellDao().count() shouldBe 0
		}
	}

	private fun buildWorker(parser: OsmImportWorker.OsmImportParser): OsmImportWorker =
		TestListenableWorkerBuilder<OsmImportWorker>(context)
			.setInputData(
				workDataOf(
					OsmImportWorker.KEY_FILE_URI to "content://test/region.osm.pbf",
					OsmImportWorker.KEY_DISPLAY_NAME to "region.osm.pbf",
					OsmImportWorker.KEY_FILE_SIZE to 1L,
				),
			)
			.setWorkerFactory(object : WorkerFactory() {
				override fun createWorker(
					appContext: Context,
					workerClassName: String,
					workerParameters: WorkerParameters,
				): ListenableWorker = OsmImportWorker(
					appContext = appContext,
					params = workerParameters,
					appDatabase = database,
					ioDispatcher = Dispatchers.Unconfined,
					parser = parser,
				)
			})
			.build() as OsmImportWorker

	private fun statusOfOnlyImport(): String =
		database.openHelper.readableDatabase.query("SELECT status FROM osm_import").use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(0)
		}

	private fun finalCounts(): Pair<Long, Long> =
		database.openHelper.readableDatabase.query(
			"SELECT way_count, node_count FROM osm_import",
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0) to cursor.getLong(1)
		}

	private fun parsedWay(): ParsedOsmWay = ParsedOsmWay(
		osmId = 101,
		name = "Test road",
		roadClass = OsmRoadClass.RESIDENTIAL,
		maxspeedKmh = 50,
		maxspeedExplicit = true,
		isOneway = false,
		geomPolylineE7 = byteArrayOf(1),
		bboxMinLatE7 = 500_000_000,
		bboxMaxLatE7 = 500_001_000,
		bboxMinLonE7 = 144_000_000,
		bboxMaxLonE7 = 144_001_000,
		cellKeys = longArrayOf(42),
	)
}
