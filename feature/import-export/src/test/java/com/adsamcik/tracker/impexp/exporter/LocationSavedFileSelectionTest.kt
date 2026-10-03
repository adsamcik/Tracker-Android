package com.adsamcik.tracker.impexp.exporter

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.LocationFileOrigin
import com.adsamcik.tracker.impexp.portable.LocationFilePointV1
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual saved Room rows and file codec; does not simulate provider acquisition or Android SAF. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationSavedFileSelectionTest {
    private lateinit var context: Application
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.testDatabase(context)
    }

    @After fun tearDown() = database.close()

    @Test fun `lost historical purpose does not hide the saved point from a user backup`(): Unit = runBlocking {
        val id = database.locationSampleDao().insert(sample(1_000L).copy(
            sourceEventId = "old-event", sourceSignalId = "old-signal",
        ))
        database.locationPurposeDao().insertReceiptIfAbsent(receipt(id))

        val (result, points) = export(1_000L..1_000L)
        result.isSuccess shouldBe true
        points.single().origin shouldBe LocationFileOrigin.UNKNOWN
        points.single().measurement.timeMs shouldBe 1_000L
        points.single().measurement.horizontalAccuracyM shouldBe 30f
        points.single().measurement.latE7 shouldBe 500_000_000
    }

    @Test fun `equal timestamps crossing a page preserve every point and stable IDs`(): Unit = runBlocking {
        repeat(LocationFileExporter.PAGE_SIZE + 3) {
            database.locationSampleDao().insert(sample(1_000L).copy(lonE7 = 140_000_000 + it))
        }
        database.locationSampleDao().insert(sample(999L))
        database.locationSampleDao().insert(sample(1_001L))

        val first = export(1_000L..1_000L).second
        val repeated = export(1_000L..1_000L).second
        first.size shouldBe LocationFileExporter.PAGE_SIZE + 3
        first.map { it.pointId }.distinct().size shouldBe first.size
        repeated.map { it.pointId } shouldBe first.map { it.pointId }
        first.map { it.measurement.timeMs }.distinct() shouldBe listOf(1_000L)
        first.map { it.measurement.lonE7 } shouldBe
            (0 until first.size).map { 140_000_000 + it }
    }

    @Test fun `retention floor and physical deletion still exclude removed data`(): Unit = runBlocking {
        database.locationSampleDao().insert(sample(999L))
        val kept = database.locationSampleDao().insert(sample(1_000L))
        val erased = database.locationSampleDao().insert(sample(1_001L))
        database.sourceEvidenceStateDao().ensure()
        database.sourceEvidenceStateDao().updateLifecycle(0L, 1_000L, 2_000L)
        database.locationSampleDao().deleteExactIds(listOf(erased))

        val points = export(0L..2_000L).second
        points.map { it.measurement.timeMs } shouldBe listOf(1_000L)
        database.locationFileIdentityDao().bySampleId(kept)?.portableId shouldBe points.single().pointId
        database.locationFileIdentityDao().bySampleId(erased) shouldBe null
    }

    @Test fun `destination copy runs outside the snapshot and contains only its captured rows`(): Unit = runBlocking {
        database.locationSampleDao().insert(sample(1_000L))
        val bytes = ByteArrayOutputStream()
        var writes = 0
        val destination = object : OutputStream() {
            override fun write(value: Int) = error("Copy must use bounded byte blocks")
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                database.inTransaction() shouldBe false
                if (writes++ == 0) runBlocking {
                    // This real DAO write can finish because the snapshot was released.
                    database.locationSampleDao().insert(sample(2_000L))
                }
                bytes.write(buffer, offset, length)
            }
        }

        val result = LocationFileExporter().exportAfter(
            context, emptySequence(), destination, 0L..3_000L, null, null, database, {},
        )
        result.isSuccess shouldBe true
        read(bytes).map { it.measurement.timeMs } shouldBe listOf(1_000L)
        database.locationSampleDao().countAll() shouldBe 2L
        ownedSpools() shouldBe emptyList()
    }

    @Test fun `empty selection leaves destination untouched and removes its staged header`(): Unit = runBlocking {
        val bytes = ByteArrayOutputStream()
        LocationFileExporter().exportAfter(
            context, emptySequence(), bytes, 0L..2_000L, null, null, database, {},
        ).isSuccess shouldBe false
        bytes.size() shouldBe 0
        ownedSpools() shouldBe emptyList()
    }

    @Test fun `closing access after staging prevents destination copy`(): Unit = runBlocking {
        database.locationSampleDao().insert(sample(1_000L))
        val bytes = ByteArrayOutputStream()
        LocationFileExporter().exportAfter(
            context, emptySequence(), bytes, 0L..2_000L, null, null, database,
            { check(database.inTransaction()) { "Original transfer admission closed" } },
        ).isSuccess shouldBe false
        bytes.size() shouldBe 0
        ownedSpools() shouldBe emptyList()
    }

    @Test fun `retention changing during copy cannot publish success`(): Unit = runBlocking {
        database.locationSampleDao().insert(sample(1_000L))
        database.sourceEvidenceStateDao().ensure()
        var changed = false
        val destination = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (!changed) {
                    changed = true
                    runBlocking { database.sourceEvidenceStateDao().updateLifecycle(0L, 2_000L, 3_000L) }
                }
                super.write(buffer, offset, length)
            }
        }
        LocationFileExporter().exportAfter(
            context, emptySequence(), destination, 0L..2_000L, null, null, database, {},
        ).isSuccess shouldBe false
        changed shouldBe true
        ownedSpools() shouldBe emptyList()
    }

    @Test fun `caller cancellation during copy is rethrown after owned staging cleanup`(): Unit = runBlocking {
        database.locationSampleDao().insert(sample(1_000L))
        lateinit var request: Deferred<ExportResult>
        val destination = object : OutputStream() {
            override fun write(value: Int) = error("Copy must use bounded byte blocks")
            override fun write(buffer: ByteArray, offset: Int, length: Int) { request.cancel() }
        }
        request = async(start = CoroutineStart.LAZY) {
            LocationFileExporter().exportAfter(
                context, emptySequence(), destination, 0L..2_000L, null, null, database, {},
            )
        }
        request.start()
        try {
            request.await()
            error("Cancellation must not become an export result")
        } catch (_: kotlinx.coroutines.CancellationException) {
            request.isCancelled shouldBe true
        }
        ownedSpools() shouldBe emptyList()
    }

    private suspend fun export(range: LongRange): Pair<ExportResult, List<LocationFilePointV1>> {
        val bytes = ByteArrayOutputStream()
        val result = LocationFileExporter().exportAfter(
            context, emptySequence(), bytes, range, null, null, database, {},
        )
        return result to if (result.isSuccess) read(bytes) else emptyList()
    }

    private suspend fun read(bytes: ByteArrayOutputStream): List<LocationFilePointV1> {
        val points = mutableListOf<LocationFilePointV1>()
        LocationFileJsonV1().read(ByteArrayInputStream(bytes.toByteArray())) { points += it }
        return points
    }

    private fun ownedSpools(): List<File> =
        File(context.cacheDir, "portable-export-spool").listFiles()?.filter {
            it.name.startsWith("tracker-portable-export-spool-v1-portable-location-v1-")
        }.orEmpty()

    private fun sample(timeMs: Long) = LocationSample(
        timeMs = timeMs, elapsedRealtimeNanos = 0L, latE7 = 500_000_000, lonE7 = 140_000_000,
        altitudeM = null, rawGpsAltitudeM = null, hAccM = 30f, vAccM = null, speedMps = null,
        speedAccuracyMps = null, provider = "saved", quality = SampleQuality.MEDIUM,
        motionState = null, policy = null, bucketId = null, createdAt = timeMs,
    )

    private fun receipt(sampleId: Long) = LocationAcceptedReceiptEntity(
        sampleId = sampleId, sourceEventId = "old-event", sourceSignalId = "old-signal",
        sourceAdmissionOrdinal = 1L, walIntegrityIdentity = "a".repeat(64),
        canonicalContentChecksum = "b".repeat(64), sourceInstanceId = "old-instance",
        registrationGeneration = 1L, sourceSequence = 0L, deliveryIdentity = "old-delivery",
        deliveryUnitIndex = 0, deliveryUnitCount = 1, writerId = "old-writer", writerVersion = 1,
        configurationRevision = 1L, physicalConfigurationFingerprint = "old-physical",
        authorizationRevision = 1L, authorizationFingerprint = "old-authorization",
        clockDomainId = "old-boot", bootId = "old-boot", observedElapsedRealtimeNanos = 10L,
        receivedElapsedRealtimeNanos = 11L, observedWallTimeMs = 1_000L,
        receivedWallTimeMs = 1_001L, wallTimeUncertaintyMs = 0L, collectedDataEpoch = 0L,
    )
}
