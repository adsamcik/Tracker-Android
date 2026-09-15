package com.adsamcik.tracker.impexp.exporter.activity

import android.content.Context
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.data.ImportExportDataRepository
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.model.LocationSample
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportExportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `export completion remains pending until the write reports success`() {
        val viewModel = newViewModel()

        viewModel.uiState.value.exportCompletionState shouldBe ExportCompletionState.Idle

        viewModel.beginExport()
        viewModel.uiState.value.exportCompletionState shouldBe ExportCompletionState.Exporting

        viewModel.completeExport(
            ExportDocumentResult.Failure(ExportResult.Error())
        )
        viewModel.uiState.value.exportCompletionState shouldBe ExportCompletionState.Failed

        viewModel.beginExport()
        viewModel.completeExport(
            ExportDocumentResult.Success(
                fileNameWithExtension = "export.gpx",
                baseFileName = "export",
            )
        )
        viewModel.uiState.value.exportCompletionState shouldBe ExportCompletionState.Succeeded
    }

    @Test
    fun `initial state reports that no exportable trips exist`() = runTest(dispatcher) {
        val repository = mockk<ImportExportDataRepository>()
        coEvery { repository.hasTrips() } returns false

        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        viewModel.uiState.value.showNoDataDialog shouldBe true
    }

    @Test
    fun `source-owned range export bypasses Location preflight`() = runTest(dispatcher) {
        val repository = mockk<ImportExportDataRepository>(relaxed = true)
        coEvery { repository.hasTrips() } returns false
        val viewModel = newViewModel(repository)
        val exporter = SourceOwnedExporter()
        val from = ZonedDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val to = from.plusDays(1)

        val result = viewModel.exportToStream(
            outputStream = ByteArrayOutputStream(),
            exporter = exporter,
            range = from..to,
        )

        result shouldBe ExportResult.Success
        exporter.receivedRange shouldBe from.toInstant().toEpochMilli()..to.toInstant().toEpochMilli()
        exporter.receivedLocationCount shouldBe 0
        coVerify(exactly = 0) { repository.countLocationSamples(any(), any()) }
        verify(exactly = 0) { repository.pagedLocationSamples(any(), any(), any()) }
    }

    private fun newViewModel() = newViewModel(
        dataRepository = mockk(relaxed = true),
    )

    private fun newViewModel(
        dataRepository: ImportExportDataRepository,
    ) = ImportExportViewModel(
        appContext = mockk<Context>(relaxed = true),
        dataRepository = dataRepository,
        dispatchers = object : DispatchersProvider {
            override val io: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
            override val main: CoroutineDispatcher = dispatcher
            override val unconfined: CoroutineDispatcher = dispatcher
        },
    )

    private class SourceOwnedExporter : Exporter {
        override val requiresLocationData: Boolean = false
        override val containsSensitiveLocationData: Boolean = false
        override val canSelectDateRange: Boolean = true
        override val mimeType: String = "application/test"
        override val extension: String = "test"
        var receivedRange: LongRange? = null
        var receivedLocationCount: Int? = null

        override suspend fun export(
            context: Context,
            locationData: Sequence<LocationSample>,
            outputStream: OutputStream,
            dateRange: LongRange?,
        ): ExportResult {
            receivedRange = dateRange
            receivedLocationCount = locationData.count()
            return ExportResult.Success
        }
    }
}
