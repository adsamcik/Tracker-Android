package com.adsamcik.tracker.impexp.exporter.activity

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.impexp.exporter.pagedLocationSequence
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.base.extension.openOutputStream
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ImportExportUiState(
    val showNoDataDialog: Boolean = false,
)

internal sealed interface ExportDocumentResult {
    data class Success(
        val fileNameWithExtension: String,
        val baseFileName: String,
    ) : ExportDocumentResult

    data class Failure(val error: ExportResult.Error) : ExportDocumentResult
}

@HiltViewModel
class ImportExportViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val appDatabase: AppDatabase,
    private val dispatchers: DispatchersProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportExportUiState())
    internal val uiState: StateFlow<ImportExportUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val hasTrips = withContext(dispatchers.io) {
                appDatabase.tripDao().countAllTrips() > 0L
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
        onResult: (ExportDocumentResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                val actualFileName = getExportFileName(fileName, exporter, range, appContext)
                val finalFileName = if (forceOverride) {
                    actualFileName
                } else {
                    findAvailableFileName(directory, actualFileName, exporter.extension)
                }
                val fileNameWithExtension = "${finalFileName}.${exporter.extension}"
                val trimmedName = preventDoubleExtension(fileNameWithExtension, exporter)
                val createdFile = directory.createFile(exporter.mimeType, trimmedName)
                    ?: return@withContext ExportDocumentResult.Failure(
                        ExportResult.Error(
                            LocalizedString(R.string.export_error_stream_failed, fileNameWithExtension)
                        )
                    )

                when (val exportResult = exportToFile(createdFile, exporter, range)) {
                    is ExportResult.Success -> ExportDocumentResult.Success(
                        fileNameWithExtension = fileNameWithExtension,
                        baseFileName = finalFileName,
                    )
                    is ExportResult.Error -> ExportDocumentResult.Failure(exportResult)
                }
            }
            onResult(result)
        }
    }

    internal fun resolveShareTripSummary(
        fallbackFileName: String,
        range: ClosedRange<ZonedDateTime>?,
        onResult: (ShareTripSummary) -> Unit,
    ) {
        viewModelScope.launch {
            val summary = withContext(dispatchers.io) {
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

                val tripDao = appDatabase.tripDao()
                if (tripDao.countTripsBetween(fromMs, toMs) == 0L) return@withContext fallback

                val trips = tripDao.getBetween(fromMs, toMs)
                val primaryTrip = trips.firstOrNull() ?: return@withContext fallback

                val totalDistance = trips.sumOf { it.distanceM.toDouble() }.toFloat()
                val totalDuration = trips.sumOf { it.durationMs }
                val totalSteps = trips.sumOf { it.steps ?: 0 }

                val activity = primaryTrip.primaryActivity?.toLong()?.let { id ->
                    appDatabase.activityDao().getLocalized(appContext, id)
                }

                val tripName = activity?.name?.takeIf { it.isNotBlank() } ?: fallbackName
                val emoji = mapActivityToEmoji(activity?.name, activity?.id)
                val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(
                    Instant.ofEpochMilli(primaryTrip.startTimeMs).atZone(ZoneId.systemDefault()).toLocalDate()
                )
                val lengthSystem = TrackerSettingsQuick.lengthSystem(appContext)
                val distance = appContext.resources.formatDistance(
                    totalDistance,
                    digits = if (totalDistance >= 1000f) 1 else 2,
                    unit = lengthSystem
                )

                ShareTripSummary(
                    tripName = tripName,
                    formattedDate = date,
                    activityEmoji = emoji,
                    formattedDistance = distance,
                    formattedDuration = totalDuration.formatAsDuration(appContext),
                    formattedSteps = totalSteps.formatReadable()
                )
            }
            onResult(summary)
        }
    }

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

    private suspend fun exportToStream(
        outputStream: java.io.OutputStream,
        exporter: Exporter,
        range: ClosedRange<ZonedDateTime>?,
    ): ExportResult = withContext(dispatchers.io) {
        if (exporter.canSelectDateRange && range != null) {
            val locationSampleDao = appDatabase.locationSampleDao()
            val fromMs = range.start.toEpochMillis()
            val toMs = range.endInclusive.toEpochMillis()

            val totalCount = locationSampleDao.countBetween(fromMs, toMs)
            if (totalCount == 0) {
                return@withContext ExportResult.Error(
                    LocalizedString(R.string.export_error_no_locations_in_interval)
                )
            }

            val locationSequence = pagedLocationSequence(
                locationSampleDao = locationSampleDao,
                fromMs = fromMs,
                toMs = toMs,
                pageSize = EXPORT_PAGE_SIZE,
            ).filter { it.latE7 != null && it.lonE7 != null }

            exporter.export(appContext, locationSequence, outputStream, fromMs..toMs)
        } else {
            exporter.export(appContext, emptySequence(), outputStream)
        }
    }

    companion object {
        private const val EXPORT_PAGE_SIZE = 5000
    }
}
