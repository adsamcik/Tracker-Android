package com.adsamcik.tracker.app.settings.debug

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Science
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.DebugSettingsViewModel
import com.adsamcik.tracker.app.settings.components.SectionHeader
import com.adsamcik.tracker.app.settings.components.SettingsItem

@Composable
fun DebugSettingsScreen(onNavigateToDebug: () -> Unit = {}) {
    val context = LocalContext.current
    val debugVm: DebugSettingsViewModel = hiltViewModel()
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
                    subtitle = stringResource(R.string.settings_debug_disable_developer_subtitle),
                    icon = Icons.Default.Close,
                    onClick = {
                        com.adsamcik.tracker.shared.preferences.DeveloperPreferences.setDeveloperMode(context, false)
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.settings_debug_developer_mode_disabled_toast),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }

        // Debug tools
        item {
            SectionHeader(stringResource(R.string.settings_debug_tools_section))
        }

        item {
            SettingsItem(
                title = stringResource(R.string.settings_debug_crash_manager_title),
                subtitle = stringResource(R.string.settings_debug_crash_manager_subtitle),
                icon = Icons.Default.BugReport,
                onClick = {
                    context.startActivity(Intent(context, com.adsamcik.tracker.app.activity.debug.CrashManagerActivity::class.java))
                }
            )
        }

        item {
            SettingsItem(
                title = stringResource(R.string.settings_debug_log_viewer_title),
                subtitle = stringResource(R.string.settings_debug_log_viewer_subtitle),
                icon = Icons.Default.Description,
                onClick = {
                    onNavigateToDebug()
                }
            )
        }

        // Developer tools (only show in debug builds)
        if (com.adsamcik.tracker.BuildConfig.DEBUG) {
            item {
                SectionHeader(stringResource(R.string.settings_debug_developer_tools_section))
            }

            item {
                com.adsamcik.tracker.app.debug.SeedDataSection()
            }
        }
    }
}
