package com.adsamcik.tracker.impexp.exporter.activity

import android.content.Context
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.RadioSourceExporter
import com.adsamcik.tracker.impexp.exporter.data.ImportExportDataRepository
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.model.LocationSample
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
                startupGeneration = 0L,
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

    @Test
    fun `source calendar selection includes every hour of the chosen days`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        val exporter = SourceOwnedExporter(calendarDates = true)
        val zone = ZoneId.of("Europe/Prague")
        val from = ZonedDateTime.of(2026, 3, 28, 14, 0, 0, 0, zone)
        val to = ZonedDateTime.of(2026, 3, 29, 9, 0, 0, 0, zone)

        viewModel.exportToStream(ByteArrayOutputStream(), exporter, from..to) shouldBe ExportResult.Success
        exporter.receivedRange shouldBe
            from.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()..(
                to.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
            )
    }

    @Test
    fun `radio date switch reaches only the source file exporter`() = runTest(dispatcher) {
        val repository = mockk<ImportExportDataRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        val exporter = RadioExporter()
        val zone = ZoneId.of("Europe/Prague")
        val day = ZonedDateTime.of(2026, 3, 29, 12, 0, 0, 0, zone)

        viewModel.setIncludeCompleteSessions(false)
        viewModel.exportToStream(ByteArrayOutputStream(), exporter, day..day) shouldBe ExportResult.Success

        exporter.includeSessionsAcrossDates shouldBe false
        exporter.receivedRange shouldBe day.toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()..(
                day.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
            )
        coVerify(exactly = 0) { repository.countLocationSamples(any(), any()) }
    }

    @Test
    fun `calendar picker preserves the displayed day in either time zone`() {
        for (zone in listOf(ZoneId.of("Europe/Prague"), ZoneId.of("America/Los_Angeles"))) {
            val shown = ZonedDateTime.of(2026, 9, 28, 19, 0, 0, 0, zone)
            calendarPickerDay(calendarPickerSelectionMs(shown), zone).toLocalDate() shouldBe shown.toLocalDate()
        }
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
        startupGate = object : TrackingStartupGate {
            override val isReady = true
            override val permitsNewCollection = false
            override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0L)
        },
        exportPlanStore = mockk<ExportPlanStore> { every { unfinishedFiles } returns flowOf(emptyList()) },
    )

    private class SourceOwnedExporter(calendarDates: Boolean = false) : Exporter {
        override val requiresLocationData: Boolean = false
        override val containsSensitiveLocationData: Boolean = false
        override val canSelectDateRange: Boolean = true
        override val selectsCalendarDates: Boolean = calendarDates
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

    private class RadioExporter : RadioSourceExporter {
        override val requiresLocationData = false
        override val containsSensitiveLocationData = false
        override val canSelectDateRange = true
        override val selectsCalendarDates = true
        override val mimeType = "application/test-radio"
        override val extension = "testradio"
        var includeSessionsAcrossDates: Boolean? = null
        var receivedRange: LongRange? = null

        override suspend fun exportWithSessionSelection(
            context: Context,
            outputStream: OutputStream,
            dateRange: LongRange?,
            includeSessionsAcrossDates: Boolean,
        ): ExportResult {
            this.includeSessionsAcrossDates = includeSessionsAcrossDates
            receivedRange = dateRange
            return ExportResult.Success
        }

        override suspend fun export(
            context: Context,
            locationData: Sequence<LocationSample>,
            outputStream: OutputStream,
            dateRange: LongRange?,
        ): ExportResult = exportWithSessionSelection(context, outputStream, dateRange, true)
    }
}
