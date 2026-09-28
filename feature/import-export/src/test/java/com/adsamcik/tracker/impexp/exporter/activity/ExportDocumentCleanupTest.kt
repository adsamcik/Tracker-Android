package com.adsamcik.tracker.impexp.exporter.activity

import android.app.Application
import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.impexp.exporter.data.ImportExportDataRepository
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.model.LocationSample
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
        })
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
