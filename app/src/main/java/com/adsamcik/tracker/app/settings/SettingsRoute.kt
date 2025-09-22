package com.adsamcik.tracker.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adsamcik.tracker.shared.base.di.LocalViewModelFactory

@Composable
fun SettingsRoute() {
    val factory = LocalViewModelFactory.current
    val vm: SettingsViewModel = viewModel(factory = factory)
    val state = vm.settings.collectAsState().value
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("Settings (Preview)", style = MaterialTheme.typography.headlineSmall)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Automatic unit switching", style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = state.autoUnitSwitch,
                onCheckedChange = { vm.setAutoUnitSwitch(it) },
                modifier = Modifier.testTag("switch_auto_unit")
            )
            Text(
                if (state.autoUnitSwitch) "Will adapt length system to activity" else "Uses default length system only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
