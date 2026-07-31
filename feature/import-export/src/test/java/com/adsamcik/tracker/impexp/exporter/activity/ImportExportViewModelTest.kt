package com.adsamcik.tracker.impexp.exporter.activity

import android.content.Context
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.data.ImportExportDataRepository
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
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
}
