package com.adsamcik.tracker.impexp.exporter.activity

import android.app.Application
import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.impexp.exporter.JsonExporter
import com.adsamcik.tracker.impexp.exporter.data.ImportExportDataRepository
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.adsamcik.tracker.shared.base.database.data.LocationSample as EntityLocationSample

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ExportDocumentCleanupTest {
    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `cancellation removes the partial file`() = runTest(dispatcher) {
        val folder = File(context.cacheDir, "cancelled-export-test").apply { mkdirs() }
        val started = CompletableDeferred<Unit>()
        try {
            val export = async {
                newViewModel().exportDocument(
                    DocumentFile.fromFile(folder), true, exporter { output ->
                        output.write("partial".toByteArray())
                        started.complete(Unit)
                        awaitCancellation()
                    }, "cancelled", null,
                )
            }
            started.await()
            export.cancelAndJoin()
            folder.listFiles()?.size shouldBe 0
        } finally {
            folder.listFiles()?.forEach(File::delete)
            folder.delete()
        }
    }

    @Test
    fun `invalid JSON page after a Room record fails and removes the emitted prefix`() = runTest(dispatcher) {
        val folder = File(context.cacheDir, "invalid-page-export-test").apply { mkdirs() }
        val database = AppDatabase.testDatabase(context)
        try {
            val timeMs = 1_725_000_000_000L
            database.locationSampleDao().insert(EntityLocationSample(
                timeMs = timeMs, elapsedRealtimeNanos = 0, latE7 = 500_000_000, lonE7 = 140_000_000,
                altitudeM = null, rawGpsAltitudeM = null, hAccM = 5f, vAccM = 2f,
                speedMps = 1f, speedAccuracyMps = 0.4f, provider = "import", quality = SampleQuality.HIGH,
                motionState = null, policy = null, bucketId = null, createdAt = timeMs,
            ))
            val segmentId = database.sessionSegmentDao().insert(SessionSegment(
                startTimeMs = timeMs, endTimeMs = timeMs + 1_000, distanceM = 1f, steps = null,
                primaryActivity = null, activityConfidence = null, sampleCount = 1,
                source = SegmentSource.USER_CREATED, inferenceVersion = null, createdAt = timeMs,
            ))
            val savedTrip = requireNotNull(database.tripDao().getById(segmentId))
            var loads = 0
            val source = object : PagingSource<Int, Trip>() {
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Trip> {
                    loads++
                    return if (loads == 1) {
                        LoadResult.Page(listOf(savedTrip), null, 1)
                    }
                    else {
                        LoadResult.Invalid()
                    }
                }
                override fun getRefreshKey(state: PagingState<Int, Trip>): Int? = null
            }
            val trips = mockk<TripDao> {
                every { getPagedOverlapping(timeMs, timeMs + 1_000) } returns source
            }
            val pagedDatabase = mockk<AppDatabase> {
                every { tripDao() } returns trips
                every { locationSampleDao() } returns database.locationSampleDao()
                every { locationPurposeDao() } returns database.locationPurposeDao()
                every { wifiObservationDao() } returns database.wifiObservationDao()
                every { cellSampleDao() } returns database.cellSampleDao()
            }
            val emitted = ByteArrayOutputStream()
            newViewModel().exportDocument(
                DocumentFile.fromFile(folder), true, exporter { output ->
                    val observedOutput = object : FilterOutputStream(output) {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            emitted.write(bytes, offset, length)
                            out.write(bytes, offset, length)
                        }
                    }
                    JsonExporter(TestDispatchersProvider(dispatcher)).exportAfter(
                        context, emptySequence(), observedOutput, timeMs..(timeMs + 1_000),
                        null, null, pagedDatabase, {},
                    )
                }, "invalid-page", null,
            ).shouldBeInstanceOf<ExportDocumentResult.Failure>()

            loads shouldBe 2
            source.invalid shouldBe true
            val prefix = emitted.toString(Charsets.UTF_8.name())
            prefix shouldContain "\"timeMs\":$timeMs"
            // One complete record was emitted, but the outer JSON array was never completed.
            prefix.endsWith("]") shouldBe false
            JSONArray("$prefix]").length() shouldBe 1
            folder.listFiles()?.size shouldBe 0
        } finally {
            database.close()
            folder.listFiles()?.forEach(File::delete)
            folder.delete()
        }
    }

    @Test
    fun `export error removes the partial file before reporting failure`() = runTest(dispatcher) {
        val folder = File(context.cacheDir, "failed-export-test").apply { mkdirs() }
        try {
            newViewModel().exportDocument(
                DocumentFile.fromFile(folder), true, exporter { output ->
                    output.write("partial".toByteArray())
                    ExportResult.Error()
                }, "failed", null,
            ).shouldBeInstanceOf<ExportDocumentResult.Failure>()
            folder.listFiles()?.size shouldBe 0
        } finally {
            folder.listFiles()?.forEach(File::delete)
            folder.delete()
        }
    }

    private fun newViewModel(): ImportExportViewModel {
        val repository = mockk<ImportExportDataRepository>()
        coEvery { repository.hasTrips() } returns true
        return ImportExportViewModel(context, repository, object : DispatchersProvider {
            override val io: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
            override val main: CoroutineDispatcher = dispatcher
            override val unconfined: CoroutineDispatcher = dispatcher
        }, object : TrackingStartupGate {
            override val isReady = true
            override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0L)
        }, mockk<ExportPlanStore> { every { unfinishedFiles } returns flowOf(emptyList()) })
    }

    private fun exporter(block: suspend (OutputStream) -> ExportResult) = object : Exporter {
        override val requiresLocationData = false
        override val containsSensitiveLocationData = false
        override val canSelectDateRange = false
        override val mimeType = "application/json"
        override val extension = "json"
        override suspend fun export(
            context: Context,
            locationData: Sequence<LocationSample>,
            outputStream: OutputStream,
            dateRange: LongRange?,
        ): ExportResult = block(outputStream)
    }
}
