package com.adsamcik.tracker.app.settings.root

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.DirectionsRun

import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.SettingsScreen
import com.adsamcik.tracker.app.settings.SettingsViewModel
import com.adsamcik.tracker.app.settings.components.DialogListPreference
import com.adsamcik.tracker.app.settings.components.PrivacyPolicyDialog
import com.adsamcik.tracker.app.settings.components.SectionHeader
import com.adsamcik.tracker.app.settings.components.SettingsGroupCard
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsState
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.type.SpeedFormat
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.app.activity.licenses.ThirdPartyLicensesActivity
import java.util.Locale
import android.content.res.Configuration

@Composable
fun RootSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigate: (SettingsScreen) -> Unit,
    onNavigateToActivities: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by viewModel.settings.collectAsStateWithLifecycle()

    // Debug menu visibility
    val developerModeEnabled by com.adsamcik.tracker.shared.preferences.DeveloperPreferences
        .observeDeveloperMode(context)
        .collectAsStateWithLifecycle(initialValue = com.adsamcik.tracker.shared.preferences.DeveloperPreferences.isDeveloperModeEnabled(context))
    val showDebug = com.adsamcik.tracker.BuildConfig.DEBUG || developerModeEnabled

    RootSettingsContent(
        state = state,
        showDebug = showDebug,
        developerModeEnabled = developerModeEnabled,
        onNavigate = onNavigate,
        onNavigateToActivities = onNavigateToActivities,
        onNavigateToAbout = onNavigateToAbout,
        onAutoUnitSwitchChanged = viewModel::setAutoUnitSwitch,
        onLengthSystemSelected = viewModel::setLengthSystem,
        onSpeedFormatSelected = viewModel::setSpeedFormat,
    )
}

@Composable
internal fun RootSettingsContent(
    state: TrackerSettingsState,
    showDebug: Boolean,
    developerModeEnabled: Boolean,
    onNavigate: (SettingsScreen) -> Unit,
    onNavigateToActivities: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onAutoUnitSwitchChanged: (Boolean) -> Unit,
    onLengthSystemSelected: (String) -> Unit,
    onSpeedFormatSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    var showPrivacyPolicy by remember { mutableStateOf(false) }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(bottom = 16.dp + navBottom)
    ) {
        // Core settings group
        item {
            SettingsGroupCard(
                title = stringResource(R.string.settings_core_group_title),
                modifier = Modifier.padding(top = 12.dp)
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
                    icon = Icons.Default.Straighten,
                    onValueChange = { selectedIndex ->
                        onLengthSystemSelected(lengthValues[selectedIndex])
                    }
                )

                // Auto unit switch
                SwitchSettingsItem(
                    title = stringResource(R.string.settings_auto_unit_switch_title),
                    subtitle = stringResource(if (state.autoUnitSwitch) R.string.settings_units_auto_summary_on else R.string.settings_units_auto_summary_off),
                    icon = Icons.Default.SwapHoriz,
                    checked = state.autoUnitSwitch,
                    onCheckedChange = onAutoUnitSwitchChanged
                )

                // Speed format
                val speedNames = stringArrayResource(R.array.settings_speed_format_names).toList()
                val speedValues = stringArrayResource(R.array.settings_speed_format_values).toList()
                DialogListPreference(
                    title = stringResource(R.string.settings_speed_format_title),
                    currentValue = state.speedFormat.name,
                    entries = speedNames,
                    entryValues = speedValues,
                    icon = Icons.Default.Speed,
                    onValueChange = { selectedIndex ->
                        onSpeedFormatSelected(speedValues[selectedIndex])
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
                    onClick = onNavigateToAbout
                )
                SettingsItem(
                    title = stringResource(R.string.settings_licenses_title),
                    icon = Icons.AutoMirrored.Filled.Article,
                    onClick = {
                        context.startActivity(Intent(context, ThirdPartyLicensesActivity::class.java))
                    }
                )
                SettingsItem(
                    title = stringResource(R.string.settings_privacy_policy_title),
                    icon = Icons.Default.PrivacyTip,
                    onClick = { showPrivacyPolicy = true }
                )
            }
        }

        // Debug (conditional)
        if (showDebug) {
            item {
                SettingsGroupCard(
                    modifier = Modifier.padding(top = 12.dp),
                    title = stringResource(R.string.settings_debug_group_title),
                ) {
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
            val developerModeEnabledToast = stringResource(R.string.settings_developer_mode_enabled_toast)

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
                                        developerModeEnabledToast,
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

    if (showPrivacyPolicy) {
        PrivacyPolicyDialog(onDismissRequest = { showPrivacyPolicy = false })
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RootSettingsScreenPreview() {
    AppTheme {
        RootSettingsContent(
            state = TrackerSettingsState(
                autoUnitSwitch = true,
                lengthSystem = LengthSystem.Metric,
                speedFormat = SpeedFormat.Hour,
            ),
            showDebug = true,
            developerModeEnabled = true,
            onNavigate = {},
            onNavigateToActivities = {},
            onNavigateToAbout = {},
            onAutoUnitSwitchChanged = {},
            onLengthSystemSelected = {},
            onSpeedFormatSelected = {},
        )
    }
}
