package com.adsamcik.tracker.impexp.exporter.activity

import android.content.Context
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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

    private fun newViewModel() = ImportExportViewModel(
        appContext = mockk<Context>(relaxed = true),
        appDatabase = mockk<AppDatabase>(relaxed = true),
        dispatchers = object : DispatchersProvider {
            override val io: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
            override val main: CoroutineDispatcher = dispatcher
            override val unconfined: CoroutineDispatcher = dispatcher
        },
    )
}
