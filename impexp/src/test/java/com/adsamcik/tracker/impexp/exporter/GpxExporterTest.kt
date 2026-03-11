package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import android.content.pm.ApplicationInfo
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Unit tests for [GpxExporter].
 * Tests GPX output format, structure, and edge cases.
 */
@DisplayName("GpxExporter")
class GpxExporterTest {

    private val exporter = GpxExporter()
    private lateinit var mockContext: Context

    @BeforeEach
    fun setUp() {
        val appInfo = ApplicationInfo().apply {
            labelRes = 0
            nonLocalizedLabel = "TrackerApp"
        }

        mockContext = mockk {
            every { applicationInfo } returns appInfo
            every { getString(eq(R.string.export_gpx_description), any(), any()) } returns "GPS Track Export"
        }
    }

    private fun createTestLocation(
        time: Long,
        latitude: Double,
        longitude: Double,
        altitude: Double? = null
    ): LocationSample {
        return LocationSample(
            timeMs = time,
            elapsedRealtimeNanos = 0L,
            latE7 = (latitude * 1e7).toInt(),
            lonE7 = (longitude * 1e7).toInt(),
            altitudeM = altitude?.toFloat(),
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
        )
    }

    @Nested
    @DisplayName("Properties")
    inner class Properties {

        @Test
        fun `has correct properties`() {
            exporter.mimeType shouldBe "application/gpx+xml"
            exporter.extension shouldBe "gpx"
            exporter.canSelectDateRange.shouldBeTrue()
        }
    }

    @Nested
    @DisplayName("Output Structure")
    inner class OutputStructure {

        @Test
        fun `produces valid GPX header`() {
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = 200.0),
                createTestLocation(time = 1700001000000L, latitude = 50.1, longitude = 14.1, altitude = 210.0)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(mockContext, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldContain "<?xml"
            output shouldContain "<gpx"
            output shouldContain "http://www.topografix.com/GPX/1/1"
        }

        @Test
        fun `produces well-formed XML structure`() {
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = 200.0)
            )

            val outputStream = ByteArrayOutputStream()
            exporter.export(mockContext, locations.asSequence(), outputStream)
            val output = outputStream.toString("UTF-8")

            output shouldStartWith "<?xml"
            output shouldContain "</gpx>"
            output shouldContain "</trk>"
            output shouldContain "</trkseg>"
        }

        @Test
        fun `includes metadata when dateRange is provided`() {
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = 200.0),
                createTestLocation(time = 1700001000000L, latitude = 50.1, longitude = 14.1, altitude = 210.0)
            )

            val outputStream = ByteArrayOutputStream()
            exporter.export(
                mockContext,
                locations.asSequence(),
                outputStream,
                dateRange = 1700000000000L..1700001000000L
            )
            val output = outputStream.toString("UTF-8")

            output shouldContain "<metadata>"
        }

        @Test
        fun `omits metadata when dateRange is null`() {
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = 200.0)
            )

            val outputStream = ByteArrayOutputStream()
            exporter.export(mockContext, locations.asSequence(), outputStream)
            val output = outputStream.toString("UTF-8")

            output shouldNotContain "<metadata>"
        }
    }

    @Nested
    @DisplayName("Track Points")
    inner class TrackPoints {

        @Test
        fun `creates track with segment`() {
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = 200.0)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(mockContext, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldContain "<trk>"
            output shouldContain "<trkseg>"
            output shouldContain "<trkpt"
        }

        @Test
        fun `serializes waypoints with altitude`() {
            val locations = listOf(
                createTestLocation(
                    time = 1700000000000L,
                    latitude = 50.123456,
                    longitude = 14.654321,
                    altitude = 200.5
                )
            )

            val outputStream = ByteArrayOutputStream()
            exporter.export(mockContext, locations.asSequence(), outputStream)
            val output = outputStream.toString("UTF-8")

            output shouldContain "lat=\"50.123456\""
            output shouldContain "lon=\"14.654321\""
            output shouldContain "<ele>200.5</ele>"
        }

        @Test
        fun `handles waypoint without altitude`() {
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = null)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(mockContext, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldContain "<trkpt"
            output shouldNotContain "<ele>null</ele>"
        }

        @Test
        fun `handles multiple waypoints`() {
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = 200.0),
                createTestLocation(time = 1700001000000L, latitude = 50.1, longitude = 14.1, altitude = 210.0),
                createTestLocation(time = 1700002000000L, latitude = 50.2, longitude = 14.2, altitude = 220.0)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(mockContext, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")
            val trkptCount = "<trkpt".toRegex().findAll(output).count()
            trkptCount shouldBe 3
        }

        @Test
        fun `handles large number of waypoints`() {
            val locations = (0 until 1000).map { i ->
                createTestLocation(
                    time = 1700000000000L + i * 1000L,
                    latitude = 50.0 + i * 0.0001,
                    longitude = 14.0 + i * 0.0001,
                    altitude = 200.0 + i * 0.1
                )
            }

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(mockContext, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")
            val trkptCount = "<trkpt".toRegex().findAll(output).count()
            trkptCount shouldBe 1000
        }
    }

    @Nested
    @DisplayName("Timestamps")
    inner class Timestamps {

        @Test
        fun `includes timestamp for waypoints`() {
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.0, longitude = 14.0, altitude = 200.0)
            )

            val outputStream = ByteArrayOutputStream()
            exporter.export(mockContext, locations.asSequence(), outputStream)
            val output = outputStream.toString("UTF-8")

            output shouldContain "<time>"
        }
    }

    @Nested
    @DisplayName("Ordering")
    inner class Ordering {

        @Test
        fun `preserves waypoint ordering`() {
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 50.111, longitude = 14.0, altitude = 100.0),
                createTestLocation(time = 1700001000000L, latitude = 51.222, longitude = 15.0, altitude = 200.0),
                createTestLocation(time = 1700002000000L, latitude = 52.333, longitude = 16.0, altitude = 300.0)
            )

            val outputStream = ByteArrayOutputStream()
            exporter.export(mockContext, locations.asSequence(), outputStream)
            val output = outputStream.toString("UTF-8")

            val firstIndex = output.indexOf("50.111")
            val secondIndex = output.indexOf("51.222")
            val thirdIndex = output.indexOf("52.333")

            firstIndex shouldBeGreaterThan 0
            firstIndex shouldBeLessThan secondIndex
            secondIndex shouldBeLessThan thirdIndex
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCases {

        @Test
        fun `handles extreme coordinate values`() {
            val locations = listOf(
                createTestLocation(time = 1700000000000L, latitude = 89.999, longitude = 0.0, altitude = 0.0),
                createTestLocation(time = 1700001000000L, latitude = -89.999, longitude = 180.0, altitude = 0.0),
                createTestLocation(time = 1700002000000L, latitude = 0.0, longitude = -179.999, altitude = 0.0)
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(mockContext, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldContain "lat=\"89.999\""
            output shouldContain "lat=\"-89.999\""
            output shouldContain "lon=\"-179.999\""
        }

        @Test
        fun `handles negative altitude`() {
            val locations = listOf(
                createTestLocation(
                    time = 1700000000000L,
                    latitude = 31.5,
                    longitude = 35.5,
                    altitude = -430.5
                )
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(mockContext, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            output shouldContain "-430.5"
            output shouldContain "<ele>"
        }

        @Test
        fun `handles high precision coordinates`() {
            val locations = listOf(
                createTestLocation(
                    time = 1700000000000L,
                    latitude = 50.12345678901234,
                    longitude = 14.98765432109876,
                    altitude = 123.456789
                )
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(mockContext, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            // Should preserve reasonable precision
            (output.contains("50.123456") || output.contains("50.12345678")).shouldBeTrue()
            (output.contains("14.987654") || output.contains("14.98765432")).shouldBeTrue()
        }

        @Test
        fun `handles zero coordinates`() {
            val locations = listOf(
                createTestLocation(
                    time = 1700000000000L,
                    latitude = 0.0,
                    longitude = 0.0,
                    altitude = 0.0
                )
            )

            val outputStream = ByteArrayOutputStream()
            val result = exporter.export(mockContext, locations.asSequence(), outputStream)

            result.isSuccess.shouldBeTrue()
            val output = outputStream.toString("UTF-8")

            (output.contains("lat=\"0\"") || output.contains("lat=\"0.0\"")).shouldBeTrue()
            (output.contains("lon=\"0\"") || output.contains("lon=\"0.0\"")).shouldBeTrue()
            output shouldContain "<ele>"
        }
    }
}
