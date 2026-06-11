package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.base.database.data.LocationSample as EntityLocationSample
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.io.File
import kotlin.math.abs

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
@DisplayName("JsonExporter Room integration")
class JsonExporterRoomIntegrationTest {

	private lateinit var context: Context
	private lateinit var database: AppDatabase
	private lateinit var locationSampleDao: LocationSampleDao
	private lateinit var sessionSegmentDao: SessionSegmentDao
	private val tempFiles = mutableListOf<File>()

	@BeforeEach
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		locationSampleDao = database.locationSampleDao()
		sessionSegmentDao = database.sessionSegmentDao()

		mockkObject(AppDatabase.Companion)
		every { AppDatabase.database(any()) } returns database
	}

	@AfterEach
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

		locationSampleDao.countBetween(dateRange.first, dateRange.last) shouldBe inRangeSamples.size

		val outputFile = createTempExportFile()
		outputFile.outputStream().use { outputStream ->
			exporter.export(
				context = context,
				locationData = loadPersistedLocations(dateRange),
				outputStream = outputStream,
				dateRange = dateRange,
			) shouldBe ExportResult.Success
		}

		outputFile.exists() shouldBe true
		outputFile.length() shouldBeGreaterThan 0L

		val payload = outputFile.readText(Charsets.UTF_8)
		payload shouldContain "\"time\":${inRangeSamples.first().timeMs}"
		payload shouldContain "\"time\":${inRangeSamples.last().timeMs}"
		payload.contains("\"time\":${outOfRangeSample.timeMs}") shouldBe false

		val document = JSONObject(payload)
		document.getInt("schema") shouldBe 1
		document.getLong("dateRangeStart") shouldBe dateRange.first
		document.getLong("dateRangeEnd") shouldBe dateRange.last

		val exportedLocations = document.getJSONArray("locations")
		exportedLocations.length() shouldBe inRangeSamples.size
		inRangeSamples.forEachIndexed { index, expected ->
			val exported = exportedLocations.getJSONObject(index)
			exported.getLong("time") shouldBe expected.timeMs
			(abs(exported.getDouble("lat") - expected.latitude()) < 0.0000001) shouldBe true
			(abs(exported.getDouble("lon") - expected.longitude()) < 0.0000001) shouldBe true
			exported.getDouble("alt").toFloat() shouldBe expected.altitudeM
			exported.getDouble("spd").toFloat() shouldBe expected.speedMps
			exported.getDouble("acc").toFloat() shouldBe expected.hAccM
		}

		val exportedSessions = document.getJSONArray("sessions")
		exportedSessions.length() shouldBe 1
		val session = exportedSessions.getJSONObject(0)
		session.getLong("start") shouldBe dateRange.first
		session.getLong("end") shouldBe dateRange.last
		session.getInt("collections") shouldBe inRangeSamples.size
		session.getDouble("distanceInM").toFloat() shouldBe 185.4f
		session.getBoolean("isUserInitiated") shouldBe true
		session.getInt("steps") shouldBe 248
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
			) shouldBe ExportResult.Success
		}

		val document = JSONObject(outputFile.readText(Charsets.UTF_8))
		document.getJSONArray("sessions").length() shouldBe sessionCount
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
		source = SegmentSource.USER_CREATED,
		inferenceVersion = "test",
		createdAt = endTimeMs,
	)

	private fun EntityLocationSample.latitude(): Double = (latE7 ?: 0) / 1e7

	private fun EntityLocationSample.longitude(): Double = (lonE7 ?: 0) / 1e7
}
