package com.adsamcik.tracker.app.settings.debug

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.components.SectionHeader
import com.adsamcik.tracker.app.settings.components.SettingsItem

@Composable
fun DebugSettingsScreen() {
    val context = LocalContext.current
    val developerModeDisabledMessage =
        stringResource(R.string.settings_debug_developer_mode_disabled_toast)
    val developerModeEnabled by com.adsamcik.tracker.shared.preferences.DeveloperPreferences
        .observeDeveloperMode(context)
        .collectAsStateWithLifecycle(initialValue = com.adsamcik.tracker.shared.preferences.DeveloperPreferences.isDeveloperModeEnabled(context))

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
                            developerModeDisabledMessage,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }

        // Developer tools (only show in debug/dev builds)
        if (com.adsamcik.tracker.BuildConfig.DEBUG || com.adsamcik.tracker.BuildConfig.BUILD_TYPE == "dev") {
            item {
                SectionHeader(stringResource(R.string.settings_debug_developer_tools_section))
            }

            item {
                com.adsamcik.tracker.app.debug.SeedDataSection()
            }
        }
    }
}
