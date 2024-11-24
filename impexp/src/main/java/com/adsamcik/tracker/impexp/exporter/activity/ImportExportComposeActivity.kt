package com.adsamcik.tracker.impexp.exporter.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.extension.openOutputStream
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import com.adsamcik.tracker.shared.base.misc.LocalizedString
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

        val exporterType = intent.extras?.get(EXPORTER_KEY) as? Class<*>
            ?: throw IllegalArgumentException("Exporter class not found in intent extras")
        exporter = exporterType.getConstructor().newInstance() as Exporter

        setContent {
            ExportScreen(
                exporter = exporter,
                shareableDir = shareableDir,
                activity = this,
                filesDir = filesDir
            )
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
    filesDir: File
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
            val sessionDao = AppDatabase.database(activity).sessionDao()
            val availableRange = sessionDao.range().let {
                if (it.start == 0L && it.endInclusive == 0L) {
                    LongRange.EMPTY
                } else {
                    LongRange(it.start, it.endInclusive)
                }
            }
            if (availableRange.isEmpty()) {
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
                    Text(text = stringResource(id = R.string.generic_ok))
                }
            }
        )
    }

    // Implement UI
    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackBarHostState)
        },
        topBar = {
            TopAppBar(title = { Text(stringResource(id = R.string.export_share_button)) })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Filename field with image
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_outline_name),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
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
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (fileNameErrorState.value != null) {
                Text(
                    text = fileNameErrorState.value!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 72.dp)
                )
            }

            // Date range fields with image, if canSelectDateRange
            if (canSelectDateRange) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_date_range_black_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        OutlinedTextField(
                            value = rangeState.value.start.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)),
                            onValueChange = {},
                            label = { Text(stringResource(id = R.string.settings_export_dialog_from)) },
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    showFromDatePicker.value = true
                                }) {
                                    Icon(painterResource(R.drawable.ic_date_range_black_24dp), contentDescription = null)
                                }
                            }
                        )
                        OutlinedTextField(
                            value = rangeState.value.endInclusive.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)),
                            onValueChange = {},
                            label = { Text(stringResource(id = R.string.settings_export_dialog_to)) },
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    showToDatePicker.value = true
                                }) {
                                    Icon(painterResource(R.drawable.ic_date_range_black_24dp), contentDescription = null)
                                }
                            }
                        )
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
                            Text(text = stringResource(id = R.string.generic_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showFromDatePicker.value = false }) {
                            Text(text = stringResource(id = R.string.generic_cancel))
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
                            Text(text = stringResource(id = R.string.generic_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showToDatePicker.value = false }) {
                            Text(text = stringResource(id = R.string.generic_cancel))
                        }
                    }
                ) {
                    DatePicker(state = datePickerState)
                }
            }

            // Buttons
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
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
                        scope.launch {
                            tryExport(
                                directory = DocumentFile.fromFile(filesDir),
                                forceOverride = false,
                                exporter = exporter,
                                fileName = fileNameState.value,
                                range = if (canSelectDateRange) rangeState.value else null,
                                context = activity,
                                snackBarHostState = snackBarHostState,
                                onSuccess = {
                                    // Show success message
                                    scope.launch {
                                        snackBarHostState.showSnackbar(
                                            message = activity.getString(R.string.export_button)
                                        )
                                    }
                                    activity.finish()
                                }
                            )
                        }
                    }
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
                                        "com.adsamcik.tracker.fileprovider",
                                        file
                                    )
                                    val shareIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_STREAM, shareUri)
                                        type = exporter.mimeType
                                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    }
                                    val chooser = Intent.createChooser(shareIntent, activity.getString(R.string.export_share_button))
                                    activity.startActivity(chooser)
                                    activity.finish()
                                }
                            )
                        }
                    }
                ) {
                    Text(text = stringResource(id = R.string.export_share_button))
                }
            }
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

suspend fun tryExport(
    directory: DocumentFile,
    forceOverride: Boolean,
    exporter: Exporter,
    fileName: String,
    range: ClosedRange<ZonedDateTime>?,
    context: Context,
    snackBarHostState: SnackbarHostState,
    onSuccess: () -> Unit
) {
    withContext(Dispatchers.IO) {
        val actualFileName = getExportFileName(fileName, exporter, range, context)
        val fileNameWithExtension = "${actualFileName}.${exporter.extension}"
        val foundFile = directory.findFile(fileNameWithExtension)

        if (!forceOverride && foundFile != null) {
            withContext(Dispatchers.Main) {
                // Handle overwrite confirmation if needed
                snackBarHostState.showSnackbar("File already exists. Overwriting.")
            }
        }

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
            if (result.isSuccess) {
                onSuccess()
            } else {
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
        return ExportResult(
            false,
            LocalizedString(
                R.string.export_error_stream_failed,
                file.uri
            )
        )
    }
}

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
            val locationDao = database.locationDao()
            val from = range.start
            val to = range.endInclusive
            val locations = locationDao.getAllBetween(from.toEpochMillis(), to.toEpochMillis())

            if (locations.isEmpty()) {
                return@withContext ExportResult(
                    false,
                    LocalizedString(R.string.export_error_no_locations_in_interval)
                )
            }
            exporter.export(context, locations, outputStream)
        } else {
            exporter.export(context, listOf(), outputStream)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ExportScreenPreview() {
    // Mock exporter for preview
    val exporter = object : Exporter {
        override val canSelectDateRange: Boolean = true
        override val mimeType: String = "application/zip"
        override val extension: String = "zip"
        override fun export(
            context: Context,
            locationData: List<DatabaseLocation>,
            outputStream: OutputStream
        ): ExportResult {
            return ExportResult(isSuccess = true)
        }
    }
    ExportScreen(
        exporter = exporter,
        shareableDir = File("/tmp"),
        activity = Activity(),
        filesDir = File("/tmp")
    )
}
