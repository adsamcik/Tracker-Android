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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.DebugSettingsViewModel
import com.adsamcik.tracker.app.settings.components.SectionHeader
import com.adsamcik.tracker.app.settings.components.SettingsItem

@Composable
fun DebugSettingsScreen(onNavigateToDebug: () -> Unit = {}) {
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
