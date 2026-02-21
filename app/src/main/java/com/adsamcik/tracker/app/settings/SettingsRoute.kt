package com.adsamcik.tracker.app.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adsamcik.tracker.R
import com.adsamcik.tracker.activity.ui.SessionActivityActivityCompose
import com.adsamcik.tracker.app.settings.components.*
import com.adsamcik.tracker.activity.ski.SkiInfrastructureManager
import com.adsamcik.tracker.map.basemap.BasemapManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

// Contract: Entry route for settings; manages hierarchical navigation & hosts category screens
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(onNavigateBack: () -> Unit = {}, onNavigateToDebug: () -> Unit = {}) {
    val vm: SettingsViewModel = hiltViewModel()
    var currentScreen by remember { mutableStateOf<SettingsScreen>(SettingsScreen.Root) }

    Scaffold(
        topBar = {
            if (currentScreen != SettingsScreen.Root) {
                TopAppBar(
                    title = { Text(currentScreen.title()) },
                    navigationIcon = {
                        IconButton(onClick = { currentScreen = SettingsScreen.Root }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_navigate_back))
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_navigate_back))
                        }
                    }
                )
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
                SettingsScreen.Debug -> DebugSettings(onNavigateToDebug)
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
    
    // Debug menu visibility: reactive via DataStore Flow so toggling developer mode
    // immediately shows/hides the debug entry without recomposition tricks.
    val developerModeEnabled by com.adsamcik.tracker.shared.preferences.DeveloperPreferences
        .observeDeveloperMode(context)
        .collectAsState(initial = com.adsamcik.tracker.shared.preferences.DeveloperPreferences.isDeveloperModeEnabled(context))
    val showDebug = com.adsamcik.tracker.BuildConfig.DEBUG || developerModeEnabled

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
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
                icon = Icons.AutoMirrored.Filled.DirectionsRun,
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
                title = stringResource(R.string.settings_auto_unit_switch_title),
                subtitle = stringResource(if (state.autoUnitSwitch) R.string.settings_units_auto_summary_on else R.string.settings_units_auto_summary_off),
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
                subtitle = stringResource(R.string.settings_language_summary, Locale.getDefault().displayLanguage),
                icon = Icons.Default.Translate,
                onClick = {
                    // Open system language settings
                    val intent = Intent(Settings.ACTION_LOCALE_SETTINGS)
                    context.startActivity(intent)
                }
            )
        }

        // Module settings section
        item {
            SectionHeader(stringResource(R.string.settings_module_group_title))
        }

        item {
            SettingsItem(
                title = stringResource(R.string.module_map_title),
                subtitle = stringResource(R.string.settings_module_map_subtitle),
                icon = Icons.Default.Map,
                onClick = { onNavigate(SettingsScreen.Map) }
            )
        }

        item {
            SettingsItem(
                title = stringResource(R.string.module_game_title),
                subtitle = stringResource(R.string.settings_module_game_subtitle),
                icon = Icons.Default.EmojiEvents,
                onClick = { onNavigate(SettingsScreen.Game) }
            )
        }

        item {
            SettingsItem(
                title = stringResource(R.string.module_statistics_title),
                subtitle = stringResource(R.string.settings_module_statistics_subtitle),
                icon = Icons.Default.BarChart,
                onClick = { onNavigate(SettingsScreen.Statistics) }
            )
        }

        // --- About section ---
        item {
            Text(
                text = stringResource(R.string.settings_about_header),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        item {
            SettingsItem(
                title = stringResource(R.string.settings_about_app_title),
                subtitle = stringResource(R.string.settings_about_app_subtitle),
                icon = Icons.Default.Info,
                onClick = { }
            )
        }

        // Licenses
        item {
            SettingsItem(
                title = stringResource(R.string.settings_licenses_title),
                icon = Icons.AutoMirrored.Filled.Article,
                onClick = {
                    context.startActivity(Intent().setClassName(context, "com.adsamcik.tracker.license.LicenseActivity"))
                }
            )
        }

        // Privacy Policy
        item {
            SettingsItem(
                title = stringResource(R.string.settings_privacy_policy_title),
                icon = Icons.Default.PrivacyTip,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/adsamcik/Tracker-Android/blob/master/PRIVACY_POLICY.md")))
                }
            )
        }

        // Send feedback
        item {
            SettingsItem(
                title = stringResource(R.string.settings_send_feedback_title),
                subtitle = stringResource(R.string.settings_send_feedback_subtitle),
                icon = Icons.Default.Feedback,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/adsamcik/Tracker-Android/issues")))
                }
            )
        }

        // Debug (conditional: always in debug builds, or when developer mode enabled in release)
        if (showDebug) {
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_debug_title),
                    subtitle = if (!com.adsamcik.tracker.BuildConfig.DEBUG) 
                        stringResource(R.string.settings_developer_mode_subtitle) 
                    else null,
                    icon = Icons.Default.BugReport,
                    onClick = { onNavigate(SettingsScreen.Debug) }
                )
            }
        }

        // Version info card (always visible; 7-tap enables developer mode in release builds)
        item {
            var tapCount by remember { mutableIntStateOf(0) }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .run {
                        if (!com.adsamcik.tracker.BuildConfig.DEBUG && !developerModeEnabled) {
                            clickable {
                                tapCount++
                                if (tapCount >= 7) {
                                    com.adsamcik.tracker.shared.preferences.DeveloperPreferences.setDeveloperMode(context, true)
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.settings_developer_mode_enabled_toast),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    tapCount = 0
                                }
                            }
                        } else {
                            this
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        stringResource(R.string.settings_version_info_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.settings_version_format, com.adsamcik.tracker.BuildConfig.VERSION_NAME, com.adsamcik.tracker.BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!com.adsamcik.tracker.BuildConfig.DEBUG && tapCount > 0 && tapCount < 7) {
                        Text(
                            stringResource(R.string.settings_developer_mode_tap_countdown, 7 - tapCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
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
    
    // State from ViewModel
    val currentPreset by trackingVm.currentPreset.collectAsState()
    val currentBatteryImpact by trackingVm.currentBatteryImpact.collectAsState()
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
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
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
        
        // Preset selector with progressive disclosure
        item {
            com.adsamcik.tracker.app.settings.ui.TrackingPolicySelector(
                selectedPreset = currentPreset,
                onPresetSelected = { trackingVm.applyPreset(it) },
                showDetails = false // collapsed by default per Apple philosophy
            )
        }
        
        // Battery warning for high impact
        if (currentBatteryImpact == com.adsamcik.tracker.app.common.ui.BatteryImpact.HIGH) {
            item {
                com.adsamcik.tracker.app.common.ui.BatteryImpactWarning()
            }
        }
        
        // Auto-tracking toggle (essential setting with help)
        item {
            SwitchSettingsItemWithHelp(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_auto_tracking_transition_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_auto_tracking_transition_summary),
                checked = transitionDetection,
                onCheckedChange = { trackingVm.setTransitionDetectionEnabled(it) },
                helpTextRes = com.adsamcik.tracker.tracker.R.string.help_transition_detection
            )
        }
        
        // Notification toggle (essential setting)
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_styled_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_styled_summary),
                checked = notificationStyled,
                onCheckedChange = { trackingVm.setNotificationStyled(it) }
            )
        }
        
        // Notification customization
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
        
        // Advanced settings section (collapsed by default)
        item {
            com.adsamcik.tracker.app.settings.components.ExpandableSection(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_advanced_section_title),
                initiallyExpanded = false
            ) {
                // Tracking parameters with contextual help
                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_min_distance_title),
                    value = minDistance.toFloat(),
                    valueRange = 0f..200f,
                    steps = 19,
                    valueLabel = { "${it.toInt()} m" },
                    onValueChange = { trackingVm.setMinDistance(it.toInt()) },
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_min_distance
                )
                
                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_min_time_title),
                    value = minTime.toFloat(),
                    valueRange = 0f..60f,
                    steps = 11,
                    valueLabel = { "${it.toInt()} s" },
                    onValueChange = { trackingVm.setMinTime(it.toInt()) },
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_min_time
                )
                
                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_required_accuracy_title),
                    value = requiredAccuracy.toFloat(),
                    valueRange = 10f..200f,
                    steps = 18,
                    valueLabel = { "${it.toInt()} m" },
                    onValueChange = { trackingVm.setRequiredAccuracy(it.toInt()) },
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_required_accuracy
                )
                
                // Enable/disable sources
                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_location_enabled_title),
                    checked = locationEnabled,
                    onCheckedChange = { trackingVm.setLocationEnabled(it) }
                )
                
                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_activity_enabled_title),
                    checked = activityEnabled,
                    onCheckedChange = { trackingVm.setActivityEnabled(it) }
                )
                
                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_steps_enabled_title),
                    checked = stepsEnabled,
                    onCheckedChange = { trackingVm.setStepsEnabled(it) }
                )
                
                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_enabled_title),
                    checked = wifiEnabled,
                    onCheckedChange = { trackingVm.setWifiEnabled(it) }
                )
                
                // WiFi sub-options
                if (wifiEnabled) {
                    SwitchSettingsItem(
                        title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_network_enabled_title),
                        checked = wifiNetworkEnabled,
                        onCheckedChange = { trackingVm.setWifiNetworkEnabled(it) }
                    )
                    
                    SwitchSettingsItemWithHelp(
                        title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_location_count_enabled_title),
                        checked = wifiLocationCountEnabled,
                        onCheckedChange = { trackingVm.setWifiLocationCountEnabled(it) },
                        helpTextRes = com.adsamcik.tracker.tracker.R.string.help_wifi_location_count
                    )
                }
                
                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_cell_enabled_title),
                    checked = cellEnabled,
                    onCheckedChange = { trackingVm.setCellEnabled(it) }
                )
            }
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
    
    val coroutineScope = rememberCoroutineScope()
    var showExportFormatDialog by remember { mutableStateOf(false) }
    
    // File picker launcher for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            com.adsamcik.tracker.impexp.importer.DataImporter.import(context, uri)
        }
    }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
    ) {
        // Export section
        item {
            SectionHeader(stringResource(com.adsamcik.tracker.impexp.R.string.settings_export_title))
        }
        
        item {
            SettingsItem(
                title = stringResource(R.string.settings_export_data_title),
                subtitle = stringResource(R.string.settings_export_data_summary),
                icon = Icons.Default.FileUpload,
                onClick = { showExportFormatDialog = true }
            )
        }
        
        // Import section
        item {
            
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
                        // Delete all data using lifecycle-bound scope
                        // Note: deleteAllCollectedData is a static utility method that handles
                        // cross-DAO deletion atomically. Moving to DI would require a
                        // dedicated DataDeletionUseCase injected via hiltViewModel.
                        coroutineScope.launch(Dispatchers.IO) {
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
    
    // Export format dialog (shown outside LazyColumn)
    if (showExportFormatDialog) {
        ExportFormatDialog(
            onDismiss = { showExportFormatDialog = false },
            onFormatSelected = { format ->
                launchExportActivity(context, format)
            }
        )
    }
}

// Placeholder screens
@Composable
private fun ExportSettings() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
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
private fun DebugSettings(onNavigateToDebug: () -> Unit = {}) {
    val context = LocalContext.current
    val debugVm: DebugSettingsViewModel = viewModel()
    val developerModeEnabled by com.adsamcik.tracker.shared.preferences.DeveloperPreferences
        .observeDeveloperMode(context)
        .collectAsState(initial = com.adsamcik.tracker.shared.preferences.DeveloperPreferences.isDeveloperModeEnabled(context))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
    ) {
        // Disable developer mode option (only in release builds when enabled)
        if (!com.adsamcik.tracker.BuildConfig.DEBUG && developerModeEnabled) {
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_developer_mode_disable),
                    subtitle = "Hide developer options from settings",
                    icon = Icons.Default.Close,
                    onClick = {
                        com.adsamcik.tracker.shared.preferences.DeveloperPreferences.setDeveloperMode(context, false)
                        android.widget.Toast.makeText(
                            context,
                            "Developer mode disabled",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                )
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
                    onNavigateToDebug()
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
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
    ) {
        // Info card explaining map settings purpose
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
                        "These settings control map visualization quality and performance. Most users can use default values.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        
        // Basemap import/reset
        item {
            val scope = rememberCoroutineScope()
            val basemapManager = remember { BasemapManager(context) }
            val basemapPathKey = remember {
                context.getString(com.adsamcik.tracker.map.R.string.settings_map_basemap_path_key)
            }
            var basemapPath by remember { mutableStateOf(basemapManager.customBasemapPath()) }

            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    scope.launch(Dispatchers.IO) {
                        val path = basemapManager.importBasemap(uri)
                        prefs.edit { setString(basemapPathKey, path) }
                        basemapPath = path
                    }
                }
            }

            SettingsItem(
                title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_basemap_title),
                subtitle = if (basemapPath != null) {
                    stringResource(com.adsamcik.tracker.map.R.string.settings_map_basemap_custom)
                } else {
                    stringResource(com.adsamcik.tracker.map.R.string.settings_map_basemap_default)
                },
                icon = Icons.Default.Map,
                onClick = {
                    if (basemapPath != null) {
                        basemapManager.clearCustomBasemap()
                        prefs.edit { setString(basemapPathKey, "") }
                        basemapPath = null
                    } else {
                        importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    }
                }
            )
        }

        // Ski infrastructure import/reset
        item {
            val scope = rememberCoroutineScope()
            val infraManager = remember { SkiInfrastructureManager(context) }
            var isLoaded by remember { mutableStateOf(infraManager.isAvailable()) }

            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            infraManager.importDatabase(uri)
                            isLoaded = true
                        } catch (e: Exception) {
                            // Import failed — already cleaned up by manager
                        }
                    }
                }
            }

            val subtitle = if (isLoaded) {
                val liftCount = infraManager.getMetadata("lift_count")
                val generatedAt = infraManager.getMetadata("generated_at")?.take(10)
                if (liftCount != null && generatedAt != null) {
                    stringResource(
                        com.adsamcik.tracker.activity.R.string.settings_ski_infrastructure_subtitle_info,
                        liftCount.toIntOrNull() ?: 0,
                        generatedAt
                    )
                } else {
                    stringResource(com.adsamcik.tracker.activity.R.string.settings_ski_infrastructure_subtitle_loaded)
                }
            } else {
                stringResource(com.adsamcik.tracker.activity.R.string.settings_ski_infrastructure_subtitle_none)
            }

            SettingsItem(
                title = stringResource(com.adsamcik.tracker.activity.R.string.settings_ski_infrastructure_title),
                subtitle = subtitle,
                icon = Icons.Default.Terrain,
                onClick = {
                    if (isLoaded) {
                        infraManager.clearDatabase()
                        isLoaded = false
                    } else {
                        importLauncher.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*"))
                    }
                }
            )

            if (isLoaded) {
                Text(
                    text = stringResource(com.adsamcik.tracker.activity.R.string.settings_ski_infrastructure_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp)
                )
            }
        }

        // All map settings are advanced - wrap in expandable section
        item {
            com.adsamcik.tracker.app.settings.components.ExpandableSection(
                title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_advanced_section_title),
                initiallyExpanded = false
            ) {
                // Map quality slider
                val qualityValues = context.resources.getStringArray(com.adsamcik.tracker.map.R.array.settings_map_quality_values).map { it.toFloat() }
                val qualityKey = context.getString(com.adsamcik.tracker.map.R.string.settings_map_quality_key)
                val qualityDefault = context.getString(com.adsamcik.tracker.map.R.string.settings_map_quality_default).toFloat()
                val quality by prefs.observeFloat(qualityKey, qualityDefault).collectAsState(initial = qualityDefault)
                
                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_quality_title),
                    value = quality,
                    valueRange = qualityValues.first()..qualityValues.last(),
                    steps = qualityValues.size - 2,
                    valueLabel = { "%.1fx".format(it) },
                    onValueChange = {
                        prefs.edit { setFloat(qualityKey, it) }
                    },
                    helpTextRes = com.adsamcik.tracker.map.R.string.help_map_quality
                )
                
                // Max heat points slider
                val heatValues = context.resources.getIntArray(com.adsamcik.tracker.map.R.array.settings_map_max_heat_values)
                val heatKey = context.getString(com.adsamcik.tracker.map.R.string.settings_map_max_heat_key)
                val heatDefault = context.getString(com.adsamcik.tracker.map.R.string.settings_map_max_heat_default).toInt()
                val maxHeat by prefs.observeInt(heatKey, heatDefault).collectAsState(initial = heatDefault)
                
                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_max_heat_title),
                    value = maxHeat.toFloat(),
                    valueRange = heatValues.first().toFloat()..heatValues.last().toFloat(),
                    steps = heatValues.size - 2,
                    valueLabel = { "%d".format(it.toInt()) },
                    onValueChange = {
                        prefs.edit { setInt(heatKey, it.toInt()) }
                    },
                    helpTextRes = com.adsamcik.tracker.map.R.string.help_max_heat_points
                )
                
                // Visit threshold slider (duration in minutes)
                val visitValues = context.resources.getIntArray(com.adsamcik.tracker.map.R.array.settings_map_visit_threshold_values)
                val visitKey = context.getString(com.adsamcik.tracker.map.R.string.settings_map_visit_threshold_key)
                val visitDefault = context.getString(com.adsamcik.tracker.map.R.string.settings_map_visit_threshold_default).toInt()
                val visitThreshold by prefs.observeInt(visitKey, visitDefault).collectAsState(initial = visitDefault)
                
                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_visit_threshold_title),
                    value = visitThreshold.toFloat(),
                    valueRange = visitValues.first().toFloat()..visitValues.last().toFloat(),
                    steps = visitValues.size - 2,
                    valueLabel = { 
                        val minutes = it.toInt() / 60
                        if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}min"
                    },
                    onValueChange = {
                        prefs.edit { setInt(visitKey, it.toInt()) }
                    },
                    helpTextRes = com.adsamcik.tracker.map.R.string.help_visit_threshold
                )
            }
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
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
    ) {
        // Challenges section
        item {
            SectionHeader(stringResource(com.adsamcik.tracker.game.R.string.settings_game_challenge_category_title))
        }
        
        item {
            val challengeKey = context.getString(com.adsamcik.tracker.game.R.string.settings_game_challenge_enable_key)
            val challengeDefault = context.getString(com.adsamcik.tracker.game.R.string.settings_game_challenge_enable_default).toBoolean()
            val challengeEnabled by prefs.observeBoolean(challengeKey, challengeDefault).collectAsState(initial = challengeDefault)
            
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.game.R.string.settings_game_challenge_enable_title),
                checked = challengeEnabled,
                onCheckedChange = {
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
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
    ) {
        item {
            val autoUnitKey = context.getString(com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_key)
            val autoUnitDefault = context.getString(com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_default).toBoolean()
            val autoUnitSwitch by prefs.observeBoolean(autoUnitKey, autoUnitDefault).collectAsState(initial = autoUnitDefault)
            
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_title),
                subtitle = stringResource(com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_summary),
                checked = autoUnitSwitch,
                onCheckedChange = {
                    prefs.edit { setBoolean(autoUnitKey, it) }
                }
            )
        }
    }
}
