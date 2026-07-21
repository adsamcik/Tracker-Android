package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.base.mapper.toEntity
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.LocationSample as EntityLocationSample
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JsonExporterRoomIntegrationTest {

	private lateinit var context: Context
	private lateinit var database: AppDatabase
	private lateinit var locationSampleDao: LocationSampleDao
	private lateinit var sessionSegmentDao: SessionSegmentDao
	private val tempFiles = mutableListOf<File>()

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		locationSampleDao = database.locationSampleDao()
		sessionSegmentDao = database.sessionSegmentDao()

		mockkObject(AppDatabase.Companion)
		every { AppDatabase.database(any()) } returns database
	}

	@After
	fun tearDown() {
		tempFiles.forEach { file ->
			if (file.exists()) {
				file.delete()
			}
		}
		unmockkObject(AppDatabase.Companion)
		database.close()
	}

	@Test
	fun `tracking samples persisted in Room export to valid json with session snapshot`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val exporter = JsonExporter(TestDispatchersProvider(dispatcher))
		val baseTimeMs = 1_725_000_000_000L
		val inRangeSamples = listOf(
			createLocationSample(
				timeMs = baseTimeMs,
				latitude = 50.087451,
				longitude = 14.420671,
				altitudeM = 214.3f,
				speedMps = 1.3f,
				accuracyM = 4.2f,
			),
			createLocationSample(
				timeMs = baseTimeMs + 30_000L,
				latitude = 50.087981,
				longitude = 14.421219,
				altitudeM = 216.8f,
				speedMps = 1.5f,
				accuracyM = 4.8f,
			),
			createLocationSample(
				timeMs = baseTimeMs + 60_000L,
				latitude = 50.088512,
				longitude = 14.421845,
				altitudeM = 219.1f,
				speedMps = 1.6f,
				accuracyM = 5.1f,
			),
		)
		val outOfRangeSample = createLocationSample(
			timeMs = baseTimeMs - 120_000L,
			latitude = 49.0,
			longitude = 13.0,
			altitudeM = 100f,
			speedMps = 0.5f,
			accuracyM = 25f,
		)
		val dateRange = inRangeSamples.first().timeMs..inRangeSamples.last().timeMs

		locationSampleDao.insert(listOf(outOfRangeSample) + inRangeSamples)
		sessionSegmentDao.insert(
			createSessionSegment(
				startTimeMs = dateRange.first,
				endTimeMs = dateRange.last,
				sampleCount = inRangeSamples.size,
				distanceM = 185.4f,
				steps = 248,
			),
		)
		database.wifiObservationDao().insert(
			WifiObservation(
				timeMs = baseTimeMs + 15_000L,
				bssid = "00:11:22:33:44:55",
				ssid = "Tracker",
				capabilities = "[WPA2]",
				frequency = 5180,
				level = -45,
				latE7 = inRangeSamples.first().latE7,
				lonE7 = inRangeSamples.first().lonE7,
				provenance = CoordinateProvenance.DIRECT,
				createdAt = baseTimeMs + 15_000L,
			),
		)
		database.cellSampleDao().insert(
			CellSample(
				timeMs = baseTimeMs + 45_000L,
				cellId = 1234L,
				lac = 5,
				mcc = 230,
				mnc = 1,
				networkType = 13,
				signalStrength = 40,
				latE7 = inRangeSamples.last().latE7,
				lonE7 = inRangeSamples.last().lonE7,
				provenance = CoordinateProvenance.DIRECT,
				createdAt = baseTimeMs + 45_000L,
			),
		)

		locationSampleDao.countBetween(dateRange.first, dateRange.last) shouldBe inRangeSamples.size

		val outputFile = createTempExportFile()
		lateinit var exportResult: ExportResult.Success
		outputFile.outputStream().use { outputStream ->
			exportResult = exporter.export(
				context = context,
				locationData = loadPersistedLocations(dateRange),
				outputStream = outputStream,
				dateRange = dateRange,
			).shouldBeInstanceOf()
		}
		exportResult.recordCount shouldBe inRangeSamples.size
		exportResult.maxTimeMs shouldBe inRangeSamples.last().timeMs
		(exportResult.maxId ?: 0L) shouldBeGreaterThan 0L

		outputFile.exists() shouldBe true
		outputFile.length() shouldBeGreaterThan 0L

		val payload = outputFile.readText(Charsets.UTF_8)
		payload shouldContain "\"timeMs\":${inRangeSamples.first().timeMs}"
		payload shouldContain "\"timeMs\":${inRangeSamples.last().timeMs}"
		payload.contains("\"timeMs\":${outOfRangeSample.timeMs}") shouldBe false

		val record = JSONArray(payload).getJSONObject(0)
		record.getInt("schemaVersion") shouldBe 2
		val exportedLocations = record.getJSONArray("locations")
		exportedLocations.length() shouldBe inRangeSamples.size
		inRangeSamples.forEachIndexed { index, expected ->
			val exported = exportedLocations.getJSONObject(index)
			exported.getLong("timeMs") shouldBe expected.timeMs
			(abs(exported.getDouble("latitude") - expected.latitude()) < 0.0000001) shouldBe true
			(abs(exported.getDouble("longitude") - expected.longitude()) < 0.0000001) shouldBe true
			exported.getDouble("altitudeM").toFloat() shouldBe expected.altitudeM
			exported.getDouble("speedMps").toFloat() shouldBe expected.speedMps
			exported.getDouble("horizontalAccuracyM").toFloat() shouldBe expected.hAccM
		}

		val session = record.getJSONObject("session")
		session.getLong("startTimeMs") shouldBe dateRange.first
		session.getLong("endTimeMs") shouldBe dateRange.last
		session.getInt("sampleCount") shouldBe inRangeSamples.size
		session.getDouble("distanceM").toFloat() shouldBe 185.4f
		session.getInt("steps") shouldBe 248
		record.getJSONArray("wifiObservations").getJSONObject(0).getString("bssid") shouldBe "00:11:22:33:44:55"
		record.getJSONArray("cellSamples").getJSONObject(0).getLong("cellId") shouldBe 1234L
	}

	@Test
	fun `json export includes sessions beyond legacy five hundred row trip cap`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val exporter = JsonExporter(TestDispatchersProvider(dispatcher))
		val baseTimeMs = 1_725_000_000_000L
		val sessionCount = 505
		repeat(sessionCount) { index ->
			val start = baseTimeMs + index * 60_000L
			sessionSegmentDao.insert(
				createSessionSegment(
					startTimeMs = start,
					endTimeMs = start + 30_000L,
					sampleCount = 1,
					distanceM = index.toFloat(),
					steps = index + 1,
				),
			)
		}

		val outputFile = createTempExportFile()
		outputFile.outputStream().use { outputStream ->
			exporter.export(
				context = context,
				locationData = emptySequence(),
				outputStream = outputStream,
				dateRange = baseTimeMs..(baseTimeMs + sessionCount * 60_000L),
			).shouldBeInstanceOf<ExportResult.Success>()
		}

		JSONArray(outputFile.readText(Charsets.UTF_8)).length() shouldBe sessionCount
	}

	@Test
	fun `json export includes a straddling session with its complete own-range data`() = runTest {
		val exporter = JsonExporter(TestDispatchersProvider(StandardTestDispatcher(testScheduler)))
		val sessionStart = 10_000L
		val sessionEnd = 30_000L
		val samples = listOf(
			createLocationSample(10_000L, 50.0, 14.0, 100f, 1f, 5f),
			createLocationSample(20_000L, 50.1, 14.1, 101f, 1f, 5f),
			createLocationSample(30_000L, 50.2, 14.2, 102f, 1f, 5f),
		)
		locationSampleDao.insert(samples)
		sessionSegmentDao.insert(
			createSessionSegment(
				startTimeMs = sessionStart,
				endTimeMs = sessionEnd,
				sampleCount = samples.size,
				distanceM = 50f,
				steps = 10,
			),
		)

		val outputFile = createTempExportFile()
		outputFile.outputStream().use { stream ->
			exporter.export(
				context = context,
				locationData = emptySequence(),
				outputStream = stream,
				dateRange = 20_000L..25_000L,
			).shouldBeInstanceOf<ExportResult.Success>()
		}

		val records = JSONArray(outputFile.readText(Charsets.UTF_8))
		records.length() shouldBe 1
		val record = records.getJSONObject(0)
		record.getJSONObject("session").getLong("startTimeMs") shouldBe sessionStart
		record.getJSONArray("locations").length() shouldBe samples.size
		record.getJSONArray("locations").getJSONObject(0).getLong("timeMs") shouldBe sessionStart
		record.getJSONArray("locations").getJSONObject(2).getLong("timeMs") shouldBe sessionEnd
	}

	@Test
	fun `json export preserves locations outside every session in an orphaned data record`() = runTest {
		val exporter = JsonExporter(TestDispatchersProvider(StandardTestDispatcher(testScheduler)))
		val orphan = createLocationSample(40_000L, 50.3, 14.3, 103f, 1f, 5f)
		locationSampleDao.insert(orphan)

		val outputFile = createTempExportFile()
		val result = outputFile.outputStream().use { stream ->
			exporter.export(
				context = context,
				locationData = emptySequence(),
				outputStream = stream,
				dateRange = 35_000L..45_000L,
			).shouldBeInstanceOf<ExportResult.Success>()
		}

		result.recordCount shouldBe 1
		result.maxTimeMs shouldBe orphan.timeMs
		val record = JSONArray(outputFile.readText(Charsets.UTF_8)).getJSONObject(0)
		record.getBoolean("orphanedData") shouldBe true
		record.getJSONArray("locations").getJSONObject(0).getLong("timeMs") shouldBe orphan.timeMs
	}

	@Test
	fun `composite cursor skips older ids and includes a newer id at the same timestamp`() = runTest {
		val timeMs = 50_000L
		val ids = locationSampleDao.insert(
			listOf(
				createLocationSample(timeMs, 50.0, 14.0, 100f, 1f, 5f),
				createLocationSample(timeMs, 50.1, 14.1, 101f, 1f, 5f),
				createLocationSample(timeMs, 50.2, 14.2, 102f, 1f, 5f),
			),
		)

		val exportedIds = pagedLocationSequence(
			locationSampleDao = locationSampleDao,
			fromMs = timeMs,
			toMs = timeMs,
			pageSize = 2,
			initialAfterTimeMs = timeMs,
			initialAfterId = ids[1],
		).map { it.id }.toList()

		exportedIds shouldBe listOf(ids[2])
	}

	private suspend fun loadPersistedLocations(dateRange: LongRange): Sequence<LocationSample> {
		val collected = mutableListOf<LocationSample>()
		var afterTimeMs: Long? = null
		var afterId: Long? = null

		while (true) {
			val chunk = locationSampleDao.getChunkBetweenOrdered(
				fromMs = dateRange.first,
				toMs = dateRange.last,
				afterTimeMs = afterTimeMs,
				afterId = afterId,
				limit = 64,
			)
			if (chunk.isEmpty()) {
				break
			}

			collected += chunk.map { it.toModel() }
			val last = chunk.last()
			afterTimeMs = last.timeMs
			afterId = last.id
		}

		return collected.asSequence()
	}

	private fun createTempExportFile(): File =
		File(context.cacheDir, "json-export-room-e2e-${System.nanoTime()}.json").also(tempFiles::add)

	private fun createLocationSample(
		timeMs: Long,
		latitude: Double,
		longitude: Double,
		altitudeM: Float,
		speedMps: Float,
		accuracyM: Float,
	): EntityLocationSample = EntityLocationSample(
		timeMs = timeMs,
		elapsedRealtimeNanos = timeMs * 1_000_000L,
		latE7 = (latitude * 1e7).toInt(),
		lonE7 = (longitude * 1e7).toInt(),
		altitudeM = altitudeM,
		rawGpsAltitudeM = altitudeM,
		hAccM = accuracyM,
		vAccM = accuracyM + 1f,
		speedMps = speedMps,
		speedAccuracyMps = 0.4f,
		provider = "fused",
		quality = SampleQuality.HIGH,
		motionState = MotionState.MOVING,
		policy = "PASSIVE_LOW",
		bucketId = null,
		createdAt = timeMs,
	)

	private fun createSessionSegment(
		startTimeMs: Long,
		endTimeMs: Long,
		sampleCount: Int,
		distanceM: Float,
		steps: Int,
	): SessionSegment = SessionSegment(
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		distanceM = distanceM,
		steps = steps,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = sampleCount,
		source = SegmentSource.USER_CREATED.toEntity(),
		inferenceVersion = "test",
		createdAt = endTimeMs,
	)

	private fun EntityLocationSample.latitude(): Double = (latE7 ?: 0) / 1e7

	private fun EntityLocationSample.longitude(): Double = (lonE7 ?: 0) / 1e7
}
