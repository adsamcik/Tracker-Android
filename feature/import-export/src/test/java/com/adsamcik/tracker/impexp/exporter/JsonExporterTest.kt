package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.SampleQuality
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class JsonExporterTest {

    private val exporter = JsonExporter()

    @Test
    fun `exports a valid empty session array`() {
        val output = export(emptySequence())

        output shouldBe "[]"
    }

    @Test
    fun `groups location wifi and cell observations under their session`() {
        val output = export(sequenceOf(sessionExport()))

        output shouldContain "\"schemaVersion\":2"
        output shouldContain "\"session\":{\"id\":42"
        output shouldContain "\"locations\":[{\"timeMs\":1725000000000,\"latitude\":50.1"
        output shouldContain "\"wifiObservations\":[{\"timeMs\":1725000010000,\"bssid\":\"00:11:22:33:44:55\""
        output shouldContain "\"cellSamples\":[{\"timeMs\":1725000020000,\"cellId\":9876543210"
    }

    @Test
    fun `streams a large number of session records`() {
        val output = export((1..2_000).asSequence().map { sessionExport(id = it.toLong()) })

        "\"schemaVersion\":2".toRegex().findAll(output).count() shouldBe 2_000
    }

    @Test
    fun `reports location count and composite maximum cursor`() {
        val output = ByteArrayOutputStream()
        val result = exporter.writeJson(
            output,
            sequenceOf(
                sessionExport(
                    locations = listOf(
                        location(id = 4L, timeMs = 2_000L),
                        location(id = 9L, timeMs = 2_000L),
                        location(id = 1L, timeMs = 3_000L),
                    ),
                ),
            ),
        ).shouldBeInstanceOf<ExportResult.Success>()

        result.recordCount shouldBe 3
        result.maxTimeMs shouldBe 3_000L
        result.maxId shouldBe 1L
    }

    private fun export(sessions: Sequence<SessionExport>): String {
        val output = ByteArrayOutputStream()
        exporter.writeJson(output, sessions).shouldBeInstanceOf<ExportResult.Success>()
        return output.toString(Charsets.UTF_8.name())
    }

    private fun sessionExport(
        id: Long = 42,
        locations: List<LocationSample> = listOf(location()),
    ) = SessionExport(
        session = SessionSnapshot(
            id = id,
            startTimeMs = 1_725_000_000_000L,
            endTimeMs = 1_725_000_060_000L,
            distanceM = 1234.5f,
            steps = 500,
            primaryActivity = 7,
            activityConfidence = 93,
            sampleCount = 1,
            source = "USER_CREATED",
            hasDistanceAnomaly = false,
        ),
        locations = locations,
        wifiObservations = listOf(
            WifiObservation(
                timeMs = 1_725_000_010_000L,
                bssid = "00:11:22:33:44:55",
                ssid = "Tracker wifi",
                capabilities = "[WPA2]",
                frequency = 5180,
                level = -50,
                latE7 = 501_000_000,
                lonE7 = 144_000_000,
                provenance = CoordinateProvenance.DIRECT,
                createdAt = 1_725_000_010_000L,
            ),
        ),
        cellSamples = listOf(
            CellSample(
                timeMs = 1_725_000_020_000L,
                cellId = 9_876_543_210L,
                lac = 123,
                mcc = 230,
                mnc = 1,
                networkType = 13,
                signalStrength = 45,
                latE7 = 501_000_000,
                lonE7 = 144_000_000,
                provenance = CoordinateProvenance.DIRECT,
                createdAt = 1_725_000_020_000L,
            ),
        ),
    )

    private fun location(
        id: Long = 7L,
        timeMs: Long = 1_725_000_000_000L,
    ) = LocationSample(
        id = id,
        timeMs = timeMs,
        elapsedRealtimeNanos = 0L,
        latE7 = 501_000_000,
        lonE7 = 144_000_000,
        altitudeM = 120f,
        rawGpsAltitudeM = null,
        hAccM = 5f,
        vAccM = null,
        speedMps = 3.5f,
        speedAccuracyMps = null,
        provider = "gps",
        quality = SampleQuality.HIGH,
        motionState = null,
        policy = null,
        bucketId = null,
        createdAt = timeMs,
    )
}