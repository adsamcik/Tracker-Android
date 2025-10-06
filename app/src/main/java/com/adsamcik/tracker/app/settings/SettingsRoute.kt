package com.adsamcik.tracker.app.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adsamcik.tracker.R
import com.adsamcik.tracker.activity.ui.SessionActivityActivityCompose
import com.adsamcik.tracker.app.settings.components.*
import com.adsamcik.tracker.shared.base.di.LocalViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Contract: Entry route for settings; manages hierarchical navigation & hosts category screens
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute() {
    val factory = LocalViewModelFactory.current
    val vm: SettingsViewModel = viewModel(factory = factory)
    var currentScreen by remember { mutableStateOf<SettingsScreen>(SettingsScreen.Root) }

    Scaffold(
        topBar = {
            if (currentScreen != SettingsScreen.Root) {
                TopAppBar(
                    title = { Text(currentScreen.title()) },
                    navigationIcon = {
                        IconButton(onClick = { currentScreen = SettingsScreen.Root }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            } else {
                TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val screen = currentScreen) {
                SettingsScreen.Root -> RootSettings(
                    viewModel = vm,
                    onNavigate = { currentScreen = it }
                )
                SettingsScreen.Tracking -> TrackingSettings()
                SettingsScreen.Data -> DataSettings()
                SettingsScreen.Export -> ExportSettings()
                SettingsScreen.Map -> MapSettings()
                SettingsScreen.Game -> GameSettings()
                SettingsScreen.Statistics -> StatisticsSettings()
                SettingsScreen.Debug -> DebugSettings()
            }
        }
    }
}

// Sealed hierarchy for settings navigation
sealed class SettingsScreen {
    @Composable
    abstract fun title(): String

    data object Root : SettingsScreen() {
        @Composable override fun title() = stringResource(R.string.settings_title)
    }
    data object Tracking : SettingsScreen() {
        @Composable override fun title() = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_title)
    }
    data object Data : SettingsScreen() {
        @Composable override fun title() = stringResource(R.string.settings_data_title)
    }
    data object Export : SettingsScreen() {
        @Composable override fun title() = stringResource(com.adsamcik.tracker.impexp.R.string.settings_export_title)
    }
    data object Map : SettingsScreen() {
        @Composable override fun title() = stringResource(R.string.module_map_title)
    }
    data object Game : SettingsScreen() {
        @Composable override fun title() = stringResource(R.string.module_game_title)
    }
    data object Statistics : SettingsScreen() {
        @Composable override fun title() = stringResource(R.string.module_statistics_title)
    }
    data object Debug : SettingsScreen() {
        @Composable override fun title() = stringResource(R.string.settings_debug_title)
    }
}

// Root settings list
@Composable
private fun RootSettings(viewModel: SettingsViewModel, onNavigate: (SettingsScreen) -> Unit) {
    val context = LocalContext.current
    val state by viewModel.settings.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // Tracking settings
        item {
            SettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_title),
                icon = Icons.Default.GpsFixed,
                onClick = { onNavigate(SettingsScreen.Tracking) }
            )
        }

        // Data settings
        item {
            SettingsItem(
                title = stringResource(R.string.settings_data_title),
                icon = Icons.Default.Folder,
                onClick = { onNavigate(SettingsScreen.Data) }
            )
        }

        // Activity settings
        item {
            SettingsItem(
                title = stringResource(com.adsamcik.tracker.activity.R.string.settings_activity_title),
                icon = Icons.Default.DirectionsRun,
                onClick = {
                    context.startActivity(Intent(context, SessionActivityActivityCompose::class.java))
                }
            )
        }

        // General settings section
        item {
            SectionHeader(stringResource(R.string.settings_other_title))
        }

        // Length system
        item {
            val lengthNames = stringArrayResource(R.array.settings_length_system_names).toList()
            val lengthValues = stringArrayResource(R.array.settings_length_system_values).toList()
            DialogListPreference(
                title = stringResource(R.string.settings_length_system_title),
                currentValue = state.lengthSystem.name,
                entries = lengthNames,
                entryValues = lengthValues,
                onValueChange = { selectedIndex ->
                    viewModel.setLengthSystem(lengthValues[selectedIndex])
                }
            )
        }

        // Auto unit switch
        item {
            SwitchSettingsItem(
                title = "Automatic unit switching",
                subtitle = if (state.autoUnitSwitch) "Will adapt length system to activity" else "Uses default length system only",
                checked = state.autoUnitSwitch,
                onCheckedChange = { viewModel.setAutoUnitSwitch(it) }
            )
        }

        // Speed format
        item {
            val speedNames = stringArrayResource(R.array.settings_speed_format_names).toList()
            val speedValues = stringArrayResource(R.array.settings_speed_format_values).toList()
            DialogListPreference(
                title = stringResource(R.string.settings_speed_format_title),
                currentValue = state.speedFormat.name,
                entries = speedNames,
                entryValues = speedValues,
                onValueChange = { selectedIndex ->
                    viewModel.setSpeedFormat(speedValues[selectedIndex])
                }
            )
        }

        // Language
        item {
            SettingsItem(
                title = stringResource(R.string.settings_language_title),
                subtitle = stringResource(R.string.settings_language_summary, "English"), // TODO: Get actual language
                icon = Icons.Default.Translate,
                onClick = { /* TODO: Language picker */ }
            )
        }

        // Module settings section
        item {
            SectionHeader(stringResource(R.string.settings_module_group_title))
        }

        item {
            SettingsItem(
                title = stringResource(R.string.module_map_title),
                icon = Icons.Default.Map,
                onClick = { onNavigate(SettingsScreen.Map) }
            )
        }

        item {
            SettingsItem(
                title = stringResource(R.string.module_game_title),
                icon = Icons.Default.EmojiEvents,
                onClick = { onNavigate(SettingsScreen.Game) }
            )
        }

        item {
            SettingsItem(
                title = stringResource(R.string.module_statistics_title),
                icon = Icons.Default.BarChart,
                onClick = { onNavigate(SettingsScreen.Statistics) }
            )
        }

        // Licenses
        item {
            SettingsItem(
                title = stringResource(R.string.settings_licenses_title),
                icon = Icons.Default.Article,
                onClick = {
                    context.startActivity(Intent().setClassName(context, "com.adsamcik.tracker.license.LicenseActivity"))
                }
            )
        }

        // Debug
        item {
            SettingsItem(
                title = stringResource(R.string.settings_debug_title),
                icon = Icons.Default.BugReport,
                onClick = { onNavigate(SettingsScreen.Debug) }
            )
        }
    }
}

// Tracking Settings Screen
@Composable
private fun TrackingSettings() {
    val context = LocalContext.current
    val trackingVm: TrackingSettingsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TrackingSettingsViewModel(context) as T
            }
        }
    )
    
    val locationEnabled by trackingVm.locationEnabled.collectAsState()
    val activityEnabled by trackingVm.activityEnabled.collectAsState()
    val stepsEnabled by trackingVm.stepsEnabled.collectAsState()
    val wifiEnabled by trackingVm.wifiEnabled.collectAsState()
    val cellEnabled by trackingVm.cellEnabled.collectAsState()
    val wifiNetworkEnabled by trackingVm.wifiNetworkEnabled.collectAsState()
    val wifiLocationCountEnabled by trackingVm.wifiLocationCountEnabled.collectAsState()
    val transitionDetection by trackingVm.transitionDetectionEnabled.collectAsState()
    val notificationStyled by trackingVm.notificationStyled.collectAsState()
    val minDistance by trackingVm.minDistance.collectAsState()
    val minTime by trackingVm.minTime.collectAsState()
    val requiredAccuracy by trackingVm.requiredAccuracy.collectAsState()
    val hasValidSources by trackingVm.hasValidSources.collectAsState()
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // Tracking notice
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_notice_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        
        // Validation warning
        if (!hasValidSources) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            stringResource(com.adsamcik.tracker.tracker.R.string.error_nothing_to_track),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
        
        // Auto-tracking section
        item {
            SectionHeader(stringResource(com.adsamcik.tracker.tracker.R.string.settings_auto_tracking_category))
        }
        
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_auto_tracking_transition_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_auto_tracking_transition_summary),
                checked = transitionDetection,
                onCheckedChange = { trackingVm.setTransitionDetectionEnabled(it) }
            )
        }
        
        // Notification section
        item {
            SectionHeader(stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_category))
        }
        
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_styled_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_styled_summary),
                checked = notificationStyled,
                onCheckedChange = { trackingVm.setNotificationStyled(it) }
            )
        }
        
        item {
            SettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_customize_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_customize_summary),
                icon = Icons.Default.Notifications,
                onClick = {
                    context.startActivity(Intent(context, com.adsamcik.tracker.tracker.notification.NotificationManagementActivity::class.java))
                }
            )
        }
        
        // Tracking parameters section
        item {
            SectionHeader("Tracking Parameters")
        }
        
        item {
            SliderSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_min_distance_title),
                value = minDistance.toFloat(),
                valueRange = 0f..200f,
                steps = 19, // 20 possible values
                valueLabel = { "${it.toInt()} m" },
                onValueChange = { trackingVm.setMinDistance(it.toInt()) }
            )
        }
        
        item {
            SliderSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_min_time_title),
                value = minTime.toFloat(),
                valueRange = 0f..60f,
                steps = 11,
                valueLabel = { "${it.toInt()} s" },
                onValueChange = { trackingVm.setMinTime(it.toInt()) }
            )
        }
        
        item {
            SliderSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_required_accuracy_title),
                value = requiredAccuracy.toFloat(),
                valueRange = 10f..200f,
                steps = 18,
                valueLabel = { "${it.toInt()} m" },
                onValueChange = { trackingVm.setRequiredAccuracy(it.toInt()) }
            )
        }
        
        // Enable/disable sources section
        item {
            SectionHeader(stringResource(com.adsamcik.tracker.tracker.R.string.settings_enable_category_title))
        }
        
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_location_enabled_title),
                checked = locationEnabled,
                onCheckedChange = { trackingVm.setLocationEnabled(it) }
            )
        }
        
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_activity_enabled_title),
                checked = activityEnabled,
                onCheckedChange = { trackingVm.setActivityEnabled(it) }
            )
        }
        
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_steps_enabled_title),
                checked = stepsEnabled,
                onCheckedChange = { trackingVm.setStepsEnabled(it) }
            )
        }
        
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_enabled_title),
                checked = wifiEnabled,
                onCheckedChange = { trackingVm.setWifiEnabled(it) }
            )
        }
        
        // WiFi sub-options
        if (wifiEnabled) {
            item {
                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_network_enabled_title),
                    checked = wifiNetworkEnabled,
                    onCheckedChange = { trackingVm.setWifiNetworkEnabled(it) }
                )
            }
            
            item {
                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_location_count_enabled_title),
                    checked = wifiLocationCountEnabled,
                    onCheckedChange = { trackingVm.setWifiLocationCountEnabled(it) }
                )
            }
        }
        
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_cell_enabled_title),
                checked = cellEnabled,
                onCheckedChange = { trackingVm.setCellEnabled(it) }
            )
        }
    }
}

// Data & Export Settings Screen
@Composable
private fun DataSettings() {
    val context = LocalContext.current
    val dataVm: DataSettingsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DataSettingsViewModel(context) as T
            }
        }
    )
    val debugVm: DebugSettingsViewModel = viewModel()
    
    val autoCleanupEnabled by dataVm.autoCleanupEnabled.collectAsState()
    val dataRetentionYears by dataVm.dataRetentionYears.collectAsState()
    val showDeleteDataDialog by debugVm.showDeleteDataDialog.collectAsState()
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // Export section
        item {
            SectionHeader(stringResource(com.adsamcik.tracker.impexp.R.string.settings_export_title))
        }
        
        item {
            SettingsItem(
                title = stringResource(com.adsamcik.tracker.impexp.R.string.settings_export_gpx_title),
                subtitle = stringResource(com.adsamcik.tracker.impexp.R.string.settings_export_gpx_summary),
                icon = Icons.Default.Route,
                onClick = {
                    context.startActivity(Intent(context, com.adsamcik.tracker.impexp.exporter.activity.ImportExportComposeActivity::class.java).apply {
                        putExtra("EXPORTER_KEY", com.adsamcik.tracker.impexp.exporter.GpxExporter::class.java)
                    })
                }
            )
        }
        
        item {
            SettingsItem(
                title = stringResource(com.adsamcik.tracker.impexp.R.string.settings_export_kml_title),
                subtitle = stringResource(com.adsamcik.tracker.impexp.R.string.settings_export_kml_summary),
                icon = Icons.Default.Map,
                onClick = {
                    context.startActivity(Intent(context, com.adsamcik.tracker.impexp.exporter.activity.ImportExportComposeActivity::class.java).apply {
                        putExtra("EXPORTER_KEY", com.adsamcik.tracker.impexp.exporter.KmlExporter::class.java)
                    })
                }
            )
        }
        
        item {
            SettingsItem(
                title = stringResource(com.adsamcik.tracker.impexp.R.string.settings_export_sqlite_title),
                subtitle = stringResource(com.adsamcik.tracker.impexp.R.string.settings_export_sqlite_summary),
                icon = Icons.Default.Storage,
                onClick = {
                    context.startActivity(Intent(context, com.adsamcik.tracker.impexp.exporter.activity.ImportExportComposeActivity::class.java).apply {
                        putExtra("EXPORTER_KEY", com.adsamcik.tracker.impexp.exporter.DatabaseExporter::class.java)
                    })
                }
            )
        }
        
        // Import section
        item {
            // File picker launcher for import
            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    com.adsamcik.tracker.impexp.importer.DataImporter.import(context, uri)
                }
            }
            
            SettingsItem(
                title = stringResource(com.adsamcik.tracker.impexp.R.string.settings_import_title),
                subtitle = stringResource(com.adsamcik.tracker.impexp.R.string.settings_import_summary, "GPX, KML, ZIP", "ZIP"),
                icon = Icons.Default.FileDownload,
                onClick = {
                    // Launch file picker with supported MIME types
                    importLauncher.launch(arrayOf(
                        "application/gpx+xml",
                        "application/vnd.google-earth.kml+xml",
                        "application/zip",
                        "*/*" // Fallback for all files
                    ))
                }
            )
        }
        
        // Auto-cleanup section
        item {
            SectionHeader("Data Management")
        }
        
        item {
            SwitchSettingsItem(
                title = stringResource(R.string.settings_auto_cleanup_old_data_title),
                subtitle = stringResource(R.string.settings_auto_cleanup_old_data_summary),
                checked = autoCleanupEnabled,
                onCheckedChange = { dataVm.setAutoCleanupEnabled(it) }
            )
        }
        
        item {
            val retentionTitles = stringArrayResource(R.array.settings_data_retention_years_titles).toList()
            val retentionValues = stringArrayResource(R.array.settings_data_retention_years_values).toList()
            DialogListPreference(
                title = stringResource(R.string.settings_data_retention_years_title),
                currentValue = dataRetentionYears,
                entries = retentionTitles,
                entryValues = retentionValues,
                onValueChange = { selectedIndex ->
                    dataVm.setDataRetentionYears(retentionValues[selectedIndex])
                }
            )
        }
        
        // Danger zone
        item {
            SectionHeader("Danger Zone")
        }
        
        item {
            SettingsItem(
                title = stringResource(R.string.settings_remove_all_collected_data_title),
                subtitle = stringResource(R.string.settings_remove_all_collected_data_summary),
                icon = Icons.Default.DeleteForever,
                onClick = {
                    debugVm.showDeleteDataDialog()
                }
            )
        }
    }
    
    // Delete data confirmation dialog
    if (showDeleteDataDialog) {
        AlertDialog(
            onDismissRequest = { debugVm.hideDeleteDataDialog() },
            title = { Text(stringResource(R.string.settings_remove_all_collected_data_title)) },
            text = { Text(stringResource(com.adsamcik.tracker.shared.base.R.string.alert_confirm, stringResource(R.string.settings_remove_all_collected_data_title))) },
            confirmButton = {
                Button(
                    onClick = {
                        debugVm.hideDeleteDataDialog()
                        // Delete all data
                        CoroutineScope(Dispatchers.IO).launch {
                            com.adsamcik.tracker.shared.base.database.AppDatabase.deleteAllCollectedData(context)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { debugVm.hideDeleteDataDialog() }) {
                    Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_cancel))
                }
            }
        )
    }
}

// Placeholder screens
@Composable
private fun ExportSettings() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item {
            Text(
                "Export settings coming in next phase",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun DebugSettings() {
    val context = LocalContext.current
    val debugVm: DebugSettingsViewModel = viewModel()
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // Version info
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Version Information",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Version: ${com.adsamcik.tracker.BuildConfig.VERSION_NAME} (${com.adsamcik.tracker.BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // Debug tools
        item {
            SectionHeader("Debug Tools")
        }
        
        item {
            SettingsItem(
                title = "Crash Manager",
                subtitle = "View and manage crash reports",
                icon = Icons.Default.BugReport,
                onClick = {
                    context.startActivity(Intent(context, com.adsamcik.tracker.app.activity.debug.CrashManagerActivity::class.java))
                }
            )
        }
        
        item {
            SettingsItem(
                title = "Log Viewer",
                subtitle = "View application logs",
                icon = Icons.Default.Description,
                onClick = {
                    context.startActivity(Intent(context, com.adsamcik.tracker.app.activity.debug.LogViewerActivity::class.java))
                }
            )
        }
        
        // Developer tools (only show in debug builds)
        if (com.adsamcik.tracker.BuildConfig.DEBUG) {
            item {
                SectionHeader("Developer Tools")
            }
            
            item {
                SettingsItem(
                    title = "Generate Dummy Data",
                    subtitle = "Create test tracking data (DEBUG only)",
                    icon = Icons.Default.Science,
                    onClick = {
                        debugVm.showDummyDataDialog()
                    }
                )
            }
        }
    }
}

// Map Settings Screen
@Composable
private fun MapSettings() {
    val context = LocalContext.current
    val prefs = remember { com.adsamcik.tracker.shared.preferences.Preferences.getPref(context) }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // Map quality slider
        item {
            val qualityValues = context.resources.getStringArray(com.adsamcik.tracker.map.R.array.settings_map_quality_values).map { it.toFloat() }
            val qualityKey = context.getString(com.adsamcik.tracker.map.R.string.settings_map_quality_key)
            val qualityDefault = context.getString(com.adsamcik.tracker.map.R.string.settings_map_quality_default).toFloat()
            var quality by remember { mutableFloatStateOf(prefs.getFloat(qualityKey, qualityDefault)) }
            
            SliderSettingsItem(
                title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_quality_title),
                value = quality,
                valueRange = qualityValues.first()..qualityValues.last(),
                steps = qualityValues.size - 2,
                valueLabel = { "%.1fx".format(it) },
                onValueChange = {
                    quality = it
                    prefs.edit { setFloat(qualityKey, it) }
                }
            )
        }
        
        // Max heat points slider
        item {
            val heatValues = context.resources.getIntArray(com.adsamcik.tracker.map.R.array.settings_map_max_heat_values)
            val heatKey = context.getString(com.adsamcik.tracker.map.R.string.settings_map_max_heat_key)
            val heatDefault = context.getString(com.adsamcik.tracker.map.R.string.settings_map_max_heat_default).toInt()
            var maxHeat by remember { mutableIntStateOf(prefs.getInt(heatKey, heatDefault)) }
            
            SliderSettingsItem(
                title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_max_heat_title),
                value = maxHeat.toFloat(),
                valueRange = heatValues.first().toFloat()..heatValues.last().toFloat(),
                steps = heatValues.size - 2,
                valueLabel = { "%d".format(it.toInt()) },
                onValueChange = {
                    maxHeat = it.toInt()
                    prefs.edit { setInt(heatKey, it.toInt()) }
                }
            )
        }
        
        // Visit threshold slider (duration in minutes)
        item {
            val visitValues = context.resources.getIntArray(com.adsamcik.tracker.map.R.array.settings_map_visit_threshold_values)
            val visitKey = context.getString(com.adsamcik.tracker.map.R.string.settings_map_visit_threshold_key)
            val visitDefault = context.getString(com.adsamcik.tracker.map.R.string.settings_map_visit_threshold_default).toInt()
            var visitThreshold by remember { mutableIntStateOf(prefs.getInt(visitKey, visitDefault)) }
            
            SliderSettingsItem(
                title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_visit_threshold_title),
                value = visitThreshold.toFloat(),
                valueRange = visitValues.first().toFloat()..visitValues.last().toFloat(),
                steps = visitValues.size - 2,
                valueLabel = { 
                    val minutes = it.toInt() / 60
                    if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}min"
                },
                onValueChange = {
                    visitThreshold = it.toInt()
                    prefs.edit { setInt(visitKey, it.toInt()) }
                }
            )
        }
    }
}

// Game Settings Screen
@Composable
private fun GameSettings() {
    val context = LocalContext.current
    val prefs = remember { com.adsamcik.tracker.shared.preferences.Preferences.getPref(context) }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // Challenges section
        item {
            SectionHeader(stringResource(com.adsamcik.tracker.game.R.string.settings_game_challenge_category_title))
        }
        
        item {
            val challengeKey = context.getString(com.adsamcik.tracker.game.R.string.settings_game_challenge_enable_key)
            val challengeDefault = context.getString(com.adsamcik.tracker.game.R.string.settings_game_challenge_enable_default).toBoolean()
            var challengeEnabled by remember { mutableStateOf(prefs.getBoolean(challengeKey, challengeDefault)) }
            
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.game.R.string.settings_game_challenge_enable_title),
                checked = challengeEnabled,
                onCheckedChange = {
                    challengeEnabled = it
                    prefs.edit { setBoolean(challengeKey, it) }
                }
            )
        }
        
        // Goals section (just notification for now)
        item {
            SectionHeader(stringResource(com.adsamcik.tracker.game.R.string.settings_game_goals_category_title))
        }
        
        item {
            Text(
                text = "Goal settings like daily/weekly steps are managed through the Goals feature",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

// Statistics Settings Screen
@Composable
private fun StatisticsSettings() {
    val context = LocalContext.current
    val prefs = remember { com.adsamcik.tracker.shared.preferences.Preferences.getPref(context) }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item {
            val autoUnitKey = context.getString(com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_key)
            val autoUnitDefault = context.getString(com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_default).toBoolean()
            var autoUnitSwitch by remember { mutableStateOf(prefs.getBoolean(autoUnitKey, autoUnitDefault)) }
            
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_title),
                subtitle = stringResource(com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_summary),
                checked = autoUnitSwitch,
                onCheckedChange = {
                    autoUnitSwitch = it
                    prefs.edit { setBoolean(autoUnitKey, it) }
                }
            )
        }
    }
}
