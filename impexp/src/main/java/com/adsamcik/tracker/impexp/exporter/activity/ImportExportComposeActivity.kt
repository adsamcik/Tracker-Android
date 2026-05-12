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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@AndroidEntryPoint
class ImportExportComposeActivity : ComponentActivity() {
    private val importExportViewModel: ImportExportViewModel by viewModels()
    private lateinit var exporter: Exporter
    private lateinit var shareableDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val legacyShareableDir = File(filesDir, SHARABLE_DIR_NAME)
        cleanupShareableDirectory(legacyShareableDir, maxAgeMillis = 0L)
        shareableDir = File(cacheDir, SHARABLE_DIR_NAME)
        cleanupShareableDirectory(shareableDir)

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
                    activity = this,
                    viewModel = importExportViewModel,
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
    activity: Activity,
    viewModel: ImportExportViewModel,
) {
    val scope = rememberCoroutineScope()
    val snackBarHostState = remember { SnackbarHostState() }
    val uiState by viewModel.uiState.collectAsState()

    // State variables
    val fileNameState = remember { mutableStateOf("") }
    val fileNameErrorState = remember { mutableStateOf<String?>(null) }
    val canSelectDateRange = exporter.canSelectDateRange

    val now = Time.now
    val monthBefore = now.minusMonths(1L)
    val rangeState = remember { mutableStateOf(monthBefore..now) }

    // State for date pickers
    val showFromDatePicker = remember { mutableStateOf(false) }
    val showToDatePicker = remember { mutableStateOf(false) }
    val pendingSensitiveAction = remember { mutableStateOf<ExportSensitiveAction?>(null) }

    if (uiState.showNoDataDialog) {
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
                            val selectedRange = if (canSelectDateRange) rangeState.value else null
                            viewModel.exportToDocument(
                                directory = directory,
                                forceOverride = false,
                                exporter = exporter,
                                fileName = fileNameState.value,
                                range = selectedRange,
                            ) { result ->
                                when (result) {
                                    is ExportDocumentResult.Success -> {
                                        activity.finish()
                                    }
                                    is ExportDocumentResult.Failure -> {
                                        scope.launch {
                                            showExportError(
                                                snackBarHostState = snackBarHostState,
                                                context = activity,
                                                error = result.error,
                                            )
                                        }
                                    }
                                }
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

                fun startShareExport() {
                    cleanupShareableDirectory(shareableDir)
                    shareableDir.mkdirs()
                    val directory = DocumentFile.fromFile(shareableDir)
                    val selectedRange = if (canSelectDateRange) rangeState.value else null
                    viewModel.exportToDocument(
                        directory = directory,
                        forceOverride = true,
                        exporter = exporter,
                        fileName = fileNameState.value,
                        range = selectedRange,
                    ) { result ->
                        when (result) {
                            is ExportDocumentResult.Success -> {
                                val file = File(shareableDir, result.fileNameWithExtension)
                                val shareUri = FileProvider.getUriForFile(
                                    activity,
                                    "${activity.packageName}.fileprovider",
                                    file
                                )
                                viewModel.resolveShareTripSummary(
                                    fallbackFileName = result.baseFileName,
                                    range = selectedRange
                                ) { shareSummary ->
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
                                    val chooser = Intent.createChooser(
                                        shareIntent,
                                        activity.getString(R.string.export_share_button)
                                    )
                                    activity.startActivity(chooser)
                                    activity.finish()
                                }
                            }
                            is ExportDocumentResult.Failure -> {
                                scope.launch {
                                    showExportError(
                                        snackBarHostState = snackBarHostState,
                                        context = activity,
                                        error = result.error,
                                    )
                                }
                            }
                        }
                    }
                }

                pendingSensitiveAction.value?.let { action ->
                    AlertDialog(
                        onDismissRequest = { pendingSensitiveAction.value = null },
                        title = { Text(stringResource(R.string.export_sensitivity_title)) },
                        text = { Text(stringResource(R.string.export_sensitivity_message)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pendingSensitiveAction.value = null
                                    when (action) {
                                        ExportSensitiveAction.Export -> exportLauncher.launch(null)
                                        ExportSensitiveAction.Share -> startShareExport()
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.export_sensitivity_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingSensitiveAction.value = null }) {
                                Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_cancel))
                            }
                        },
                    )
                }

                OutlinedButton(
                    onClick = {
                        pendingSensitiveAction.value = ExportSensitiveAction.Export
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
                        pendingSensitiveAction.value = ExportSensitiveAction.Share
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

private suspend fun showExportError(
    snackBarHostState: SnackbarHostState,
    context: Context,
    error: ExportResult.Error,
) {
    val message = error.message?.localize(context)
        ?: context.getString(R.string.export_error_unknown)
    snackBarHostState.showSnackbar(message)
}

internal fun preventDoubleExtension(fileNameWithExtension: String, exporter: Exporter): String {
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

internal fun getExportFileName(
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

internal fun findAvailableFileName(
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

private enum class ExportSensitiveAction {
    Export,
    Share,
}

internal data class ShareTripSummary(
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

internal fun mapActivityToEmoji(activityName: String?, activityId: Long?): String {
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

internal fun cleanupShareableDirectory(
    directory: File,
    nowMillis: Long = System.currentTimeMillis(),
    maxAgeMillis: Long = SHAREABLE_MAX_AGE_MS,
) {
    if (!directory.exists()) return
    directory.listFiles()?.forEach { file ->
        if (file.isDirectory) return@forEach
        val ageMillis = nowMillis - file.lastModified()
        if (ageMillis >= maxAgeMillis && !file.delete()) {
            file.deleteOnExit()
        }
    }
}

private const val SHAREABLE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
