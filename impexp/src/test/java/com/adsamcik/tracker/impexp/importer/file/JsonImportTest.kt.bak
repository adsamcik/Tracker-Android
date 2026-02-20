package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.dao.SessionDataDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.junit5.RobolectricExtension
import java.io.ByteArrayInputStream

/**
 * Unit tests for [JsonImport].
 *
 * Uses Robolectric because [JsonImport] relies on [android.util.JsonReader]
 * which is only available with the Android runtime.
 */
@ExtendWith(RobolectricExtension::class)
class JsonImportTest {

	private val jsonImport = JsonImport()
	private lateinit var mockDatabase: AppDatabase
	private lateinit var mockLocationDao: LocationDataDao
	private lateinit var mockSessionDao: SessionDataDao
	private lateinit var mockContext: Context

	private val capturedLocations = mutableListOf<DatabaseLocation>()
	private val capturedSessions = mutableListOf<TrackerSession>()
	private var locationInsertCallCount = 0

	@BeforeEach
	fun setUp() {
		capturedLocations.clear()
		capturedSessions.clear()
		locationInsertCallCount = 0

		mockLocationDao = mockk {
			every { insert(any<Collection<DatabaseLocation>>()) } answers {
				val batch = firstArg<Collection<DatabaseLocation>>()
				capturedLocations.addAll(batch)
				locationInsertCallCount++
				batch.map { 0L }
			}
		}
		mockSessionDao = mockk {
			every { insert(any<TrackerSession>()) } answers {
				capturedSessions.add(firstArg())
				1L
			}
		}
		mockDatabase = mockk {
			every { locationDao() } returns mockLocationDao
			every { sessionDao() } returns mockSessionDao
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
	fun `imports valid JSON with locations`() = runTest {
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

		capturedLocations shouldHaveSize 2
		capturedLocations[0].location.latitude shouldBeExactly 50.0
		capturedLocations[0].location.longitude shouldBeExactly 14.0
		capturedLocations[0].location.altitude shouldBe 200.0
		capturedLocations[0].location.speed shouldBe 3.5f
		capturedLocations[0].activityInfo.activityType shouldBe 0
		capturedLocations[0].activityInfo.confidence shouldBe 100
		capturedLocations[1].location.latitude shouldBeExactly 50.1
	}

	@Test
	fun `imports valid JSON with sessions`() = runTest {
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

		capturedSessions shouldHaveSize 1
		val session = capturedSessions.first()
		session.start shouldBe 1700000000000L
		session.end shouldBe 1700001000000L
		session.collections shouldBe 50
		session.distanceInM shouldBe 1234.5f
		session.isUserInitiated shouldBe true
		session.steps shouldBe 500
	}

	// -- Schema Version --

	@Test
	fun `schema version 1 is accepted`() = runTest {
		val json = """{"schema": 1, "locations": [], "sessions": []}"""
		jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		// No exception means success
	}

	@Test
	fun `unsupported schema version throws`() = runTest {
		val json = """{"schema": 99, "locations": [], "sessions": []}"""
		shouldThrow<IllegalArgumentException> {
			jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		}
	}

	@Test
	fun `schema version 0 throws`() = runTest {
		val json = """{"schema": 0, "locations": [], "sessions": []}"""
		shouldThrow<IllegalArgumentException> {
			jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		}
	}

	@Test
	fun `negative schema version throws`() = runTest {
		val json = """{"schema": -1, "locations": [], "sessions": []}"""
		shouldThrow<IllegalArgumentException> {
			jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		}
	}

	// -- Location Validation --

	@Test
	fun `location with time 0 is skipped`() = runTest {
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

		capturedLocations shouldHaveSize 0
	}

	@Test
	fun `location with time before 2010 is skipped`() = runTest {
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

		capturedLocations shouldHaveSize 0
	}

	@Test
	fun `location with time after 2100 is skipped`() = runTest {
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

		capturedLocations shouldHaveSize 0
	}

	@Test
	fun `location with out-of-range latitude is skipped`() = runTest {
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

		capturedLocations shouldHaveSize 0
	}

	@Test
	fun `location with out-of-range longitude is skipped`() = runTest {
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

		capturedLocations shouldHaveSize 0
	}

	@Test
	fun `location with null alt and speed handles gracefully`() = runTest {
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

		capturedLocations shouldHaveSize 1
		capturedLocations[0].location.altitude shouldBe null
		capturedLocations[0].location.speed shouldBe null
		capturedLocations[0].location.horizontalAccuracy shouldBe null
	}

	@Test
	fun `preserves coordinate precision`() = runTest {
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

		capturedLocations shouldHaveSize 1
		capturedLocations[0].location.latitude shouldBeExactly 50.12345678
		capturedLocations[0].location.longitude shouldBeExactly 14.98765432
	}

	// -- Session Validation --

	@Test
	fun `session with start 0 is skipped`() = runTest {
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

		capturedSessions shouldHaveSize 0
	}

	@Test
	fun `session with end before start is skipped`() = runTest {
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

		capturedSessions shouldHaveSize 0
	}

	@Test
	fun `session with null steps defaults to 0`() = runTest {
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

		capturedSessions shouldHaveSize 1
		capturedSessions[0].steps shouldBe 0
		capturedSessions[0].isUserInitiated shouldBe false
	}

	// -- Unknown Fields --

	@Test
	fun `unknown top-level fields are skipped gracefully`() = runTest {
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
	}

	@Test
	fun `unknown location fields are skipped gracefully`() = runTest {
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

		capturedLocations shouldHaveSize 1
	}

	@Test
	fun `segments array is skipped`() = runTest {
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
	}

	// -- Batching --

	@Test
	fun `locations are batched by 200`() = runTest {
		val locationEntries = (0 until 450).joinToString(",") { i ->
			"""{"time": ${1700000000000L + i}, "lat": ${50.0 + i * 0.0001}, "lon": 14.0, "act": 0, "actConf": 0}"""
		}
		val json = """{"schema": 1, "locations": [$locationEntries], "sessions": []}"""

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedLocations shouldHaveSize 450
		// 200 + 200 + 50 = 3 batch inserts
		locationInsertCallCount shouldBe 3
	}

	// -- Empty JSON --

	@Test
	fun `empty locations and sessions arrays produce no inserts`() = runTest {
		val json = """{"schema": 1, "locations": [], "sessions": []}"""

		jsonImport.import(mockContext, mockDatabase, jsonStream(json))

		capturedLocations shouldHaveSize 0
		capturedSessions shouldHaveSize 0
	}

	// -- Malformed JSON --

	@Test
	fun `malformed JSON throws exception`() = runTest {
		val json = """{ not valid json }"""
		shouldThrow<Exception> {
			jsonImport.import(mockContext, mockDatabase, jsonStream(json))
		}
	}
}
