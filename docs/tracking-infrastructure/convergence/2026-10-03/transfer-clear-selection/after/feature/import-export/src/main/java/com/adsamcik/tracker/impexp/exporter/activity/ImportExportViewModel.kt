package com.adsamcik.tracker.impexp.exporter.activity

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.impexp.exporter.SourceExportDates
import com.adsamcik.tracker.impexp.exporter.data.ImportExportDataRepository
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.openOutputStream
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.preferences.extension.formatDistance
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ExportCompletionState {
    Idle,
    Exporting,
    Succeeded,
    Failed,
}

internal data class ImportExportUiState(
    val showNoDataDialog: Boolean = false,
    val exportCompletionState: ExportCompletionState = ExportCompletionState.Idle,
)

internal sealed interface ExportDocumentResult {
    data class Success(
        val fileNameWithExtension: String,
        val baseFileName: String,
        val startupGeneration: Long,
    ) : ExportDocumentResult

    data class Failure(val error: ExportResult.Error) : ExportDocumentResult
}

@HiltViewModel
class ImportExportViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val dataRepository: ImportExportDataRepository,
    private val dispatchers: DispatchersProvider,
    private val startupGate: TrackingStartupGate,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportExportUiState())
    internal val uiState: StateFlow<ImportExportUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val hasTrips = withContext(dispatchers.io) {
                dataRepository.hasTrips()
            }
            _uiState.update { it.copy(showNoDataDialog = !hasTrips) }
        }
    }

    internal fun exportToDocument(
        directory: DocumentFile,
        forceOverride: Boolean,
        exporter: Exporter,
        fileName: String,
        range: ClosedRange<ZonedDateTime>?,
        prepareDirectory: suspend () -> Unit = {},
        cleanupDirectory: suspend () -> Unit = {},
        onResult: suspend (ExportDocumentResult) -> Unit,
    ) {
        val generation = startupGate.currentGeneration
        beginExport()
        viewModelScope.launch {
            try {
                if (startupGate.reconcile() !is TrackingStartupResult.Ready) {
                    val result = ExportDocumentResult.Failure(ExportResult.Error())
                    completeExport(result)
                    onResult(result)
                    return@launch
                }
                val delivered = startupGate.withReadyGenerationTransfer(generation) {
                    try {
                        val result = try {
                            withContext(dispatchers.io) { prepareDirectory() }
                            exportDocumentInTransfer(
                                directory, forceOverride, exporter, fileName, range,
                                generation,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            ExportDocumentResult.Failure(ExportResult.Error())
                        }
                        val deliveredResult = if (result is ExportDocumentResult.Success &&
                            startupGate.withRetainedDataGeneration(generation) { completeExport(result); true } != true
                        ) ExportDocumentResult.Failure(ExportResult.Error()) else result
                        if (deliveredResult !is ExportDocumentResult.Success) completeExport(deliveredResult)
                        currentCoroutineContext().ensureActive()
                        onResult(deliveredResult)
                        true
                    } finally {
                        withContext(NonCancellable + dispatchers.io) { cleanupDirectory() }
                    }
                }
                if (delivered != true) {
                    val result = ExportDocumentResult.Failure(ExportResult.Error())
                    completeExport(result)
                    onResult(result)
                }
            } catch (cancelled: CancellationException) {
                completeExport(ExportDocumentResult.Failure(ExportResult.Error()))
                throw cancelled
            } catch (_: Exception) {
                val result = ExportDocumentResult.Failure(ExportResult.Error())
                completeExport(result)
                onResult(result)
            }
        }
    }

    internal suspend fun exportDocument(
        directory: DocumentFile,
        forceOverride: Boolean,
        exporter: Exporter,
        fileName: String,
        range: ClosedRange<ZonedDateTime>?,
    ): ExportDocumentResult {
        val generation = startupGate.currentGeneration
        if (startupGate.reconcile() !is TrackingStartupResult.Ready)
            return ExportDocumentResult.Failure(ExportResult.Error())
        return startupGate.withReadyGenerationTransfer(generation) {
            exportDocumentInTransfer(directory, forceOverride, exporter, fileName, range,
                generation)
        } ?: ExportDocumentResult.Failure(ExportResult.Error())
    }

    /** Source reads, output closure and partial cleanup all belong to the admitted child job. */
    private suspend fun exportDocumentInTransfer(
        directory: DocumentFile,
        forceOverride: Boolean,
        exporter: Exporter,
        fileName: String,
        range: ClosedRange<ZonedDateTime>?,
        generation: Long,
    ): ExportDocumentResult {
        var createdFile: DocumentFile? = null
        return try {
            withContext(dispatchers.io) {
                val actualFileName = getExportFileName(fileName, exporter, range, appContext)
                val finalFileName = if (forceOverride) {
                    actualFileName
                } else {
                    findAvailableFileName(directory, actualFileName, exporter.extension)
                }
                val fileNameWithExtension = "${finalFileName}.${exporter.extension}"
                val trimmedName = preventDoubleExtension(fileNameWithExtension, exporter)
                val destinationFile = directory.createFile(exporter.mimeType, trimmedName)
                    ?: return@withContext ExportDocumentResult.Failure(
                        ExportResult.Error(
                            LocalizedString(R.string.export_error_stream_failed, fileNameWithExtension)
                        )
                    )
                createdFile = destinationFile

                when (val exportResult = exportToFile(
                    destinationFile, exporter, range,
                )) {
                    is ExportResult.Success -> ExportDocumentResult.Success(
                        fileNameWithExtension = fileNameWithExtension,
                        baseFileName = finalFileName,
                        startupGeneration = generation,
                    )
                    is ExportResult.Error -> {
                        val removed = removePartialDocument(destinationFile)
                        if (removed) ExportDocumentResult.Failure(exportResult)
                        else unfinishedFileFailure(destinationFile)
                    }
                }
            }
        } catch (exception: CancellationException) {
            createdFile?.let { file ->
                if (!removePartialDocument(file)) {
                    exception.addSuppressed(IllegalStateException("Unfinished export remains at ${file.uri}"))
                }
            }
            throw exception
        } catch (_: Exception) {
            val file = createdFile
            if (file != null && !removePartialDocument(file)) unfinishedFileFailure(file)
            else ExportDocumentResult.Failure(ExportResult.Error())
        }
    }

    private suspend fun removePartialDocument(file: DocumentFile): Boolean =
        withContext(NonCancellable + dispatchers.io) {
            runCatching { file.delete() }.getOrDefault(false)
        }

    private fun unfinishedFileFailure(file: DocumentFile) = ExportDocumentResult.Failure(
        ExportResult.Error(LocalizedString(R.string.export_error_partial_file_remains, file.uri)),
    )

    internal fun beginExport() {
        _uiState.update { it.copy(exportCompletionState = ExportCompletionState.Exporting) }
    }

    internal fun completeExport(result: ExportDocumentResult) {
        _uiState.update {
            it.copy(
                exportCompletionState = when (result) {
                    is ExportDocumentResult.Success -> ExportCompletionState.Succeeded
                    is ExportDocumentResult.Failure -> ExportCompletionState.Failed
                }
            )
        }
    }

    internal suspend fun resolveShareTripSummary(
        fallbackFileName: String,
        range: ClosedRange<ZonedDateTime>?,
    ): ShareTripSummary = withContext(dispatchers.io) {
        val fallbackDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(
            (range?.start ?: Time.now).toLocalDate()
        )
        val fallbackName = fallbackFileName.replace('_', ' ').trim().ifEmpty { "Trip" }
        val fallback = ShareTripSummary(
            tripName = fallbackName,
            formattedDate = fallbackDate,
            activityEmoji = "📍",
            formattedDistance = null,
            formattedDuration = null,
            formattedSteps = null
        )

        val targetRange = range ?: return@withContext fallback
        val fromMs = targetRange.start.toEpochMillis()
        val toMs = targetRange.endInclusive.toEpochMillis()

        val trip = dataRepository.loadTripShareSnapshot(fromMs, toMs)
            ?: return@withContext fallback

        val tripName = trip.activityName?.takeIf { it.isNotBlank() } ?: fallbackName
        val emoji = mapActivityToEmoji(trip.activityName, trip.activityId)
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(
            Instant.ofEpochMilli(trip.startTimeMs).atZone(ZoneId.systemDefault()).toLocalDate()
        )
        val lengthSystem = TrackerSettingsQuick.lengthSystem(appContext)
        val distance = appContext.resources.formatDistance(
            trip.totalDistanceM,
            digits = if (trip.totalDistanceM >= 1000f) 1 else 2,
            unit = lengthSystem
        )

        ShareTripSummary(
            tripName = tripName,
            formattedDate = date,
            activityEmoji = emoji,
            formattedDistance = distance,
            formattedDuration = trip.totalDurationMs.formatAsDuration(appContext),
            // Trip.steps is a legacy aggregate without exact capture/source authority.
            formattedSteps = null
        )
    }

    internal fun handOffExport(result: ExportDocumentResult.Success, handoff: () -> Unit): Boolean =
        startupGate.withRetainedDataGeneration(result.startupGeneration) { handoff(); true } == true

    private suspend fun exportToFile(
        file: DocumentFile,
        exporter: Exporter,
        range: ClosedRange<ZonedDateTime>?,
    ): ExportResult {
        val stream = file.openOutputStream(appContext, append = false)
        return if (stream != null) {
            stream.use {
                exportToStream(it, exporter, range)
            }
        } else {
            ExportResult.Error(
                LocalizedString(
                    R.string.export_error_stream_failed,
                    file.uri
                )
            )
        }
    }

    internal suspend fun exportToStream(
        outputStream: java.io.OutputStream,
        exporter: Exporter,
        range: ClosedRange<ZonedDateTime>?,
    ): ExportResult = withContext(dispatchers.io) {
        if (exporter.canSelectDateRange && range != null) {
            val (fromMs, toMs) = if (exporter.selectsCalendarDates) {
                val dates = SourceExportDates.of(
                    range.start.toLocalDate(),
                    range.endInclusive.toLocalDate(),
                    range.start.zone,
                )
                dates.fromInclusiveMs to (dates.toExclusiveMs - 1L)
            } else {
                range.start.toEpochMillis() to range.endInclusive.toEpochMillis()
            }

            val locationSequence = if (exporter.requiresLocationData) {
                val totalCount = dataRepository.countLocationSamples(fromMs, toMs)
                if (totalCount == 0) {
                    return@withContext ExportResult.Error(
                        LocalizedString(R.string.export_error_no_locations_in_interval)
                    )
                }
                dataRepository.pagedLocationSamples(
                    fromMs = fromMs,
                    toMs = toMs,
                    pageSize = EXPORT_PAGE_SIZE,
                ).filter { it.latE7 != null && it.lonE7 != null }
            } else {
                emptySequence()
            }

            exporter.export(appContext, locationSequence, outputStream, fromMs..toMs)
        } else {
            exporter.export(appContext, emptySequence(), outputStream)
        }
    }

    companion object {
        private const val EXPORT_PAGE_SIZE = 5000
    }
}
