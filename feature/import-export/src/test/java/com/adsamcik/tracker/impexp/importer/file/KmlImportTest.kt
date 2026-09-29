package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
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
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KmlImportTest {

	private val kmlImport = KmlImport()
	private lateinit var database: AppDatabase
	private lateinit var context: Context
	private val capturedSamples = mutableListOf<LocationSample>()

	@Before
	fun setUp() {
		capturedSamples.clear()
		val locationDao = mockk<LocationSampleDao> {
			coEvery { insert(any<Collection<LocationSample>>()) } answers {
				val batch = firstArg<Collection<LocationSample>>()
				capturedSamples.addAll(batch)
				batch.map { 0L }
			}
		}
		database = mockk {
			every { locationSampleDao() } returns locationDao
		}
		context = mockk(relaxed = true)
	}

	private fun kmlStream(kml: String) =
		FileImportStream(ByteArrayInputStream(kml.toByteArray()), "test.kml")

	@Test
	fun `supports kml extension`() {
		kmlImport.supportedExtensions shouldBe listOf("kml")
	}

	@Test
	fun `parses exporter ISO instant timestamp with timezone`() {
		KmlImport.parseWhenTimestamp("2023-11-14T22:13:20Z") shouldBe 1_700_000_000_000L
	}

	@Test
	fun `imports timed points without inventing other times`() = runTest {
		val kml = """<kml><Placemark><TimeStamp><when>2023-11-14T22:13:20Z</when></TimeStamp><Point><coordinates>14.0,50.0,300</coordinates></Point></Placemark><Placemark><Point><coordinates>14.1,50.1</coordinates></Point><TimeStamp><when>2023-11-14T22:14:20Z</when></TimeStamp></Placemark></kml>"""

		kmlImport.import(context, database, kmlStream(kml)).successCount shouldBe 2

		capturedSamples shouldHaveSize 2
		capturedSamples[0].timeMs shouldBe 1_700_000_000_000L
		capturedSamples[1].timeMs shouldBe 1_700_000_060_000L
		capturedSamples.first().quality.name shouldBe "COARSE"
		capturedSamples.first().altitudeDatum shouldBe AltitudeDatum.UNKNOWN_LEGACY
		capturedSamples.first().altitudeSource shouldBe AltitudeSource.IMPORTED
		capturedSamples.first().altitudeConversionStatus shouldBe AltitudeConversionStatus.UNKNOWN_LEGACY
	}

	@Test
	fun `rounds timed point coordinates to E7`() = runTest {
		val kml = """<kml><Placemark><Point><coordinates>14.12345678,50.12345678</coordinates></Point><TimeStamp><when>2023-11-14T22:13:20Z</when></TimeStamp></Placemark></kml>"""
		kmlImport.import(context, database, kmlStream(kml))
		capturedSamples.single().latE7 shouldBe (50.12345678 * 1e7).roundToInt()
		capturedSamples.single().lonE7 shouldBe (14.12345678 * 1e7).roundToInt()
	}

	@Test
	fun `untimed point is rejected without saving data`() = runTest {
		val kml = """<kml><Placemark><Point><coordinates>14.0,50.0</coordinates></Point></Placemark></kml>"""
		assertFailsWith<IOException> { kmlImport.import(context, database, kmlStream(kml)) }
		capturedSamples shouldHaveSize 0
	}

	@Test
	fun `line without per point time is rejected without saving data`() = runTest {
		val kml = """<kml><Placemark><TimeStamp><when>2023-11-14T22:13:20Z</when></TimeStamp><LineString><coordinates>14.0,50.0 14.1,50.1</coordinates></LineString></Placemark></kml>"""
		assertFailsWith<IOException> { kmlImport.import(context, database, kmlStream(kml)) }
		capturedSamples shouldHaveSize 0
	}
}
