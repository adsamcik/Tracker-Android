package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class JsonExporterTest {

	private val exporter = JsonExporter()

	private fun export(
		locations: Sequence<LocationSample> = emptySequence(),
		sessions: List<SessionSnapshot> = emptyList(),
		dateRange: LongRange? = null,
	): String {
		val out = ByteArrayOutputStream()
		val result = exporter.writeJson(out, locations, sessions, dateRange)
		result shouldBe ExportResult.Success
		return out.toString(Charsets.UTF_8.name())
	}

	@Test
	fun `empty export produces valid schema`() {
		val json = export()
		json shouldStartWith "{\"schema\":1"
		json shouldContain "\"locations\":[]"
		json shouldContain "\"sessions\":[]"
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
	fun `activity info is included in location`() {
		val loc = testLocation(time = 300L, lat = 51.0, lon = 7.0)
		val json = export(locations = sequenceOf(loc))
		json shouldContain "\"time\":300"
		json shouldContain "\"lat\":51.0"
		json shouldContain "\"lon\":7.0"
	}

	@Test
	fun `json escaping works for special characters`() {
		val escaped = JsonExporter.escapeJson("hello \"world\"\nnew\\line")
		escaped shouldBe "hello \\\"world\\\"\\nnew\\\\line"
	}

	@Test
	fun `large export streams without OOM`() {
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

	@Test
	fun `session without steps omits steps field`() {
		val session = SessionSnapshot(
			id = 1,
			start = 1000L,
			end = 2000L,
			collections = 10,
			distanceInM = 100f,
			isUserInitiated = false,
			steps = null,
		)
		val json = export(sessions = listOf(session))
		json shouldContain "\"isUserInitiated\":false"
		json.contains("\"steps\"") shouldBe false
	}

	private fun testLocation(
		time: Long,
		lat: Double,
		lon: Double,
		alt: Double? = null,
		speed: Float? = null,
	) = LocationSample(
		timeMs = time,
		elapsedRealtimeNanos = 0L,
		latE7 = (lat * 1e7).toInt(),
		lonE7 = (lon * 1e7).toInt(),
		altitudeM = alt?.toFloat(),
		rawGpsAltitudeM = null,
		hAccM = null,
		vAccM = null,
		speedMps = speed,
		speedAccuracyMps = null,
		provider = "gps",
		quality = SampleQuality.HIGH,
		motionState = null,
		policy = null,
		bucketId = null,
		createdAt = System.currentTimeMillis(),
	)
}
