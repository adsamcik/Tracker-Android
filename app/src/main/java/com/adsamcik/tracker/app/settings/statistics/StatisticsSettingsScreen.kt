package com.adsamcik.tracker.app.settings.statistics

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem

@Composable
fun StatisticsSettingsScreen() {
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
