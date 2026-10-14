package com.adsamcik.tracker.impexp.exporter.activity

import android.app.Application
import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.OwnedTransferCleanupException
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.impexp.exporter.data.ImportExportDataRepository
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.model.LocationSample
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ManualExportTransferBoundaryTest {
    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val repository = mockk<ImportExportDataRepository> {
        coEvery { hasTrips() } returns true
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `closure after synchronous output success cannot publish success or finish the screen`() = runTest(dispatcher) {
        val gate = TestGate()
        val folder = File(context.cacheDir, "closed-before-result")
        val model = viewModel(gate)
        var finished = false
        var failure = false
        try {
            model.exportToDocument(DocumentFile.fromFile(folder), true,
                exporter { output -> output.write(7); gate.closed = true; ExportResult.Success },
                "data", null, prepareDirectory = { folder.mkdirs() }) { result ->
                if (result is ExportDocumentResult.Success) model.handOffExport(result) { finished = true }
                else failure = true
            }
            advanceUntilIdle()
            finished shouldBe false
            failure shouldBe true
            model.uiState.value.exportCompletionState shouldBe ExportCompletionState.Failed
        } finally { folder.listFiles()?.forEach(File::delete); folder.delete() }
    }

    @Test
    fun `turning tracking off during the write preserves the successful history export`() = runTest(dispatcher) {
        val gate = TestGate()
        val folder = File(context.cacheDir, "off-during-export")
        val model = viewModel(gate)
        var finished = false
        try {
            model.exportToDocument(DocumentFile.fromFile(folder), true,
                exporter { output -> output.write(7); gate.collectionStopped = true; ExportResult.Success },
                "data", null, prepareDirectory = { folder.mkdirs() }) { result ->
                finished = model.handOffExport(result.shouldBeInstanceOf<ExportDocumentResult.Success>()) { }
            }
            advanceUntilIdle()
            gate.isReady shouldBe false
            gate.permitsNewCollection shouldBe false
            finished shouldBe true
            model.uiState.value.exportCompletionState shouldBe ExportCompletionState.Succeeded
            folder.resolve("data.dat").readBytes().toList() shouldBe listOf(7.toByte())
        } finally { folder.listFiles()?.forEach(File::delete); folder.delete() }
    }

    @Test
    fun `ordinary Share preparation leaves concurrently open GPX and PNG share files intact`() = runTest(dispatcher) {
        val owned = File(context.filesDir, "sharable").apply { mkdirs() }
        val gpx = File(owned, "concurrent-share.gpx")
        val png = File(owned, "concurrent-share.png")
        val cache = File(context.cacheDir, "sharable")
        try {
            gpx.outputStream().use { gpxOutput ->
                png.outputStream().use { pngOutput ->
                    gpxOutput.write(7); pngOutput.write(8)
                    prepareShareableDirectory(cache)
                    // Both physical owners can finish and hand off their own existing files.
                    gpxOutput.write(9); pngOutput.write(10)
                    gpx.exists() shouldBe true
                    png.exists() shouldBe true
                }
            }
            gpx.readBytes().toList() shouldBe listOf(7.toByte(), 9.toByte())
            png.readBytes().toList() shouldBe listOf(8.toByte(), 10.toByte())
        } finally { gpx.delete(); png.delete() }
    }

    @Test
    fun `independent Export screens sharing the same name cannot remove each others files`() = runTest(dispatcher) {
        val root = File(context.cacheDir, "concurrent-share-requests")
        val firstDirectory = shareRequestDirectory(root)
        val secondDirectory = shareRequestDirectory(root)
        val firstGate = TestGate()
        val secondGate = TestGate()
        val first = viewModel(firstGate)
        val second = viewModel(secondGate)
        val firstWriting = CompletableDeferred<Unit>()
        val secondWriting = CompletableDeferred<Unit>()
        val finishSecond = CompletableDeferred<Unit>()
        var secondHandedOff = false
        try {
            first.exportToDocument(DocumentFile.fromFile(firstDirectory), true,
                exporter { output -> output.write(7); firstWriting.complete(Unit); awaitCancellation() },
                "same-name", null,
                prepareDirectory = { prepareShareRequestDirectory(firstDirectory) },
                cleanupDirectory = { removeShareRequestDirectory(firstDirectory) }) { }
            firstWriting.await()
            second.exportToDocument(DocumentFile.fromFile(secondDirectory), true,
                exporter { output -> output.write(8); secondWriting.complete(Unit); finishSecond.await(); ExportResult.Success },
                "same-name", null,
                prepareDirectory = { prepareShareRequestDirectory(secondDirectory) },
                cleanupDirectory = { if (!secondHandedOff) removeShareRequestDirectory(secondDirectory) }) { result ->
                secondHandedOff = second.handOffExport(result.shouldBeInstanceOf<ExportDocumentResult.Success>()) { }
            }
            secondWriting.await()
            firstGate.child!!.cancel()
            firstGate.child!!.join()
            runCurrent()
            firstDirectory.exists() shouldBe false
            secondDirectory.resolve("same-name.dat").readBytes().toList() shouldBe listOf(8.toByte())
            secondGate.child!!.isCompleted shouldBe false
            finishSecond.complete(Unit)
            secondGate.child!!.join()
            runCurrent()
            secondHandedOff shouldBe true
            secondDirectory.resolve("same-name.dat").readBytes().toList() shouldBe listOf(8.toByte())
        } finally {
            firstGate.child?.cancel(); secondGate.child?.cancel()
            firstGate.child?.join(); secondGate.child?.join()
            firstDirectory.deleteRecursively(); secondDirectory.deleteRecursively(); root.delete()
        }
    }

    @Test
    fun `share aging removes only old recognized request directories`() {
        val root = kotlin.io.path.createTempDirectory("share-aging-").toFile()
        val now = System.currentTimeMillis()
        val old = shareRequestDirectory(root)
        val fresh = shareRequestDirectory(root)
        val unrelated = root.resolve("unrelated-folder")
        try {
            old.mkdir(); fresh.mkdir(); unrelated.mkdir()
            old.resolve("same-name.dat").writeText("old")
            fresh.resolve("same-name.dat").writeText("fresh")
            unrelated.resolve("keep.dat").writeText("keep")
            old.setLastModified(now - 25L * 60L * 60L * 1_000L)
            fresh.setLastModified(now)
            unrelated.setLastModified(now - 25L * 60L * 60L * 1_000L)
            cleanupShareableDirectory(root, nowMillis = now)
            old.exists() shouldBe false
            fresh.resolve("same-name.dat").readText() shouldBe "fresh"
            unrelated.resolve("keep.dat").readText() shouldBe "keep"
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `cancellation and aging preserve unexpected nested contents of an owned request`() {
        for (aging in listOf(false, true)) {
            val root = kotlin.io.path.createTempDirectory("share-nested-").toFile()
            val request = shareRequestDirectory(root).apply { mkdir() }
            val direct = request.resolve("export.dat").apply { writeText("owned") }
            val nested = request.resolve("unexpected/keep.dat").apply {
                parentFile!!.mkdir(); writeText("keep")
            }
            try {
                request.setLastModified(System.currentTimeMillis() - 25L * 60L * 60L * 1_000L)
                shouldThrow<OwnedTransferCleanupException> {
                    if (aging) cleanupShareableDirectory(root) else removeShareRequestDirectory(request)
                }
                direct.readText() shouldBe "owned"
                nested.readText() shouldBe "keep"
                request.isDirectory shouldBe true
            } finally { nested.delete(); nested.parentFile!!.delete(); direct.delete(); request.delete(); root.delete() }
        }
    }

    @Test
    fun `cancellation and aging never follow a recognized request directory link`() {
        for (aging in listOf(false, true)) {
            val root = kotlin.io.path.createTempDirectory("share-linked-").toFile()
            val outside = kotlin.io.path.createTempDirectory("outside-share-").toFile()
            val retained = outside.resolve("keep.dat").apply { writeText("keep") }
            val request = shareRequestDirectory(root)
            try {
                val created = runCatching { Files.createSymbolicLink(request.toPath(), outside.toPath()) }.isSuccess
                assumeTrue("Host cannot create symbolic links", created)
                outside.setLastModified(System.currentTimeMillis() - 25L * 60L * 60L * 1_000L)
                shouldThrow<OwnedTransferCleanupException> {
                    if (aging) cleanupShareableDirectory(root) else removeShareRequestDirectory(request)
                }
                retained.readText() shouldBe "keep"
                Files.isSymbolicLink(request.toPath()) shouldBe true
            } finally { Files.deleteIfExists(request.toPath()); retained.delete(); outside.delete(); root.delete() }
        }
    }

    @Test
    fun `rejected transfer cannot create a directory or read export data`() = runTest(dispatcher) {
        val gate = TestGate().apply { closed = true }
        val folder = File(context.cacheDir, "rejected-transfer")
        var prepared = false
        var written = false
        var failure = false
        viewModel(gate).exportToDocument(DocumentFile.fromFile(folder), true,
            exporter { written = true; ExportResult.Success }, "data", null,
            prepareDirectory = { prepared = true; folder.mkdirs() }) {
            failure = it is ExportDocumentResult.Failure
        }
        advanceUntilIdle()
        prepared shouldBe false
        written shouldBe false
        folder.exists() shouldBe false
        failure shouldBe true
    }

    @Test
    fun `share preparation stream and delayed summary belong to one transfer when tracking is off`() = runTest(dispatcher) {
        val gate = TestGate()
        val folder = File(context.cacheDir, "structured-manual-share")
        val summaryEntered = CompletableDeferred<Unit>()
        val allowSummary = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<Unit>()
        val range = Instant.ofEpochMilli(1_000L).atZone(ZoneOffset.UTC)..
            Instant.ofEpochMilli(2_000L).atZone(ZoneOffset.UTC)
        coEvery { repository.loadTripShareSnapshot(1_000L, 2_000L) } coAnswers {
            gate.inside shouldBe true
            summaryEntered.complete(Unit)
            allowSummary.await()
            null
        }
        val model = viewModel(gate)
        try {
            model.exportToDocument(DocumentFile.fromFile(folder), true,
                exporter { output -> gate.inside shouldBe true; output.write(7); ExportResult.Success },
                "data", range, prepareDirectory = { gate.inside shouldBe true; folder.mkdirs() }) { result ->
                gate.inside shouldBe true
                val success = result.shouldBeInstanceOf<ExportDocumentResult.Success>()
                model.resolveShareTripSummary(success.baseFileName, range)
                model.handOffExport(success) { gate.inside shouldBe true } shouldBe true
                delivered.complete(Unit)
            }
            summaryEntered.await()
            gate.transferCount shouldBe 1
            gate.child?.isCompleted shouldBe false
            folder.listFiles()?.single()?.readBytes()?.toList() shouldBe listOf(7.toByte())
            allowSummary.complete(Unit)
            delivered.await()
            runCurrent()
            gate.inside shouldBe false
        } finally { folder.listFiles()?.forEach(File::delete); folder.delete() }
    }

    @Test
    fun `clear during delayed share summary cancels handoff and preserves the ViewModel owner`() = runTest(dispatcher) {
        val gate = TestGate()
        val folder = File(context.cacheDir, "cancelled-summary-share")
        val summaryEntered = CompletableDeferred<Unit>()
        val range = Instant.ofEpochMilli(1_000L).atZone(ZoneOffset.UTC)..
            Instant.ofEpochMilli(2_000L).atZone(ZoneOffset.UTC)
        coEvery { repository.loadTripShareSnapshot(any(), any()) } coAnswers {
            summaryEntered.complete(Unit); awaitCancellation()
        }
        val model = viewModel(gate)
        var handedOff = false
        try {
            model.exportToDocument(DocumentFile.fromFile(folder), true,
                exporter { it.write(7); ExportResult.Success }, "data", range,
                prepareDirectory = { folder.mkdirs() }) { result ->
                val success = result.shouldBeInstanceOf<ExportDocumentResult.Success>()
                model.resolveShareTripSummary(success.baseFileName, range)
                model.handOffExport(success) { handedOff = true }
            }
            summaryEntered.await()
            gate.closed = true
            gate.child!!.cancel()
            gate.child!!.join()
            runCurrent()
            handedOff shouldBe false
            model.uiState.value.exportCompletionState shouldBe ExportCompletionState.Failed
            // Cancelling one transfer leaves the ViewModel available for a later user action.
            gate.closed = false
            var retried = false
            model.exportToDocument(DocumentFile.fromFile(folder), true,
                exporter { ExportResult.Success }, "retry", null) { retried = true }
            advanceUntilIdle()
            retried shouldBe true
        } finally { folder.listFiles()?.forEach(File::delete); folder.delete() }
    }

    @Test
    fun `old manual request is not admitted after startup completes a deletion`() = runTest(dispatcher) {
        val gate = TestGate().apply { advanceOnReconcile = true }
        val folder = File(context.cacheDir, "old-generation-export")
        viewModel(gate).exportDocument(DocumentFile.fromFile(folder), true,
            exporter { error("Stale request reached the writer") }, "data", null)
            .shouldBeInstanceOf<ExportDocumentResult.Failure>()
        folder.exists() shouldBe false
    }

    @Test
    fun `folder preparation failure reports once and does not leave exporting state`() = runTest(dispatcher) {
        val gate = TestGate()
        val folder = File(context.cacheDir, "failed-prepare-export")
        val model = viewModel(gate)
        var results = 0
        model.exportToDocument(DocumentFile.fromFile(folder), true,
            exporter { error("Failed preparation reached writer") }, "data", null,
            prepareDirectory = { throw java.io.IOException("Folder unavailable") }) {
            it.shouldBeInstanceOf<ExportDocumentResult.Failure>(); results++
        }
        advanceUntilIdle()
        results shouldBe 1
        model.uiState.value.exportCompletionState shouldBe ExportCompletionState.Failed
    }

    private fun viewModel(gate: TrackingStartupGate) = ImportExportViewModel(
        context, repository, TestDispatchersProvider(dispatcher), gate,
        mockk<ExportPlanStore> { every { unfinishedFiles } returns flowOf(emptyList()) },
    )

    private fun exporter(write: suspend (OutputStream) -> ExportResult) = object : Exporter {
        override val requiresLocationData = false
        override val containsSensitiveLocationData = false
        override val canSelectDateRange = false
        override val mimeType = "application/octet-stream"
        override val extension = "dat"
        override suspend fun export(context: Context, locationData: Sequence<LocationSample>,
            outputStream: OutputStream, dateRange: LongRange?) = write(outputStream)
    }

    /** Feature contract double; the production barrier's actual drain is tested in the app module. */
    private class TestGate : TrackingStartupGate {
        var closed = false
        var collectionStopped = false
        var generation = 0L
        var advanceOnReconcile = false
        var child: Job? = null
        var inside = false
        var transferCount = 0
        override val currentGeneration get() = generation
        override val isReady get() = !closed && !collectionStopped
        override val permitsNewCollection = false
        override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult {
            if (advanceOnReconcile) generation++
            return TrackingStartupResult.Ready(false, 0L)
        }
        override fun <T> withRetainedDataGeneration(expectedGeneration: Long, operation: () -> T): T? =
            if (!closed && generation == expectedGeneration) operation() else null
        override suspend fun <T> withReadyGenerationTransfer(expectedGeneration: Long,
            operation: suspend () -> T): T? = coroutineScope {
            if (withRetainedDataGeneration(expectedGeneration) { true } != true) return@coroutineScope null
            val transfer = async {
                transferCount++; inside = true
                try { operation() } finally { inside = false }
            }
            child = transfer
            transfer.await()
        }
    }
}
