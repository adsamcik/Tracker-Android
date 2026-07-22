package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
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
	private val capturedWifiObservations = mutableListOf<WifiObservation>()
	private val capturedCellSamples = mutableListOf<CellSample>()
	private var locationInsertCallCount = 0
	private var wifiInsertCallCount = 0
	private var cellInsertCallCount = 0

	@Before
	fun setUp() {
		capturedSamples.clear()
		capturedSegments.clear()
		capturedWifiObservations.clear()
		capturedCellSamples.clear()
		locationInsertCallCount = 0
		wifiInsertCallCount = 0
		cellInsertCallCount = 0

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
		mockWifiObservationDao = mockk {
			coEvery { insert(any<Collection<WifiObservation>>()) } answers {
				val batch = firstArg<Collection<WifiObservation>>()
				capturedWifiObservations.addAll(batch)
				wifiInsertCallCount++
				batch.map { 0L }
			}
		}
		mockCellSampleDao = mockk {
			coEvery { insert(any<Collection<CellSample>>()) } answers {
				val batch = firstArg<Collection<CellSample>>()
				capturedCellSamples.addAll(batch)
				cellInsertCallCount++
				batch.map { 0L }
			}
		}
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

	private suspend fun importSignalObservations(
		latitude: String?,
		longitude: String?,
		provenance: String? = null,
	) =
		jsonImport.import(
			mockContext,
			mockDatabase,
			jsonStream(
				"""[{"schemaVersion":2,"wifiObservations":[${wifiObservationJson(latitude, longitude, provenance)}],"cellSamples":[${cellSampleJson(latitude, longitude, provenance)}]}]""",
			),
		)

	private fun wifiObservationJson(latitude: String?, longitude: String?, provenance: String?): String =
		"""{"timeMs":1700000000000,"bssid":"00:00:00:00:00:01","ssid":"test","capabilities":"","frequencyMhz":2412,"levelDbm":-50${coordinateFieldsJson(latitude, longitude, provenance)}}"""

	private fun cellSampleJson(latitude: String?, longitude: String?, provenance: String?): String =
		"""{"timeMs":1700000000000,"cellId":1,"lac":1,"mcc":1,"mnc":1,"networkType":1,"signalStrength":-90${coordinateFieldsJson(latitude, longitude, provenance)}}"""

	private fun coordinateFieldsJson(latitude: String?, longitude: String?, provenance: String?): String =
		listOfNotNull(
			latitude?.let { "\"latitude\":$it" },
			longitude?.let { "\"longitude\":$it" },
			provenance?.let { "\"coordinateProvenance\":\"$it\"" },
		).joinToString(",").let { fields -> if (fields.isEmpty()) "" else ",$fields" }

	private fun assertCapturedSignalCoordinates(expectedLatitudeE7: Int?, expectedLongitudeE7: Int?) {
		capturedWifiObservations shouldHaveSize 1
		capturedCellSamples shouldHaveSize 1
		capturedWifiObservations.single().latE7 shouldBe expectedLatitudeE7
		capturedWifiObservations.single().lonE7 shouldBe expectedLongitudeE7
		capturedCellSamples.single().latE7 shouldBe expectedLatitudeE7
		capturedCellSamples.single().lonE7 shouldBe expectedLongitudeE7
	}

	private fun clearCapturedSignalObservations() {
		capturedWifiObservations.clear()
		capturedCellSamples.clear()
	}

	private data class JsonCoordinateCase(
		val description: String,
		val latitude: String?,
		val longitude: String?,
	)

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
	fun `imports JSON schema 3 altitude contract without conflating raw and processed altitude`() = runTest {
		val json = """
			[{"schemaVersion":3,"locations":[{
				"timeMs":1700000000000,
				"latitude":50.0,
				"longitude":14.0,
				"altitudeM":420.0,
				"rawGpsAltitudeM":500.0,
				"altitudeDatum":"fused_android_model_msl",
				"altitudeSource":"fused_gps_barometer",
				"altitudeConversionStatus":"success",
				"rawGpsAltitudeDatum":"wgs84_ellipsoid",
				"altitudeModelVersion":1,
				"altitudeEstimatorVersion":1,
				"altitudeCalibrationVersion":1
			}]}]
		""".trimIndent()

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		val sample = capturedSamples.single()
		sample.altitudeM shouldBe 420f
		sample.rawGpsAltitudeM shouldBe 500f
		sample.altitudeDatum shouldBe AltitudeDatum.FUSED_ANDROID_MODEL_MSL
		sample.altitudeSource shouldBe AltitudeSource.FUSED_GPS_BAROMETER
		sample.altitudeConversionStatus shouldBe AltitudeConversionStatus.SUCCESS
		sample.rawGpsAltitudeDatum shouldBe AltitudeDatum.WGS84_ELLIPSOID
		sample.altitudeModelVersion shouldBe 1
		sample.estimatorVersion shouldBe 1
		sample.calibrationVersion shouldBe 1
	}

	@Test
	fun `bare historical JSON altitude is imported with unknown datum`() = runTest {
		val json = """{"schema":1,"locations":[{"time":1700000000000,"lat":50.0,"lon":14.0,"alt":200.0}],"sessions":[]}"""

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		val sample = capturedSamples.single()
		sample.altitudeM shouldBe 200f
		sample.altitudeDatum shouldBe AltitudeDatum.UNKNOWN_LEGACY
		sample.altitudeSource shouldBe AltitudeSource.IMPORTED
		sample.altitudeConversionStatus shouldBe AltitudeConversionStatus.UNKNOWN_LEGACY
		sample.rawGpsAltitudeDatum shouldBe AltitudeDatum.UNKNOWN_LEGACY
	}

	@Test
	fun `unknown JSON altitude contract values decode conservatively`() = runTest {
		val json = """[{"schemaVersion":3,"locations":[{"timeMs":1700000000000,"altitudeDatum":"future","altitudeSource":"future","altitudeConversionStatus":"future","rawGpsAltitudeDatum":"future"}]}]"""

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		val sample = capturedSamples.single()
		sample.altitudeDatum shouldBe AltitudeDatum.UNKNOWN_LEGACY
		sample.altitudeSource shouldBe AltitudeSource.UNKNOWN_LEGACY
		sample.altitudeConversionStatus shouldBe AltitudeConversionStatus.UNKNOWN_LEGACY
		sample.rawGpsAltitudeDatum shouldBe AltitudeDatum.UNKNOWN_LEGACY
	}

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

	// -- Wi-Fi and Cell Coordinate Validation --

	@Test
	fun `wifi and cell observations round valid coordinate pairs`() = runTest {
		val result = importSignalObservations("50.12345678", "14.98765432")

		result.successCount shouldBe 2
		assertCapturedSignalCoordinates(
			expectedLatitudeE7 = (50.12345678 * 1e7).roundToInt(),
			expectedLongitudeE7 = (14.98765432 * 1e7).roundToInt(),
		)
	}

	@Test
	fun `wifi and cell observations accept coordinate boundaries`() = runTest {
		listOf(
			Triple("90.0", "180.0", 0),
			Triple("-90.0", "-180.0", 0),
			Triple("0.0", "180.0", -1_800_000_000),
		).forEach { (latitude, longitude, expectedLongitudeE7) ->
			clearCapturedSignalObservations()

			val result = importSignalObservations(latitude, longitude)

			result.successCount shouldBe 2
			assertCapturedSignalCoordinates(
				expectedLatitudeE7 = latitude.toDouble().times(1e7).roundToInt(),
				expectedLongitudeE7 = expectedLongitudeE7,
			)
		}
	}

	@Test
	fun `wifi and cell observations clear unusable coordinate pairs but are imported`() = runTest {
		val unusableCoordinateCases = listOf(
			JsonCoordinateCase("latitude above the upper boundary", "90.0000001", "14.0"),
			JsonCoordinateCase("latitude below the lower boundary", "-90.0000001", "14.0"),
			JsonCoordinateCase("longitude above the upper boundary", "50.0", "180.0000001"),
			JsonCoordinateCase("longitude below the lower boundary", "50.0", "-180.0000001"),
			JsonCoordinateCase("NaN latitude", "NaN", "14.0"),
			JsonCoordinateCase("NaN longitude", "50.0", "NaN"),
			JsonCoordinateCase("infinite latitude", "Infinity", "14.0"),
			JsonCoordinateCase("infinite longitude", "50.0", "Infinity"),
			JsonCoordinateCase("only latitude", "50.0", null),
			JsonCoordinateCase("only longitude", null, "14.0"),
			JsonCoordinateCase("null latitude", "null", "14.0"),
			JsonCoordinateCase("null longitude", "50.0", "null"),
			JsonCoordinateCase("very large finite latitude", "1e300", "14.0"),
			JsonCoordinateCase("very large finite longitude", "50.0", "1e300"),
		)

		unusableCoordinateCases.forEach { case ->
			clearCapturedSignalObservations()
			val result = importSignalObservations(case.latitude, case.longitude)

			withClue(case.description) {
				result.successCount shouldBe 2
				assertCapturedSignalCoordinates(expectedLatitudeE7 = null, expectedLongitudeE7 = null)
			}
		}
	}

	@Test
	fun `wifi and cell observations reset provenance when coordinates are cleared`() = runTest {
		val result = importSignalObservations("91.0", "14.0", provenance = "DIRECT")

		result.successCount shouldBe 2
		assertCapturedSignalCoordinates(expectedLatitudeE7 = null, expectedLongitudeE7 = null)
		capturedWifiObservations.single().provenance shouldBe CoordinateProvenance.UNKNOWN
		capturedCellSamples.single().provenance shouldBe CoordinateProvenance.UNKNOWN
	}

	@Test
	fun `valid imported coordinates retain unknown provenance and use positive half E7 ties`() = runTest {
		val result = importSignalObservations("0.00000005", "-0.00000005", provenance = "NOT_A_SOURCE")

		result.successCount shouldBe 2
		assertCapturedSignalCoordinates(expectedLatitudeE7 = 1, expectedLongitudeE7 = 0)
		capturedWifiObservations.single().provenance shouldBe CoordinateProvenance.UNKNOWN
		capturedCellSamples.single().provenance shouldBe CoordinateProvenance.UNKNOWN
	}

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
		capturedWifiObservations shouldHaveSize 450
		wifiInsertCallCount shouldBe 3
	}

	@Test
	fun `cell samples are batched by 200`() = runTest {
		val samples = (0 until 450).joinToString(",") { index ->
			"""{"timeMs":${1700000000000L + index},"cellId":$index,"lac":1,"mcc":1,"mnc":1,"networkType":1,"signalStrength":-90}"""
		}
		val json = """[{"schemaVersion":2,"cellSamples":[$samples]}]"""
		jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		capturedCellSamples shouldHaveSize 450
		cellInsertCallCount shouldBe 3
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
