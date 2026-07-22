package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
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
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KmlImportTest {

	private val kmlImport = KmlImport()
	private lateinit var database: AppDatabase
	private lateinit var context: Context
	private val capturedSamples = mutableListOf<LocationSample>()
	private var locationInsertCallCount = 0

	@Before
	fun setUp() {
		capturedSamples.clear()
		locationInsertCallCount = 0
		val locationDao = mockk<LocationSampleDao> {
			coEvery { insert(any<Collection<LocationSample>>()) } answers {
				val batch = firstArg<Collection<LocationSample>>()
				capturedSamples.addAll(batch)
				locationInsertCallCount++
				batch.map { 0L }
			}
		}
		database = mockk {
			every { locationSampleDao() } returns locationDao
			every { sessionSegmentDao() } returns mockk<SessionSegmentDao>(relaxed = true)
			every { activityDao() } returns mockk<ActivityDao>(relaxed = true)
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
	fun `imports coordinates in bounded batches`() = runTest {
		val points = (0 until KmlImport.BATCH_SIZE * 4 + 5).joinToString(" ") { index ->
			"${14.0 + index * 0.00001},${50.0 + index * 0.00001},${index.toDouble()}"
		}
		val kml = """<kml><Placemark><name>walking</name><LineString><coordinates>$points</coordinates></LineString></Placemark></kml>"""

		kmlImport.import(context, database, kmlStream(kml))

		capturedSamples shouldHaveSize (KmlImport.BATCH_SIZE * 4 + 5)
		locationInsertCallCount shouldBe 5
		capturedSamples.first().altitudeDatum shouldBe AltitudeDatum.UNKNOWN_LEGACY
		capturedSamples.first().altitudeSource shouldBe AltitudeSource.IMPORTED
		capturedSamples.first().altitudeConversionStatus shouldBe AltitudeConversionStatus.UNKNOWN_LEGACY
	}

	@Test
	fun `rounds coordinates to E7`() = runTest {
		val kml = """<kml><Placemark><LineString><coordinates>14.12345678,50.12345678</coordinates></LineString></Placemark></kml>"""
		kmlImport.import(context, database, kmlStream(kml))
		capturedSamples.single().latE7 shouldBe (50.12345678 * 1e7).roundToInt()
		capturedSamples.single().lonE7 shouldBe (14.12345678 * 1e7).roundToInt()
	}
}
