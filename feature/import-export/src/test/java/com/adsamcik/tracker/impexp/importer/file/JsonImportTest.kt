package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

/**
 * Unit tests for [JsonImport].
 *
 * Uses Robolectric because [JsonImport] relies on [android.util.JsonReader]
 * which is only available with the Android runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JsonImportTest {

	private val jsonImport = JsonImport()
	private lateinit var mockDatabase: AppDatabase
	private lateinit var mockLocationSampleDao: LocationSampleDao
	private lateinit var mockSegmentDao: SessionSegmentDao
	private lateinit var mockWifiObservationDao: WifiObservationDao
	private lateinit var mockCellSampleDao: CellSampleDao
	private lateinit var mockContext: Context

	private val capturedSamples = mutableListOf<LocationSample>()
	private val capturedSegments = mutableListOf<SessionSegment>()
	private var locationInsertCallCount = 0

	@Before
	fun setUp() {
		capturedSamples.clear()
		capturedSegments.clear()
		locationInsertCallCount = 0

		mockLocationSampleDao = mockk {
			coEvery { insert(any<Collection<LocationSample>>()) } answers {
				val batch = firstArg<Collection<LocationSample>>()
				capturedSamples.addAll(batch)
				locationInsertCallCount++
				batch.map { 0L }
			}
		}
		mockSegmentDao = mockk {
			coEvery { insert(any<SessionSegment>()) } answers {
				capturedSegments.add(firstArg())
				1L
			}
		}
		mockWifiObservationDao = mockk(relaxed = true)
		mockCellSampleDao = mockk(relaxed = true)
		mockDatabase = mockk {
			every { locationSampleDao() } returns mockLocationSampleDao
				every { sessionSegmentDao() } returns mockSegmentDao
			every { wifiObservationDao() } returns mockWifiObservationDao
			every { cellSampleDao() } returns mockCellSampleDao
		}
		mockContext = mockk(relaxed = true)
	}

	private fun jsonStream(json: String): FileImportStream {
		val bytes = json.toByteArray(Charsets.UTF_8)
		return FileImportStream(ByteArrayInputStream(bytes), "test.json")
	}

	// -- Properties --

	@Test
	fun `supported extensions contains json`() {
		jsonImport.supportedExtensions shouldBe listOf("json")
	}

	// -- Valid JSON Parsing --

	@Test
	fun `imports valid JSON with locations`()  { runTest {
		val json = """
			{
				"schema": 1,
				"exportedAt": 1700000000000,
				"locations": [
					{"time": 1700000000000, "lat": 50.0, "lon": 14.0, "alt": 200.0, "spd": 3.5, "acc": 10.0, "act": 0, "actConf": 100},
					{"time": 1700001000000, "lat": 50.1, "lon": 14.1, "alt": 210.0, "spd": 4.0, "acc": 8.0, "act": 1, "actConf": 80}
				],
				"sessions": []
			}
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedSamples shouldHaveSize 2
		capturedSamples[0].latE7 shouldBe (50.0 * 1e7).roundToInt()
		capturedSamples[0].lonE7 shouldBe (14.0 * 1e7).roundToInt()
		capturedSamples[0].altitudeM shouldBe 200.0f
		capturedSamples[0].speedMps shouldBe 3.5f
		capturedSamples[1].latE7 shouldBe (50.1 * 1e7).roundToInt()
	} }

	@Test
	fun `imports valid JSON with sessions`()  { runTest {
		val json = """
			{
				"schema": 1,
				"exportedAt": 1700000000000,
				"locations": [],
				"sessions": [
					{"id": 42, "start": 1700000000000, "end": 1700001000000, "collections": 50, "distanceInM": 1234.5, "isUserInitiated": true, "steps": 500}
				]
			}
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedSegments shouldHaveSize 1
		val segment = capturedSegments.first()
		segment.startTimeMs shouldBe 1700000000000L
		segment.endTimeMs shouldBe 1700001000000L
		segment.sampleCount shouldBe 50
		segment.distanceM shouldBe 1234.5f
		segment.steps shouldBe 500
	} }

	// -- Schema Version --

	@Test
	fun `schema version 1 is accepted`()  { runTest {
		val json = """{"schema": 1, "locations": [], "sessions": []}"""
		jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		// No exception means success
	} }

	@Test
	fun `unsupported schema version throws`()  { runTest {
		val json = """{"schema": 99, "locations": [], "sessions": []}"""
		shouldThrow<IllegalArgumentException> {
			jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		}
	} }

	@Test
	fun `schema version 0 throws`()  { runTest {
		val json = """{"schema": 0, "locations": [], "sessions": []}"""
		shouldThrow<IllegalArgumentException> {
			jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		}
	} }

	@Test
	fun `negative schema version throws`()  { runTest {
		val json = """{"schema": -1, "locations": [], "sessions": []}"""
		shouldThrow<IllegalArgumentException> {
			jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		}
	} }

	// -- Location Validation --

	@Test
	fun `location with time 0 is skipped`()  { runTest {
		val json = """
			{
				"schema": 1,
				"locations": [
					{"time": 0, "lat": 50.0, "lon": 14.0, "act": 0, "actConf": 0}
				],
				"sessions": []
			}
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedSamples shouldHaveSize 0
	} }

	@Test
	fun `location with time before 2010 is skipped`()  { runTest {
		val json = """
			{
				"schema": 1,
				"locations": [
					{"time": 1000000000000, "lat": 50.0, "lon": 14.0, "act": 0, "actConf": 0}
				],
				"sessions": []
			}
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedSamples shouldHaveSize 0
	} }

	@Test
	fun `location with time after 2100 is skipped`()  { runTest {
		val json = """
			{
				"schema": 1,
				"locations": [
					{"time": 5000000000000, "lat": 50.0, "lon": 14.0, "act": 0, "actConf": 0}
				],
				"sessions": []
			}
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedSamples shouldHaveSize 0
	} }

	@Test
	fun `location without coordinates preserves null coordinates`() = runTest {
		val json = """{"schema": 1, "locations": [{"time": 1700000000000}], "sessions": []}"""
		jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		capturedSamples shouldHaveSize 1
		capturedSamples.single().latE7 shouldBe null
		capturedSamples.single().lonE7 shouldBe null
	}

	@Test
	fun `location with NaN coordinate preserves null coordinates`() = runTest {
		val json = """{"schema": 1, "locations": [{"time": 1700000000000, "latitude": NaN, "longitude": 14.0}], "sessions": []}"""
		jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		capturedSamples shouldHaveSize 1
		capturedSamples.single().latE7 shouldBe null
		capturedSamples.single().lonE7 shouldBe null
	}

	@Test
	fun `location with out-of-range latitude preserves null coordinates`()  { runTest {
		val json = """
			{
				"schema": 1,
				"locations": [
					{"time": 1700000000000, "lat": 91.0, "lon": 14.0, "act": 0, "actConf": 0}
				],
				"sessions": []
			}
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedSamples shouldHaveSize 1
		capturedSamples.single().latE7 shouldBe null
		capturedSamples.single().lonE7 shouldBe null
	} }

	@Test
	fun `location with out-of-range longitude preserves null coordinates`()  { runTest {
		val json = """
			{
				"schema": 1,
				"locations": [
					{"time": 1700000000000, "lat": 50.0, "lon": 181.0, "act": 0, "actConf": 0}
				],
				"sessions": []
			}
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedSamples shouldHaveSize 1
		capturedSamples.single().latE7 shouldBe null
		capturedSamples.single().lonE7 shouldBe null
	} }

	@Test
	fun `location with null alt and speed handles gracefully`()  { runTest {
		val json = """
			{
				"schema": 1,
				"locations": [
					{"time": 1700000000000, "lat": 50.0, "lon": 14.0, "alt": null, "spd": null, "acc": null, "act": 0, "actConf": 0}
				],
				"sessions": []
			}
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedSamples shouldHaveSize 1
		capturedSamples[0].altitudeM shouldBe null
		capturedSamples[0].speedMps shouldBe null
		capturedSamples[0].hAccM shouldBe null
	} }

	@Test
	fun `preserves coordinate precision`()  { runTest {
		val json = """
			{
				"schema": 1,
				"locations": [
					{"time": 1700000000000, "lat": 50.12345678, "lon": 14.98765432, "act": 0, "actConf": 0}
				],
				"sessions": []
			}
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedSamples shouldHaveSize 1
		capturedSamples[0].latE7 shouldBe (50.12345678 * 1e7).roundToInt()
		capturedSamples[0].lonE7 shouldBe (14.98765432 * 1e7).roundToInt()
	} }

	// -- Session Validation --

	@Test
	fun `session with start 0 is skipped`()  { runTest {
		val json = """
			{
				"schema": 1,
				"locations": [],
				"sessions": [
					{"id": 1, "start": 0, "end": 1700001000000, "collections": 10, "distanceInM": 100.0, "isUserInitiated": true}
				]
			}
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedSegments shouldHaveSize 0
	} }

	@Test
	fun `session with end before start is skipped`()  { runTest {
		val json = """
			{
				"schema": 1,
				"locations": [],
				"sessions": [
					{"id": 1, "start": 1700001000000, "end": 1700000000000, "collections": 10, "distanceInM": 100.0, "isUserInitiated": true}
				]
			}
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedSegments shouldHaveSize 0
	} }

	@Test
	fun `session with null steps defaults to 0`()  { runTest {
		val json = """
			{
				"schema": 1,
				"locations": [],
				"sessions": [
					{"id": 1, "start": 1700000000000, "end": 1700001000000, "collections": 10, "distanceInM": 100.0, "isUserInitiated": false, "steps": null}
				]
			}
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedSegments shouldHaveSize 1
		capturedSegments[0].steps shouldBe null
	} }

	// -- Unknown Fields --

	@Test
	fun `unknown top-level fields are skipped gracefully`()  { runTest {
		val json = """
			{
				"schema": 1,
				"exportedAt": 1700000000000,
				"unknownField": "some value",
				"locations": [],
				"sessions": []
			}
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		// No exception means success
	} }

	@Test
	fun `unknown location fields are skipped gracefully`()  { runTest {
		val json = """
			{
				"schema": 1,
				"locations": [
					{"time": 1700000000000, "lat": 50.0, "lon": 14.0, "act": 0, "actConf": 0, "unknownField": 42}
				],
				"sessions": []
			}
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedSamples shouldHaveSize 1
	} }

	@Test
	fun `segments array is skipped`()  { runTest {
		val json = """
			{
				"schema": 1,
				"locations": [],
				"sessions": [],
				"segments": [{"id": 1, "start": 0, "end": 100}]
			}
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		// No exception means segments were skipped
	} }

	// -- Batching --

	@Test
	fun `locations are batched by 200`()  { runTest {
		val locationEntries = (0 until 450).joinToString(",") { i ->
			"""{"time": ${1700000000000L + i}, "lat": ${50.0 + i * 0.0001}, "lon": 14.0, "act": 0, "actConf": 0}"""
		}
		val json = """{"schema": 1, "locations": [$locationEntries], "sessions": []}"""

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedSamples shouldHaveSize 450
		// 200 + 200 + 50 = 3 batch inserts
		locationInsertCallCount shouldBe 3
	} }

	@Test
	fun `wifi observations are batched by 200`() = runTest {
		val observations = (0 until 450).joinToString(",") { index ->
			"""{"timeMs":${1700000000000L + index},"bssid":"00:00:00:00:00:${index % 100}","ssid":"test","capabilities":"","frequencyMhz":2412,"levelDbm":-50}"""
		}
		val json = """[{"schemaVersion":2,"wifiObservations":[$observations]}]"""
		jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		coVerify(exactly = 3) { mockWifiObservationDao.insert(any<Collection<com.adsamcik.tracker.shared.base.database.data.WifiObservation>>()) }
	}

	@Test
	fun `cell samples are batched by 200`() = runTest {
		val samples = (0 until 450).joinToString(",") { index ->
			"""{"timeMs":${1700000000000L + index},"cellId":$index,"lac":1,"mcc":1,"mnc":1,"networkType":1,"signalStrength":-90}"""
		}
		val json = """[{"schemaVersion":2,"cellSamples":[$samples]}]"""
		jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		coVerify(exactly = 3) { mockCellSampleDao.insert(any<Collection<com.adsamcik.tracker.shared.base.database.data.CellSample>>()) }
	}

	// -- Empty JSON --

	@Test
	fun `empty locations and sessions arrays produce no inserts`()  { runTest {
		val json = """{"schema": 1, "locations": [], "sessions": []}"""

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedSamples shouldHaveSize 0
		capturedSegments shouldHaveSize 0
	} }

	// -- Malformed JSON --

	@Test
	fun `malformed JSON throws exception`()  { runTest {
		val json = """{ not valid json }"""
		shouldThrow<Exception> {
			jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		}
	} }
}
