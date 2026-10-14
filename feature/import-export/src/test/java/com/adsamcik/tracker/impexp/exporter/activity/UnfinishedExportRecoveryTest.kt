package com.adsamcik.tracker.impexp.exporter.activity

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.impexp.exporter.automation.UnfinishedExportFile
import com.adsamcik.tracker.impexp.exporter.data.ImportExportDataRepository
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.SystemClock
import com.adsamcik.tracker.shared.model.LocationSample
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual ViewModel + DataStore chain; Android document operations are explicit SDK fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class UnfinishedExportRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private val realContext = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context> { every { contentResolver } returns resolver }
    private val models = ViewModelStore()
    private var modelId = 0
    private val store = ExportPlanStore(realContext, Dispatchers.Unconfined, SystemClock)
    private val gate = object : TrackingStartupGate {
        override val isReady = true
        override val permitsNewCollection = false
        override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0L)
    }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(DocumentFile::class)
    }

    @After fun tearDown() {
        models.clear()
        unmockkStatic(DocumentFile::class)
        Dispatchers.resetMain()
    }

    @Test
    fun `failed external export reappears after reopening even with no recorded data`() = runTest(dispatcher) {
        val uri = Uri.parse("content://manual-recovery/document/failed")
        val destination = destination(uri, deleted = false)
        val folder = folder(destination)
        every { resolver.openOutputStream(uri, "w") } returns ByteArrayOutputStream()
        try {
            viewModel().exportDocument(folder, true, exporter { ExportResult.Error() }, "failed", null)
                .shouldBeInstanceOf<ExportDocumentResult.Failure>()
            val reopened = viewModel(hasTrips = false)
            val state = reopened.uiState.first { it.showNoDataDialog && it.unfinishedFiles.any { file -> file.uri == uri.toString() } }
            state.unfinishedFiles.first { it.uri == uri.toString() }.name shouldBe "failed.json"
            state.hasUnfinishedFileRecovery shouldBe true
            verify(exactly = 1) { destination.delete() }
        } finally {
            store.forgetUnfinishedFile(uri.toString())
        }
    }

    @Test
    fun `cancelled external export waits for failed cleanup notice before the child finishes`() = runTest(dispatcher) {
        val uri = Uri.parse("content://manual-recovery/document/cancelled")
        val started = CompletableDeferred<Unit>()
        val destination = destination(uri, deleted = false)
        every { resolver.openOutputStream(uri, "w") } returns ByteArrayOutputStream()
        try {
            val task = async {
                viewModel().exportDocument(folder(destination), true, exporter {
                    it.write("partial".toByteArray())
                    started.complete(Unit)
                    awaitCancellation()
                }, "cancelled", null)
            }
            started.await()
            task.cancelAndJoin()
            store.unfinishedFiles.first().first { it.uri == uri.toString() }.name shouldBe "cancelled.json"
        } finally {
            store.forgetUnfinishedFile(uri.toString())
        }
    }

    @Test
    fun `Retry removes only the selected URI while equally named sibling remains`() = runTest(dispatcher) {
        val first = UnfinishedExportFile("content://manual-recovery/document/first", "same.json")
        val sibling = UnfinishedExportFile("content://manual-recovery/document/sibling", "same.json")
        val firstDocument = destination(Uri.parse(first.uri), deleted = true)
        val siblingDocument = destination(Uri.parse(sibling.uri), deleted = true)
        every { DocumentFile.fromSingleUri(context, Uri.parse(first.uri)) } returns firstDocument
        every { DocumentFile.fromSingleUri(context, Uri.parse(sibling.uri)) } returns siblingDocument
        try {
            store.rememberUnfinishedFile(first)
            store.rememberUnfinishedFile(sibling)
            val model = viewModel()
            model.uiState.first { it.unfinishedFiles.any { file -> file.uri == first.uri } }
            model.removeUnfinishedFile(first.uri)
            model.uiState.first { it.unfinishedFileRemoving == null && it.unfinishedFiles.none { file -> file.uri == first.uri } }
            store.unfinishedFiles.first().first { it.uri == sibling.uri } shouldBe sibling
            verify(exactly = 1) { firstDocument.delete() }
            verify(exactly = 0) { siblingDocument.delete() }
        } finally {
            store.forgetUnfinishedFile(first.uri)
            store.forgetUnfinishedFile(sibling.uri)
        }
    }

    @Test
    fun `lost document access preserves the notice and actionable Files guidance`() = runTest(dispatcher) {
        val file = UnfinishedExportFile("content://manual-recovery/document/lost-access", "lost.json")
        val document = destination(Uri.parse(file.uri), deleted = false)
        every { document.delete() } throws SecurityException("Grant revoked")
        every { DocumentFile.fromSingleUri(context, Uri.parse(file.uri)) } returns document
        every { resolver.query(any(), any<Array<String>>(), null, null, null) } throws SecurityException("Grant revoked")
        try {
            store.rememberUnfinishedFile(file)
            val model = viewModel()
            model.uiState.first { it.unfinishedFiles.any { saved -> saved.uri == file.uri } }
            model.removeUnfinishedFile(file.uri)
            model.uiState.first { it.unfinishedFileErrors[file.uri] == UnfinishedFileRemovalError.FILE_ACCESS }
            store.unfinishedFiles.first().first { it.uri == file.uri } shouldBe file
        } finally {
            store.forgetUnfinishedFile(file.uri)
        }
    }

    @Test
    fun `successful empty exact-file query clears notice after removal in Files`() = runTest(dispatcher) {
        val file = UnfinishedExportFile("content://manual-recovery/document/already-removed", "removed.json")
        every { DocumentFile.fromSingleUri(context, Uri.parse(file.uri)) } returns destination(Uri.parse(file.uri), deleted = false)
        every { resolver.query(Uri.parse(file.uri), any<Array<String>>(), null, null, null) } returns
            MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
        try {
            store.rememberUnfinishedFile(file)
            val model = viewModel()
            model.uiState.first { it.unfinishedFiles.any { saved -> saved.uri == file.uri } }
            model.removeUnfinishedFile(file.uri)
            model.uiState.first { it.unfinishedFileRemoving == null && it.unfinishedFiles.none { saved -> saved.uri == file.uri } }
            store.unfinishedFiles.first().none { it.uri == file.uri } shouldBe true
        } finally {
            store.forgetUnfinishedFile(file.uri)
        }
    }

    @Test
    fun `load failure stays reachable after Clear and Retry reads saved notices`() = runTest(dispatcher) {
        val file = UnfinishedExportFile("content://manual-recovery/document/load", "load.json")
        val brokenStore = mockk<ExportPlanStore>()
        every { brokenStore.unfinishedFiles } returns flow { throw IOException("Storage unavailable") }
        val model = viewModel(brokenStore, hasTrips = false)
        val failed = model.uiState.first { it.unfinishedFilesLoadFailed && it.showNoDataDialog }
        failed.hasUnfinishedFileRecovery shouldBe true
        every { brokenStore.unfinishedFiles } returns flowOf(listOf(file))
        model.loadUnfinishedFiles()
        model.uiState.first { !it.unfinishedFilesLoadFailed && it.unfinishedFiles == listOf(file) }
    }

    @Test
    fun `failed notice persistence is visible and never claims a remembered file`() = runTest(dispatcher) {
        val uri = Uri.parse("content://manual-recovery/document/unsaved")
        val brokenStore = mockk<ExportPlanStore>()
        every { brokenStore.unfinishedFiles } returns flowOf(emptyList())
        coEvery { brokenStore.rememberUnfinishedFile(any()) } throws IOException("Write failed")
        every { resolver.openOutputStream(uri, "w") } returns ByteArrayOutputStream()
        val model = viewModel(brokenStore)
        model.exportDocument(folder(destination(uri, false)), true, exporter { ExportResult.Error() }, "unsaved", null)
            .shouldBeInstanceOf<ExportDocumentResult.Failure>()
        model.uiState.value.unfinishedFileNoticeSaveFailed shouldBe true
        model.uiState.value.unfinishedFiles shouldBe emptyList()
    }

    @Test
    fun `failed private document cleanup is never added to external notices`() = runTest(dispatcher) {
        val before = store.unfinishedFiles.first()
        val privateUri = Uri.fromFile(java.io.File.createTempFile("private-export-", ".json", realContext.cacheDir))
        val destination = destination(privateUri, false)
        val directory = mockk<DocumentFile> {
            every { uri } returns Uri.fromFile(realContext.cacheDir)
            every { createFile(any(), any()) } returns destination
        }
        try {
            viewModel().exportDocument(directory, true, exporter { ExportResult.Error() }, "private", null)
            store.unfinishedFiles.first() shouldBe before
        } finally {
            java.io.File(requireNotNull(privateUri.path)).delete()
        }
    }

    private fun destination(savedUri: Uri, deleted: Boolean) = mockk<DocumentFile> {
        every { uri } returns savedUri
        every { delete() } returns deleted
    }

    private fun folder(destination: DocumentFile) = mockk<DocumentFile> {
        every { uri } returns Uri.parse("content://manual-recovery/tree/exports")
        every { createFile(any(), any()) } returns destination
    }

    private fun viewModel(planStore: ExportPlanStore = store, hasTrips: Boolean = true): ImportExportViewModel {
        val repository = mockk<ImportExportDataRepository>()
        coEvery { repository.hasTrips() } returns hasTrips
        return ImportExportViewModel(context, repository, TestDispatchersProvider(dispatcher), gate, planStore)
            .also { models.put("model-${modelId++}", it) }
    }

    private fun exporter(write: suspend (OutputStream) -> ExportResult) = object : Exporter {
        override val requiresLocationData = false
        override val containsSensitiveLocationData = false
        override val canSelectDateRange = false
        override val mimeType = "application/json"
        override val extension = "json"
        override suspend fun export(context: Context, locationData: Sequence<LocationSample>,
            outputStream: OutputStream, dateRange: LongRange?) = write(outputStream)
    }
}
