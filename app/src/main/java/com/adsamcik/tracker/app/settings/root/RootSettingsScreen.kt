package com.adsamcik.tracker.app.settings.root

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.SettingsScreen
import com.adsamcik.tracker.app.settings.SettingsViewModel
import com.adsamcik.tracker.app.settings.components.DialogListPreference
import com.adsamcik.tracker.app.settings.components.SectionHeader
import com.adsamcik.tracker.app.settings.components.SettingsGroupCard
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.app.activity.licenses.ThirdPartyLicensesActivity
import java.util.Locale

@Composable
fun RootSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigate: (SettingsScreen) -> Unit,
    onNavigateToActivities: () -> Unit = {}
) {
    val context = LocalContext.current
    val state by viewModel.settings.collectAsState()

    // Debug menu visibility
    val developerModeEnabled by com.adsamcik.tracker.shared.preferences.DeveloperPreferences
        .observeDeveloperMode(context)
        .collectAsState(initial = com.adsamcik.tracker.shared.preferences.DeveloperPreferences.isDeveloperModeEnabled(context))
    val showDebug = com.adsamcik.tracker.BuildConfig.DEBUG || developerModeEnabled

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp)
    ) {
        // Core settings group
        item {
            SettingsGroupCard(
                title = stringResource(R.string.settings_core_group_title),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                SettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_title),
                    icon = Icons.Default.GpsFixed,
                    onClick = { onNavigate(SettingsScreen.Tracking) }
                )
                SettingsItem(
                    title = stringResource(R.string.settings_data_title),
                    icon = Icons.Default.Folder,
                    onClick = { onNavigate(SettingsScreen.Data) }
                )
                SettingsItem(
                    title = stringResource(com.adsamcik.tracker.activity.R.string.settings_activity_title),
                    icon = Icons.AutoMirrored.Filled.DirectionsRun,
                    onClick = onNavigateToActivities
                )
            }
        }

        // General settings group
        item {
            SettingsGroupCard(
                title = stringResource(R.string.settings_other_title),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                // Length system
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

                // Auto unit switch
                SwitchSettingsItem(
                    title = stringResource(R.string.settings_auto_unit_switch_title),
                    subtitle = stringResource(if (state.autoUnitSwitch) R.string.settings_units_auto_summary_on else R.string.settings_units_auto_summary_off),
                    checked = state.autoUnitSwitch,
                    onCheckedChange = { viewModel.setAutoUnitSwitch(it) }
                )

                // Speed format
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

                // Language
                SettingsItem(
                    title = stringResource(R.string.settings_language_title),
                    subtitle = stringResource(R.string.settings_language_summary, Locale.getDefault().displayLanguage),
                    icon = Icons.Default.Translate,
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS))
                    }
                )
            }
        }

        // Module settings group
        item {
            SettingsGroupCard(
                title = stringResource(R.string.settings_module_group_title),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                SettingsItem(
                    title = stringResource(R.string.module_map_title),
                    subtitle = stringResource(R.string.settings_module_map_subtitle),
                    icon = Icons.Default.Map,
                    onClick = { onNavigate(SettingsScreen.Map) }
                )
                SettingsItem(
                    title = stringResource(R.string.module_game_title),
                    subtitle = stringResource(R.string.settings_module_game_subtitle),
                    icon = Icons.Default.EmojiEvents,
                    onClick = { onNavigate(SettingsScreen.Game) }
                )
                SettingsItem(
                    title = stringResource(R.string.module_statistics_title),
                    subtitle = stringResource(R.string.settings_module_statistics_subtitle),
                    icon = Icons.Default.BarChart,
                    onClick = { onNavigate(SettingsScreen.Statistics) }
                )
            }
        }

        // About section group
        item {
            SettingsGroupCard(
                title = stringResource(R.string.settings_about_header),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                SettingsItem(
                    title = stringResource(R.string.settings_about_app_title),
                    subtitle = stringResource(R.string.settings_about_app_subtitle),
                    icon = Icons.Default.Info,
                    onClick = { }
                )
                SettingsItem(
                    title = stringResource(R.string.settings_licenses_title),
                    icon = Icons.AutoMirrored.Filled.Article,
                    onClick = {
                        context.startActivity(Intent(context, ThirdPartyLicensesActivity::class.java))
                    }
                )
            }
        }

        // Debug (conditional)
        if (showDebug) {
            item {
                SettingsGroupCard(modifier = Modifier.padding(top = 12.dp)) {
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
        }

        // Version info card (7-tap enables developer mode in release builds)
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
