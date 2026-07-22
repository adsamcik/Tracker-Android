package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.SampleQuality
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Unit tests for [KmlExporter].
 * Tests KML output format, structure, and edge cases.
 */
@DisplayName("KmlExporter")
class KmlExporterTest {

    private val exporter = KmlExporter()

    private fun createTestLocation(
        time: Long,
        latitude: Double,
        longitude: Double,
        altitude: Double? = null
    ): LocationSample {
        return LocationSample(
            timeMs = time,
            elapsedRealtimeNanos = 0L,
            latE7 = latitude.takeIf { it.isFinite() }?.times(1e7)?.toInt(),
            lonE7 = longitude.takeIf { it.isFinite() }?.times(1e7)?.toInt(),
            altitudeM = altitude?.takeIf { it.isFinite() }?.toFloat(),
            rawGpsAltitudeM = null,
            hAccM = 10f,
            vAccM = null,
            speedMps = null,
            speedAccuracyMps = null,
            provider = "gps",
            quality = SampleQuality.HIGH,
            motionState = null,
            policy = null,
            bucketId = null,
            createdAt = System.currentTimeMillis(),
			altitudeDatum = if (altitude?.isFinite() == true) {
				AltitudeDatum.ANDROID_MODEL_MSL
			} else {
				AltitudeDatum.UNKNOWN_LEGACY
			},
        )
    }

    @Nested
    @DisplayName("Properties")
    inner class Properties {

        @Test
        fun `has correct properties`() {
            exporter.mimeType shouldBe "application/vnd.google-earth.kml+xml"
            exporter.extension shouldBe "kml"
            exporter.canSelectDateRange.shouldBeTrue()
        }
    }

    @Nested
    @DisplayName("Output Structure")
    inner class OutputStructure {

        @Test
        fun `produces valid KML header`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = 200.0)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(context, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldContain "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            output shouldContain "<kml xmlns=\"http://www.opengis.net/kml/2.2\">"
            output shouldContain "<Document>"
            output shouldContain "</Document></kml>"
        }

        @Test
        fun `produces well-formed XML`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = 200.0)
            )

            val outputStream = ByteArrayOutputStream()
            exporter.export(context, locations.asSequence(), outputStream)
            val output = outputStream.toString("UTF-8")

            output shouldStartWith "<?xml"
            output.isEmpty().shouldBeFalse()
        }
    }

    @Nested
    @DisplayName("Placemarks")
    inner class Placemarks {

        @Test
        fun `includes placemark for each location`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = 200.0),
                createTestLocation(time = 1700001000000L, latitude = 51.0, longitude = 15.0, altitude = 250.0),
                createTestLocation(time = 1700002000000L, latitude = 52.0, longitude = 16.0, altitude = 300.0)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(context, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")
            val placemarkCount = "<Placemark>".toRegex().findAll(output).count()
            placemarkCount shouldBe 3
        }

        @Test
        fun `handles single location`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = 200.0)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(context, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldStartWith "<?xml"
            output shouldContain "<Placemark>"
            "<Placemark>".toRegex().findAll(output).count() shouldBe 1
        }

        @Test
        fun `handles multiple locations in sequence`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = (0 until 100).map { i ->
                createTestLocation(
                    time = 1700000000000L + i * 1000L,
                    latitude = 50.0 + i * 0.001,
                    longitude = 14.0 + i * 0.001,
                    altitude = 200.0 + i
                )
            }

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(context, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")
            "<Placemark>".toRegex().findAll(output).count() shouldBe 100
        }
    }

    @Nested
    @DisplayName("Coordinates")
    inner class Coordinates {

        @Test
        fun `serializes coordinates correctly`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(
                    time = 1700000000000L,
                    latitude = 50.123456,
                    longitude = 14.654321,
                    altitude = 200.5
                )
            )

            val outputStream = ByteArrayOutputStream()
            exporter.export(context, locations.asSequence(), outputStream)
            val output = outputStream.toString("UTF-8")

            // KML format: longitude,latitude,altitude
            output shouldContain "14.654321,50.123456"
            output shouldContain "200.5"
        }

        @Test
        fun `handles location with null altitude`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = null)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(context, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldContain "<coordinates>"
            output shouldContain "14.0,50.0"
        }

		@Test
		fun `omits third coordinate for non-MSL altitude`() = runTest {
			val context = mockk<android.content.Context>(relaxed = true)
			val location = createTestLocation(
				time = 1700000000000L,
				latitude = 50.0,
				longitude = 14.0,
				altitude = 200.0,
			).copy(altitudeDatum = AltitudeDatum.RELATIVE_BAROMETRIC)

			val outputStream = ByteArrayOutputStream()
			exporter.export(context, sequenceOf(location), outputStream)

			outputStream.toString("UTF-8") shouldContain "<coordinates>14.0,50.0</coordinates>"
		}

        @Test
        fun `handles extreme coordinate values`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 89.999, longitude = 0.0, altitude = 0.0),
                createTestLocation(time = 1700001000000L, latitude = -89.999, longitude = 180.0, altitude = 0.0),
                createTestLocation(time = 1700002000000L, latitude = 0.0, longitude = -179.999, altitude = 0.0)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(context, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldContain "89.999"
            output shouldContain "-89.999"
            output shouldContain "-179.999"
        }
    }

    @Nested
    @DisplayName("Timestamps")
    inner class Timestamps {

        @Test
        fun `includes timestamp in placemark`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = 200.0)
            )

            val outputStream = ByteArrayOutputStream()
            exporter.export(context, locations.asSequence(), outputStream)
            val output = outputStream.toString("UTF-8")

            output shouldContain "<TimeStamp>"
            output shouldContain "<when>"
            output shouldContain "<when>2023-11-14T22:13:20Z</when>"
        }
    }

    @Nested
    @DisplayName("Ordering")
    inner class Ordering {

        @Test
        fun `preserves location ordering`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = 100.0),
                createTestLocation(time = 1700001000000L, latitude = 51.0, longitude = 15.0, altitude = 200.0),
                createTestLocation(time = 1700002000000L, latitude = 52.0, longitude = 16.0, altitude = 300.0)
            )

            val outputStream = ByteArrayOutputStream()
            exporter.export(context, locations.asSequence(), outputStream)
            val output = outputStream.toString("UTF-8")

            val firstIndex = output.indexOf("14.0,50.0")
            val secondIndex = output.indexOf("15.0,51.0")
            val thirdIndex = output.indexOf("16.0,52.0")

            firstIndex shouldBeGreaterThan 0
            firstIndex shouldBeLessThan secondIndex
            secondIndex shouldBeLessThan thirdIndex
        }
    }

    @Nested
    @DisplayName("NaN and Infinity handling")
    inner class InvalidValues {

        @Test
		fun `NaN altitude produces valid KML without an invented altitude`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = Double.NaN)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(context, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldNotContain "NaN"
			output shouldContain "<coordinates>14.0,50.0</coordinates>"
        }

        @Test
		fun `Infinity altitude produces valid KML without an invented altitude`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = Double.POSITIVE_INFINITY)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(context, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldNotContain "Infinity"
			output shouldContain "<coordinates>14.0,50.0</coordinates>"
        }

        @Test
		fun `negative Infinity altitude produces valid KML without an invented altitude`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = Double.NEGATIVE_INFINITY)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(context, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldNotContain "Infinity"
			output shouldContain "<coordinates>14.0,50.0</coordinates>"
        }

        @Test
        fun `NaN latitude skips the location entirely`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = Double.NaN, longitude = 14.0, altitude = 200.0)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(context, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldNotContain "NaN"
            output shouldNotContain "<Placemark>"
        }

        @Test
        fun `NaN longitude skips the location entirely`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = Double.NaN, altitude = 200.0)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(context, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldNotContain "NaN"
            output shouldNotContain "<Placemark>"
        }

        @Test
        fun `Infinity longitude skips the location entirely`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = Double.POSITIVE_INFINITY, altitude = 200.0)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(context, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldNotContain "Infinity"
            output shouldNotContain "<Placemark>"
        }

        @Test
        fun `mixed valid and invalid locations only exports valid ones`() = runTest {
            val context = mockk<android.content.Context>(relaxed = true)
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = 200.0),
                createTestLocation(time = 1700001000000L, latitude = Double.NaN, longitude = 15.0, altitude = 100.0),
                createTestLocation(time = 1700002000000L, latitude = 51.0, longitude = 15.0, altitude = Double.NaN)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(context, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldNotContain "NaN"
            "<Placemark>".toRegex().findAll(output).count() shouldBe 2
        }
    }
}
