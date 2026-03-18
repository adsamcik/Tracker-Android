package com.adsamcik.tracker.app.settings.statistics

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.app.settings.StatisticsSettingsViewModel
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem

@Composable
fun StatisticsSettingsScreen(
    viewModel: StatisticsSettingsViewModel = hiltViewModel()
) {
    val autoUnitSwitch by viewModel.autoUnitSwitch.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
    ) {
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_title),
                subtitle = stringResource(com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_summary),
                checked = autoUnitSwitch,
                onCheckedChange = { viewModel.setAutoUnitSwitch(it) }
            )
        }
    }
}
