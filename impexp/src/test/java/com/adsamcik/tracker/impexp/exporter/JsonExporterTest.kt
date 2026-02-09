package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class JsonExporterTest {

	private val exporter = JsonExporter()

	private fun export(
		locations: Sequence<DatabaseLocation> = emptySequence(),
		sessions: List<SessionSnapshot> = emptyList(),
		segments: List<SegmentSnapshot> = emptyList(),
		dateRange: LongRange? = null,
	): String {
		val out = ByteArrayOutputStream()
		val result = exporter.writeJson(out, locations, sessions, segments, dateRange)
		result shouldBe ExportResult.Success
		return out.toString(Charsets.UTF_8.name())
	}

	@Test
	fun `empty export produces valid schema`() {
		val json = export()
		json shouldStartWith "{\"schema\":1"
		json shouldContain "\"locations\":[]"
		json shouldContain "\"sessions\":[]"
		json shouldContain "\"segments\":[]"
	}

	@Test
	fun `export with date range includes boundaries`() {
		val json = export(dateRange = 1000L..2000L)
		json shouldContain "\"dateRangeStart\":1000"
		json shouldContain "\"dateRangeEnd\":2000"
	}

	@Test
	fun `single location includes all fields`() {
		val loc = testLocation(time = 100L, lat = 51.5, lon = 7.1, alt = 120.0, speed = 3.5f)
		val json = export(locations = sequenceOf(loc))
		json shouldContain "\"time\":100"
		json shouldContain "\"lat\":51.5"
		json shouldContain "\"lon\":7.1"
		json shouldContain "\"alt\":120.0"
		json shouldContain "\"spd\":3.5"
	}

	@Test
	fun `location without altitude omits alt field`() {
		val loc = testLocation(time = 200L, lat = 50.0, lon = 8.0, alt = null)
		val json = export(locations = sequenceOf(loc))
		json shouldContain "\"time\":200"
		json.contains("\"alt\"") shouldBe false
	}

	@Test
	fun `multiple locations separated by commas`() {
		val locs = (1..3).map { testLocation(time = it.toLong(), lat = 50.0 + it, lon = 7.0) }
		val json = export(locations = locs.asSequence())
		// Count location objects
		val count = "\"time\"".toRegex().findAll(json).count()
		count shouldBe 3
	}

	@Test
	fun `session snapshot serialization`() {
		val session = SessionSnapshot(
			id = 42,
			start = 1000L,
			end = 2000L,
			collections = 50,
			distanceInM = 1234.5f,
			isUserInitiated = true,
			steps = 500,
		)
		val json = export(sessions = listOf(session))
		json shouldContain "\"id\":42"
		json shouldContain "\"start\":1000"
		json shouldContain "\"end\":2000"
		json shouldContain "\"collections\":50"
		json shouldContain "\"distanceInM\":1234.5"
		json shouldContain "\"isUserInitiated\":true"
		json shouldContain "\"steps\":500"
	}

	@Test
	fun `segment snapshot serialization`() {
		val segment = SegmentSnapshot(
			id = 7,
			startTimeMs = 3000L,
			endTimeMs = 4000L,
			distanceM = 567.8f,
			sampleCount = 25,
			source = "INFERRED_HIGH_CONFIDENCE",
			steps = 200,
			primaryActivity = 7,
		)
		val json = export(segments = listOf(segment))
		json shouldContain "\"source\":\"INFERRED_HIGH_CONFIDENCE\""
		json shouldContain "\"sampleCount\":25"
		json shouldContain "\"primaryActivity\":7"
	}

	@Test
	fun `activity info is included in location`() {
		val loc = DatabaseLocation(
			Location(300L, 51.0, 7.0, null, null, null, null, null),
			ActivityInfo(activityType = 8, confidence = 95),
		)
		val json = export(locations = sequenceOf(loc))
		json shouldContain "\"act\":8"
		json shouldContain "\"actConf\":95"
	}

	@Test
	fun `json escaping works for special characters`() {
		val escaped = JsonExporter.escapeJson("hello \"world\"\nnew\\line")
		escaped shouldBe "hello \\\"world\\\"\\nnew\\\\line"
	}

	@Test
	fun `large export streams without OOM`() {
		// Generate 10000 locations to verify streaming works
		val locs = (1..10_000).asSequence().map {
			testLocation(time = it.toLong(), lat = 50.0 + it * 0.0001, lon = 7.0)
		}
		val out = ByteArrayOutputStream()
		val result = exporter.writeJson(out, locs)
		result shouldBe ExportResult.Success
		val json = out.toString(Charsets.UTF_8.name())
		val count = "\"time\"".toRegex().findAll(json).count()
		count shouldBe 10_000
	}

	private fun testLocation(
		time: Long,
		lat: Double,
		lon: Double,
		alt: Double? = null,
		speed: Float? = null,
	) = DatabaseLocation(
		Location(time, lat, lon, alt, null, null, speed, null),
		ActivityInfo.UNKNOWN,
	)
}
