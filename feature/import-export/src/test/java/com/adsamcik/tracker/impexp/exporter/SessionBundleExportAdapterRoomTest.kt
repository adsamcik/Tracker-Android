package com.adsamcik.tracker.impexp.exporter

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.feature.statistics.api.export.SessionBundleExportRequest
import com.adsamcik.tracker.feature.statistics.api.export.SessionBundleExportResult
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.LocationFilePointV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestCodecV1
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizationSessionId
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real saved metadata, Location selection, typed file, ZIP and cleanup. Authored, NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionBundleExportAdapterRoomTest {
    private lateinit var context: Application
    private lateinit var database: AppDatabase
    private lateinit var exporter: ImportExportSessionBundleExporter
    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.testDatabase(context)
        database.sourceEvidenceStateDao().ensure()
        exporter = ImportExportSessionBundleExporter(context, database, TestDispatchersProvider(Dispatchers.Unconfined),
            mockk<TrackingStartupGate>(), mockk<ExportPlanStore>())
    }
    @After fun close() = database.close()

    @Test fun `bundle retains labels and only selected current-interval Location data`() = runBlocking {
        annotation(1_000, 4_000); point(999); point(2_000); point(4_000)
        val output = ByteArrayOutputStream()
        assertEquals(SessionBundleExportResult.Success(), exporter.writeBundle(request(), {}) { write -> write(output) })
        val entries = unzip(output.toByteArray())
        val manifest = SessionBundleManifestCodecV1.decode(entries.getValue(SessionBundleManifestCodecV1.MANIFEST_PATH))
        assertEquals("Morning walk", manifest.name); assertEquals(context.getString(com.adsamcik.tracker.shared.base.data.NativeSessionActivity.WALKING.nameRes), manifest.activityLabel)
        assertEquals(listOf("location"), manifest.selectedSources)
        val child = manifest.children.single()
        assertEquals(2_000L, child.fromMs); assertEquals(2_000L, child.toMs)
        val points = mutableListOf<LocationFilePointV1>()
        LocationFileJsonV1().read(ByteArrayInputStream(entries.getValue(child.path))) { points += it }
        assertEquals(listOf(2_000L), points.map { it.measurement.timeMs })
        assertSpoolEmpty()
    }

    @Test fun `point session preserves its original time in manifest and child`() = runBlocking {
        annotation(2_000, 2_000); point(2_000); point(2_001)
        val output = ByteArrayOutputStream()
        exporter.writeBundle(request(), {}) { write -> write(output) }
        val manifest = SessionBundleManifestCodecV1.decode(unzip(output.toByteArray()).getValue(SessionBundleManifestCodecV1.MANIFEST_PATH))
        assertEquals(2_000L, manifest.fromMs); assertEquals(2_000L, manifest.toMs)
        assertEquals(2_000L, manifest.children.single().toMs)
        assertFalse(manifest.children.single().wholeEntries)
    }

    @Test fun `stale annotation never opens destination`() = runBlocking {
        annotation(1_000, 4_000); point(2_000)
        var opened = false
        assertFailsWith<IllegalStateException> {
            exporter.writeBundle(request().copy(expectedOrganizationRevision = 4), {}) { opened = true }
        }
        assertFalse(opened); assertSpoolEmpty()
    }

    @Test fun `deletion during copy invalidates success and cleans private files`() = runBlocking {
        annotation(1_000, 4_000); val saved = point(2_000)
        val output = object : ByteArrayOutputStream() {
            var first = true
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, length)
                if (first) { first = false; runBlocking { database.locationSampleDao().deleteExactIds(listOf(saved.id)) } }
            }
        }
        assertFailsWith<IllegalStateException> { exporter.writeBundle(request(), {}) { write -> write(output) } }
        assertTrue(output.size() > 0); assertSpoolEmpty()
    }

    @Test fun `cancelled destination cannot become successful bundle`() = runBlocking {
        annotation(1_000, 4_000); point(2_000)
        assertFailsWith<CancellationException> { exporter.writeBundle(request(), {}) { write -> write(object : OutputStream() {
            override fun write(value: Int) = throw CancellationException("cancel")
            override fun write(bytes: ByteArray, offset: Int, length: Int) = throw CancellationException("cancel")
        }) } }
        assertSpoolEmpty()
    }

    @Test fun `cancellation remains primary when child cleanup also fails`(): Unit = runBlocking {
        annotation(1_000, 4_000)
        point(2_000)
        var blockedSpool: File? = null
        try {
            val failure = assertFailsWith<CancellationException> {
                exporter.writeBundle(request(), {}) {
                    val child = File(context.cacheDir, "portable-export-spool").listFiles().orEmpty()
                        .single { it.name.contains("session-child-") }
                    assertTrue(child.delete())
                    assertTrue(child.mkdir())
                    File(child, "occupied").writeText("fixture")
                    blockedSpool = child
                    throw CancellationException("cancelled destination")
                }
            }
            assertEquals("cancelled destination", failure.message)
            assertTrue(failure.suppressed.any { it is IOException })
        } finally {
            blockedSpool?.let { child ->
                assertTrue(File(child, "occupied").delete())
                assertTrue(child.delete())
            }
        }
        assertSpoolEmpty()
    }

    private suspend fun request() = SessionBundleExportRequest(OrganizationSessionId.Annotation("walk"), 0,
        requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch, setOf(HistorySource.LOCATION))
    private suspend fun annotation(from: Long, to: Long) {
        database.organizationAnnotationDao().insert(OrganizationAnnotationEntity("walk", 0, to, to, null,
            from, to, "UTC", "Morning walk", com.adsamcik.tracker.shared.base.data.NativeSessionActivity.WALKING.id, false))
    }
    private suspend fun point(time: Long): LocationSample {
        val row = LocationSample(timeMs = time, elapsedRealtimeNanos = time * 1_000_000, latE7 = 500_000_000,
            lonE7 = 140_000_000, altitudeM = null, rawGpsAltitudeM = null, hAccM = null, vAccM = null,
            speedMps = null, speedAccuracyMps = null, provider = "saved", quality = SampleQuality.MEDIUM,
            motionState = null, policy = null, bucketId = null, createdAt = time, clockDomainId = "boot")
        return row.copy(id = database.locationSampleDao().insert(row))
    }
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()); zip.closeEntry() }
        }
    }
    private fun assertSpoolEmpty() = assertTrue(File(context.cacheDir, "portable-export-spool").listFiles().orEmpty().isEmpty())
}
