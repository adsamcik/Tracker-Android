package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.dao.SessionDataDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

@DisplayName("GpxImport")
class GpxImportTest {

	private val gpxImport = GpxImport()
	private lateinit var mockDatabase: AppDatabase
	private lateinit var mockLocationDao: LocationDataDao
	private lateinit var mockSessionDao: SessionDataDao
	private lateinit var mockActivityDao: ActivityDao
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
		mockActivityDao = mockk(relaxed = true)
		mockDatabase = mockk {
			every { locationDao() } returns mockLocationDao
			every { sessionDao() } returns mockSessionDao
			every { activityDao() } returns mockActivityDao
		}
		mockContext = mockk(relaxed = true)
	}

	private fun gpxStream(xml: String): FileImportStream {
		val bytes = xml.toByteArray(Charsets.UTF_8)
		return FileImportStream(ByteArrayInputStream(bytes), "test.gpx")
	}

	@Nested
	@DisplayName("Properties")
	inner class Properties {

		@Test
		fun `supported extensions contains gpx`() {
			gpxImport.supportedExtensions shouldBe listOf("gpx")
		}
	}

	@Nested
	@DisplayName("Valid GPX Parsing")
	inner class ValidGpxParsing {

		@Test
		fun `imports single track with timed waypoints`() = runTest {
			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				  <trk>
				    <trkseg>
				      <trkpt lat="50.0" lon="14.0">
				        <ele>200.0</ele>
				        <time>2023-11-14T10:00:00Z</time>
				      </trkpt>
				      <trkpt lat="50.1" lon="14.1">
				        <ele>210.0</ele>
				        <time>2023-11-14T10:01:00Z</time>
				      </trkpt>
				    </trkseg>
				  </trk>
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			capturedLocations shouldHaveSize 2
			capturedLocations[0].location.latitude shouldBeExactly 50.0
			capturedLocations[0].location.longitude shouldBeExactly 14.0
			capturedLocations[0].location.altitude shouldBe 200.0
			capturedLocations[1].location.latitude shouldBeExactly 50.1
			capturedLocations[1].location.longitude shouldBeExactly 14.1
		}

		@Test
		fun `creates session with correct start and end times`() = runTest {
			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				  <trk>
				    <trkseg>
				      <trkpt lat="50.0" lon="14.0">
				        <time>2023-11-14T10:00:00Z</time>
				      </trkpt>
				      <trkpt lat="50.1" lon="14.1">
				        <time>2023-11-14T10:05:00Z</time>
				      </trkpt>
				    </trkseg>
				  </trk>
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			capturedSessions shouldHaveSize 1
			val session = capturedSessions.first()
			session.collections shouldBe 2
			session.isUserInitiated shouldBe true
			(session.end >= session.start) shouldBe true
			(session.end - session.start) shouldBe 5 * 60 * 1000L
		}

		@Test
		fun `preserves coordinate precision`() = runTest {
			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				  <trk>
				    <trkseg>
				      <trkpt lat="50.12345678" lon="14.98765432">
				        <time>2023-11-14T10:00:00Z</time>
				      </trkpt>
				    </trkseg>
				  </trk>
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			capturedLocations shouldHaveSize 1
			capturedLocations[0].location.latitude shouldBeExactly 50.12345678
			capturedLocations[0].location.longitude shouldBeExactly 14.98765432
		}

		@Test
		fun `extracts altitude from waypoints`() = runTest {
			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				  <trk>
				    <trkseg>
				      <trkpt lat="50.0" lon="14.0">
				        <ele>350.5</ele>
				        <time>2023-11-14T10:00:00Z</time>
				      </trkpt>
				    </trkseg>
				  </trk>
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			capturedLocations.first().location.altitude shouldBe 350.5
		}

		@Test
		fun `handles waypoint without altitude`() = runTest {
			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				  <trk>
				    <trkseg>
				      <trkpt lat="50.0" lon="14.0">
				        <time>2023-11-14T10:00:00Z</time>
				      </trkpt>
				    </trkseg>
				  </trk>
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			capturedLocations.first().location.altitude shouldBe null
		}

		@Test
		fun `accumulates distance between consecutive points`() = runTest {
			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				  <trk>
				    <trkseg>
				      <trkpt lat="50.0" lon="14.0">
				        <time>2023-11-14T10:00:00Z</time>
				      </trkpt>
				      <trkpt lat="50.1" lon="14.1">
				        <time>2023-11-14T10:01:00Z</time>
				      </trkpt>
				      <trkpt lat="50.2" lon="14.2">
				        <time>2023-11-14T10:02:00Z</time>
				      </trkpt>
				    </trkseg>
				  </trk>
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			capturedSessions.first().distanceInM shouldBeGreaterThan 0f
		}
	}

	@Nested
	@DisplayName("Track Type and Activity")
	inner class TrackTypeAndActivity {

		@Test
		fun `creates new activity from track type`() = runTest {
			every { mockActivityDao.find("running") } returns null
			every { mockActivityDao.insert(any<SessionActivity>()) } returns 42L

			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				  <trk>
				    <type>running</type>
				    <trkseg>
				      <trkpt lat="50.0" lon="14.0">
				        <time>2023-11-14T10:00:00Z</time>
				      </trkpt>
				    </trkseg>
				  </trk>
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			verify { mockActivityDao.find("running") }
			verify { mockActivityDao.insert(any<SessionActivity>()) }
		}

		@Test
		fun `reuses existing activity from database`() = runTest {
			val existingActivity = SessionActivity(id = 7, name = "cycling")
			every { mockActivityDao.find("cycling") } returns existingActivity

			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				  <trk>
				    <type>cycling</type>
				    <trkseg>
				      <trkpt lat="50.0" lon="14.0">
				        <time>2023-11-14T10:00:00Z</time>
				      </trkpt>
				    </trkseg>
				  </trk>
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			verify { mockActivityDao.find("cycling") }
			verify(exactly = 0) { mockActivityDao.insert(any<SessionActivity>()) }
		}

		@Test
		fun `track without type does not query activity dao`() = runTest {
			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				  <trk>
				    <trkseg>
				      <trkpt lat="50.0" lon="14.0">
				        <time>2023-11-14T10:00:00Z</time>
				      </trkpt>
				    </trkseg>
				  </trk>
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			verify(exactly = 0) { mockActivityDao.find(any()) }
			verify(exactly = 0) { mockActivityDao.insert(any<SessionActivity>()) }
		}
	}

	@Nested
	@DisplayName("Edge Cases")
	inner class EdgeCases {

		@Test
		fun `empty GPX with no tracks inserts nothing`() = runTest {
			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			capturedLocations shouldHaveSize 0
			capturedSessions shouldHaveSize 0
		}

		@Test
		fun `segment with no timed waypoints is skipped`() = runTest {
			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				  <trk>
				    <trkseg>
				      <trkpt lat="50.0" lon="14.0">
				      </trkpt>
				    </trkseg>
				  </trk>
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			capturedSessions shouldHaveSize 0
		}

		@Test
		fun `waypoints without time are excluded from locations`() = runTest {
			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				  <trk>
				    <trkseg>
				      <trkpt lat="50.0" lon="14.0">
				        <time>2023-11-14T10:00:00Z</time>
				      </trkpt>
				      <trkpt lat="50.1" lon="14.1">
				      </trkpt>
				      <trkpt lat="50.2" lon="14.2">
				        <time>2023-11-14T10:02:00Z</time>
				      </trkpt>
				    </trkseg>
				  </trk>
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			capturedLocations shouldHaveSize 2
		}

		@Test
		fun `multiple segments create multiple sessions`() = runTest {
			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				  <trk>
				    <trkseg>
				      <trkpt lat="50.0" lon="14.0">
				        <time>2023-11-14T10:00:00Z</time>
				      </trkpt>
				    </trkseg>
				    <trkseg>
				      <trkpt lat="51.0" lon="15.0">
				        <time>2023-11-14T11:00:00Z</time>
				      </trkpt>
				    </trkseg>
				  </trk>
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			capturedSessions shouldHaveSize 2
		}

		@Test
		fun `handles negative altitude`() = runTest {
			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				  <trk>
				    <trkseg>
				      <trkpt lat="31.5" lon="35.5">
				        <ele>-430.0</ele>
				        <time>2023-11-14T10:00:00Z</time>
				      </trkpt>
				    </trkseg>
				  </trk>
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			capturedLocations.first().location.altitude shouldBe -430.0
		}

		@Test
		fun `handles extreme coordinate values`() = runTest {
			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				  <trk>
				    <trkseg>
				      <trkpt lat="89.999" lon="-179.999">
				        <time>2023-11-14T10:00:00Z</time>
				      </trkpt>
				      <trkpt lat="-89.999" lon="179.999">
				        <time>2023-11-14T10:01:00Z</time>
				      </trkpt>
				    </trkseg>
				  </trk>
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			capturedLocations shouldHaveSize 2
			capturedLocations[0].location.latitude shouldBeExactly 89.999
			capturedLocations[0].location.longitude shouldBeExactly -179.999
			capturedLocations[1].location.latitude shouldBeExactly -89.999
			capturedLocations[1].location.longitude shouldBeExactly 179.999
		}

		@Test
		fun `accumulates collection count`() = runTest {
			val gpx = """
				<?xml version="1.0" encoding="UTF-8"?>
				<gpx version="1.1" creator="test"
				     xmlns="http://www.topografix.com/GPX/1/1">
				  <trk>
				    <trkseg>
				      <trkpt lat="50.0" lon="14.0">
				        <time>2023-11-14T10:00:00Z</time>
				      </trkpt>
				      <trkpt lat="50.001" lon="14.001">
				        <time>2023-11-14T10:01:00Z</time>
				      </trkpt>
				      <trkpt lat="50.002" lon="14.002">
				        <time>2023-11-14T10:02:00Z</time>
				      </trkpt>
				    </trkseg>
				  </trk>
				</gpx>
			""".trimIndent()

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			capturedSessions.first().collections shouldBe 3
		}
	}

	@Nested
	@DisplayName("Batching")
	inner class Batching {

		@Test
		fun `locations are batched in chunks of 100`() = runTest {
			val points = (0 until 250).joinToString("\n") { i ->
				val instant = java.time.Instant.ofEpochSecond(1700000000L + i.toLong())
				"""<trkpt lat="${50.0 + i * 0.0001}" lon="14.0">
					<time>$instant</time>
				</trkpt>"""
			}

			val gpx = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test"
     xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <trkseg>
      $points
    </trkseg>
  </trk>
</gpx>"""

			gpxImport.import(mockContext, mockDatabase, gpxStream(gpx))

			capturedLocations shouldHaveSize 250
			// chunked(100) → 100 + 100 + 50 = 3 insert calls
			locationInsertCallCount shouldBe 3
		}
	}
}
