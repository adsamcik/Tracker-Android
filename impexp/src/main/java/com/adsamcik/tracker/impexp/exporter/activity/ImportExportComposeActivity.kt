package com.adsamcik.tracker.impexp.exporter.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.WorkerThread
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.base.extension.openOutputStream
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.style.compose.AppColors
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class ImportExportComposeActivity : ComponentActivity() {
    private lateinit var exporter: Exporter
    private lateinit var shareableDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shareableDir = File(filesDir, SHARABLE_DIR_NAME)

        val exporterType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.extras?.getSerializable(EXPORTER_KEY, Class::class.java) as? Class<*>
        } else {
            @Suppress("DEPRECATION")
            intent.extras?.getSerializable(EXPORTER_KEY) as? Class<*>
        } ?: throw IllegalArgumentException("Exporter class not found in intent extras")
        exporter = exporterType.getConstructor().newInstance() as Exporter

        setContent {
            AppTheme {
                ExportScreen(
                    exporter = exporter,
                    shareableDir = shareableDir,
                    activity = this
                )
            }
        }
    }

    companion object {
        private const val SHARABLE_DIR_NAME = "sharable"
        const val EXPORTER_KEY: String = "exporter"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    exporter: Exporter,
    shareableDir: File,
    activity: Activity
) {
    val scope = rememberCoroutineScope()
    val snackBarHostState = remember { SnackbarHostState() }

    // State variables
    val fileNameState = remember { mutableStateOf("") }
    val fileNameErrorState = remember { mutableStateOf<String?>(null) }
    val canSelectDateRange = exporter.canSelectDateRange

    val now = Time.now
    val monthBefore = now.minusMonths(1L)
    val rangeState = remember { mutableStateOf(monthBefore..now) }

    val showNoDataDialog = remember { mutableStateOf(false) }

    // State for date pickers
    val showFromDatePicker = remember { mutableStateOf(false) }
    val showToDatePicker = remember { mutableStateOf(false) }

    // Check for data availability
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val tripCount = AppDatabase.database(activity).tripDao().countAllTrips()
            if (tripCount == 0L) {
                showNoDataDialog.value = true
            }
        }
    }

    if (showNoDataDialog.value) {
        AlertDialog(
            onDismissRequest = { activity.finish() },
            title = { Text(text = stringResource(id = R.string.settings_export_no_data)) },
            confirmButton = {
                TextButton(onClick = { activity.finish() }) {
                    Text(text = stringResource(id = com.adsamcik.tracker.shared.base.R.string.generic_ok))
                }
            }
        )
    }

    // Implement UI
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(hostState = snackBarHostState)
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.export_share_button), color = MaterialTheme.colorScheme.onSurface) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main content in GlassCard
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(8.dp), // Check GlassCard inner padding
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                     // Filename field with image
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = com.adsamcik.tracker.shared.base.R.drawable.ic_outline_name),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        OutlinedTextField(
                            value = fileNameState.value,
                            onValueChange = { newValue ->
                                fileNameState.value = newValue
                                fileNameErrorState.value = if (Regex("[\\\\/:\"*?<>|]+").containsMatchIn(newValue)) {
                                    activity.getString(R.string.export_file_name_error)
                                } else {
                                    null
                                }
                            },
                            label = { Text(stringResource(id = R.string.export_file_name)) },
                            isError = fileNameErrorState.value != null,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                focusedLabelColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                    fileNameErrorState.value?.let { errorText ->
                        Text(
                            text = errorText,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 48.dp)
                        )
                    }

                    // Date range fields with image
                    if (canSelectDateRange) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                painter = painterResource(id = com.adsamcik.tracker.shared.base.R.drawable.ic_date_range_black_24dp),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = rangeState.value.start.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)),
                                    onValueChange = {},
                                    label = { Text(stringResource(id = R.string.settings_export_dialog_from)) },
                                    enabled = false,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                        disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            showFromDatePicker.value = true
                                        }) {
                                            Icon(
                                                painterResource(com.adsamcik.tracker.shared.base.R.drawable.ic_date_range_black_24dp),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                )
                                OutlinedTextField(
                                    value = rangeState.value.endInclusive.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)),
                                    onValueChange = {},
                                    label = { Text(stringResource(id = R.string.settings_export_dialog_to)) },
                                    enabled = false,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                        disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            showToDatePicker.value = true
                                        }) {
                                            Icon(
                                                painterResource(com.adsamcik.tracker.shared.base.R.drawable.ic_date_range_black_24dp),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val exportLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    if (uri == null) {
                        scope.launch {
                            snackBarHostState.showSnackbar(
                                message = activity.getString(R.string.export_error_no_uri)
                            )
                        }
                    } else {
                        val directory = DocumentFile.fromTreeUri(activity, uri)
                        if (directory != null) {
                            scope.launch {
                                tryExport(
                                    directory = directory,
                                    forceOverride = false,
                                    exporter = exporter,
                                    fileName = fileNameState.value,
                                    range = if (canSelectDateRange) rangeState.value else null,
                                    context = activity,
                                    snackBarHostState = snackBarHostState,
                                    onSuccess = {
                                        activity.finish()
                                    }
                                )
                            }
                        } else {
                            scope.launch {
                                snackBarHostState.showSnackbar(
                                    message = activity.getString(R.string.export_error_no_uri)
                                )
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        exportLauncher.launch(null)
                    },
                    modifier = Modifier.weight(1f),
                   border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(text = stringResource(id = R.string.export_button))
                }

                OutlinedButton(
                    onClick = {
                        shareableDir.mkdirs()
                        scope.launch {
                            val directory = DocumentFile.fromFile(shareableDir)
                            tryExport(
                                directory = directory,
                                forceOverride = true,
                                exporter = exporter,
                                fileName = fileNameState.value,
                                range = if (canSelectDateRange) rangeState.value else null,
                                context = activity,
                                snackBarHostState = snackBarHostState,
                                onSuccess = {
                                    val actualFileName = getExportFileName(fileNameState.value, exporter, rangeState.value, activity)
                                    val fileNameWithExtension = "${actualFileName}.${exporter.extension}"
                                    val file = File(shareableDir, fileNameWithExtension)
                                    val shareUri = FileProvider.getUriForFile(
                                        activity,
                                        "${activity.packageName}.fileprovider",
                                        file
                                    )
                                    val shareSummary = withContext(Dispatchers.IO) {
                                        resolveShareTripSummary(
                                            context = activity,
                                            fallbackFileName = actualFileName,
                                            range = if (canSelectDateRange) rangeState.value else null
                                        )
                                    }
                                    val shareIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_STREAM, shareUri)
                                        putExtra(
                                            Intent.EXTRA_SUBJECT,
                                            "Trail: ${shareSummary.tripName} — ${shareSummary.formattedDate}"
                                        )
                                        putExtra(Intent.EXTRA_TEXT, buildShareText(shareSummary))
                                        type = exporter.mimeType
                                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    }
                                    val chooser = Intent.createChooser(shareIntent, activity.getString(R.string.export_share_button))
                                    activity.startActivity(chooser)
                                    activity.finish()
                                }
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(text = stringResource(id = R.string.export_share_button))
                }
            }
        }
    }
    
    // Date pickers
    if (showFromDatePicker.value) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = rangeState.value.start.toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showFromDatePicker.value = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedMillis = datePickerState.selectedDateMillis
                        if (selectedMillis != null) {
                            val selectedDate = Instant.ofEpochMilli(selectedMillis).atZone(ZoneId.systemDefault())
                            rangeState.value = selectedDate..rangeState.value.endInclusive
                        }
                        showFromDatePicker.value = false
                    }
                ) {
                    Text(text = stringResource(id = com.adsamcik.tracker.shared.base.R.string.generic_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFromDatePicker.value = false }) {
                    Text(text = stringResource(id = com.adsamcik.tracker.shared.base.R.string.generic_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showToDatePicker.value) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = rangeState.value.endInclusive.toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showToDatePicker.value = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedMillis = datePickerState.selectedDateMillis
                        if (selectedMillis != null) {
                            val selectedDate = Instant.ofEpochMilli(selectedMillis).atZone(ZoneId.systemDefault())
                            rangeState.value = rangeState.value.start..selectedDate
                        }
                        showToDatePicker.value = false
                    }
                ) {
                    Text(text = stringResource(id = com.adsamcik.tracker.shared.base.R.string.generic_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showToDatePicker.value = false }) {
                    Text(text = stringResource(id = com.adsamcik.tracker.shared.base.R.string.generic_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

private fun preventDoubleExtension(fileNameWithExtension: String, exporter: Exporter): String {
    val extension = MimeTypeMap
        .getSingleton()
        .getExtensionFromMimeType(exporter.mimeType)
        .orEmpty()

    return if (fileNameWithExtension.endsWith(".$extension")) {
        fileNameWithExtension.substring(
            0,
            fileNameWithExtension.length - extension.length - 1
        )
    } else {
        fileNameWithExtension
    }
}

private fun formatForFile(dateTime: ZonedDateTime) =
    dateTime.withNano(0).format(DateTimeFormatter.ofPattern("d-M-y--k-m")).replace(':', '-')

private fun getExportFileName(
    fileName: String,
    exporter: Exporter,
    range: ClosedRange<ZonedDateTime>?,
    context: Context
): String {
    if (fileName.isBlank()) {
        return if (!exporter.canSelectDateRange || range == null) {
            context.getString(
                R.string.export_default_file_name_all,
                formatForFile(Time.now)
            )
        } else {
            context.getString(
                R.string.export_default_file_name_range,
                formatForFile(range.start),
                formatForFile(range.endInclusive)
            )
        }
    } else {
        return fileName.trim()
    }
}

private fun findAvailableFileName(
    directory: DocumentFile,
    baseFileName: String,
    extension: String
): String {
    var counter = 1
    var candidateName = baseFileName
    
    // Check if base name already exists
    while (directory.findFile("${candidateName}.${extension}") != null) {
        candidateName = "${baseFileName}_${counter}"
        counter++
        // Safety limit to prevent infinite loops
        if (counter > 9999) break
    }
    
    return candidateName
}

suspend fun tryExport(
    directory: DocumentFile,
    forceOverride: Boolean,
    exporter: Exporter,
    fileName: String,
    range: ClosedRange<ZonedDateTime>?,
    context: Context,
    snackBarHostState: SnackbarHostState,
    onSuccess: suspend () -> Unit
) {
    withContext(Dispatchers.IO) {
        val actualFileName = getExportFileName(fileName, exporter, range, context)
        
        // Auto-increment filename if file exists (macOS-style: file_1.gpx, file_2.gpx)
        val finalFileName = if (!forceOverride) {
            findAvailableFileName(directory, actualFileName, exporter.extension)
        } else {
            actualFileName
        }
        
        val fileNameWithExtension = "${finalFileName}.${exporter.extension}"
        val trimmedName = preventDoubleExtension(fileNameWithExtension, exporter)
        val createdFile = directory.createFile(exporter.mimeType, trimmedName)
            ?: throw IOException("Could not access or create file $fileNameWithExtension")

        val result = exportStream(
            file = createdFile,
            exporter = exporter,
            range = range,
            context = context
        )

        withContext(Dispatchers.Main) {
            when (result) {
                is ExportResult.Success -> onSuccess()
                is ExportResult.Error -> {
                    val message = result.message?.localize(context)
                    if (message != null) {
                        snackBarHostState.showSnackbar(message)
                    } else {
                        snackBarHostState.showSnackbar("Export failed, but has no message!")
                    }
                }
            }
        }
    }
}

@WorkerThread
suspend fun exportStream(
    file: DocumentFile,
    exporter: Exporter,
    range: ClosedRange<ZonedDateTime>?,
    context: Context
): ExportResult {
    val stream = file.openOutputStream(context, append = false)
    if (stream != null) {
        stream.use {
            return export(it, exporter, range, context)
        }
    } else {
        return ExportResult.Error(
            LocalizedString(
                R.string.export_error_stream_failed,
                file.uri
            )
        )
    }
}

/**
 * Page size for chunked location data export. Each page is loaded from the
 * database independently so that at most [EXPORT_PAGE_SIZE] rows reside in
 * memory at any given time (plus whatever the exporter's writer retains).
 */
private const val EXPORT_PAGE_SIZE = 2000

@WorkerThread
suspend fun export(
    outputStream: OutputStream,
    exporter: Exporter,
    range: ClosedRange<ZonedDateTime>?,
    context: Context
): ExportResult {
    return withContext(Dispatchers.IO) {
        if (exporter.canSelectDateRange && range != null) {
            val database = AppDatabase.database(context)
            val locationSampleDao = database.locationSampleDao()
            val fromMs = range.start.toEpochMillis()
            val toMs = range.endInclusive.toEpochMillis()

            val totalCount = locationSampleDao.countBetween(fromMs, toMs)
            if (totalCount == 0) {
                return@withContext ExportResult.Error(
                    LocalizedString(R.string.export_error_no_locations_in_interval)
                )
            }

            val samples = locationSampleDao.getAllBetween(fromMs, toMs)
            val locationSequence = samples.asSequence()
                .filter { it.latE7 != null && it.lonE7 != null }

            val dateRange = LongRange(fromMs, toMs)
            exporter.export(context, locationSequence, outputStream, dateRange)
        } else {
            exporter.export(context, emptySequence(), outputStream)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ExportScreenPreview() {
    val exporter = object : Exporter {
        override val canSelectDateRange: Boolean = true
        override val mimeType: String = "application/zip"
        override val extension: String = "zip"
        override fun export(
            context: Context,
            locationData: Sequence<LocationSample>,
            outputStream: OutputStream,
            dateRange: LongRange?
        ): ExportResult {
            return ExportResult.Success
        }
    }
    ExportScreen(
        exporter = exporter,
        shareableDir = File("/tmp"),
        activity = Activity()
    )
}

private data class ShareTripSummary(
    val tripName: String,
    val formattedDate: String,
    val activityEmoji: String,
    val formattedDistance: String?,
    val formattedDuration: String?,
    val formattedSteps: String?
)

private fun buildShareText(trip: ShareTripSummary): String {
    val titleLine = "${trip.activityEmoji} ${trip.tripName} — ${trip.formattedDate}"
    val statsLine = if (
        trip.formattedDistance != null &&
        trip.formattedDuration != null &&
        trip.formattedSteps != null
    ) {
        "📏 ${trip.formattedDistance} · ⏱ ${trip.formattedDuration} · 🦶 ${trip.formattedSteps}"
    } else {
        "📏 — · ⏱ — · 🦶 —"
    }
    return "$titleLine\n$statsLine\nExported from Tracker (local-only, no cloud)"
}

private suspend fun resolveShareTripSummary(
    context: Context,
    fallbackFileName: String,
    range: ClosedRange<ZonedDateTime>?
): ShareTripSummary {
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

    val targetRange = range ?: return fallback
    val fromMs = targetRange.start.toEpochMillis()
    val toMs = targetRange.endInclusive.toEpochMillis()

    val database = AppDatabase.database(context)
    val tripDao = database.tripDao()
    if (tripDao.countTripsBetween(fromMs, toMs) == 0L) return fallback

    val trips = tripDao.getBetween(fromMs, toMs)
    val primaryTrip = trips.firstOrNull() ?: return fallback

    val totalDistance = trips.sumOf { it.distanceM.toDouble() }.toFloat()
    val totalDuration = trips.sumOf { it.durationMs }
    val totalSteps = trips.sumOf { it.steps ?: 0 }

    val activity = primaryTrip.primaryActivity?.toLong()?.let { id ->
        database.activityDao().getLocalized(context, id)
    }

    val tripName = activity?.name?.takeIf { it.isNotBlank() } ?: fallbackName
    val emoji = mapActivityToEmoji(activity?.name, activity?.id)
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(
        Instant.ofEpochMilli(primaryTrip.startTimeMs).atZone(ZoneId.systemDefault()).toLocalDate()
    )
    val lengthSystem = TrackerSettingsQuick.lengthSystem(context)
    val distance = context.resources.formatDistance(
        totalDistance,
        digits = if (totalDistance >= 1000f) 1 else 2,
        unit = lengthSystem
    )

    return ShareTripSummary(
        tripName = tripName,
        formattedDate = date,
        activityEmoji = emoji,
        formattedDistance = distance,
        formattedDuration = totalDuration.formatAsDuration(context),
        formattedSteps = totalSteps.formatReadable()
    )
}

private fun mapActivityToEmoji(activityName: String?, activityId: Long?): String {
    return when {
        activityId == NativeSessionActivity.WALKING.id ||
            activityName?.contains("walk", ignoreCase = true) == true -> "🥾"
        activityId == NativeSessionActivity.RUNNING.id ||
            activityName?.contains("run", ignoreCase = true) == true -> "🏃"
        activityId == NativeSessionActivity.BICYCLE.id ||
            activityName?.contains("ride", ignoreCase = true) == true ||
            activityName?.contains("bike", ignoreCase = true) == true ||
            activityName?.contains("cycle", ignoreCase = true) == true -> "🚴"
        activityId == NativeSessionActivity.VEHICLE.id ||
            activityId == NativeSessionActivity.LAND_VEHICLE.id ||
            activityId == NativeSessionActivity.WATER_VEHICLE.id ||
            activityId == NativeSessionActivity.AIR_VEHICLE.id ||
            activityName?.contains("vehicle", ignoreCase = true) == true ||
            activityName?.contains("car", ignoreCase = true) == true -> "🚗"
        activityName?.contains("still", ignoreCase = true) == true -> "⏸️"
        else -> "📍"
    }
}
