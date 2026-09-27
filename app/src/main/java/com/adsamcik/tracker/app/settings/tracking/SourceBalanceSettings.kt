package com.adsamcik.tracker.app.settings.tracking

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.TrackingSettingsUiState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent

@Composable
internal fun SourceBalanceSettingsItem(state: TrackingSettingsUiState, onSelected: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("trackingBalance")) {
        Text(if (state.overallBalanceLevel == null) stringResource(R.string.settings_balance_custom)
            else stringResource(R.string.settings_balance_value, locationBalanceLabel(state.overallBalanceLevel)))
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.settings_balance_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                Text(stringResource(R.string.settings_balance_summary))
                (1..5).forEach { level ->
                    LocationChoice(locationBalanceLabel(level), state.overallBalanceLevel == level, "trackingBalance-$level") {
                        open = false
                        onSelected(level)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
internal fun SourceBalanceOverrideItem(source: TrackingSourceComponent, state: TrackingSettingsUiState,
    onSelected: (TrackingSourceComponent, Int?) -> Unit) {
    if (state.balanceProfileVersion == null) return
    var open by remember { mutableStateOf(false) }
    val override = state.sourceBalanceOverrides[source]
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("sourceBalance-${source.name}")) {
        Text(if (override == null) stringResource(R.string.settings_balance_inherit_value,
            locationBalanceLabel(requireNotNull(state.overallBalanceLevel)))
            else stringResource(R.string.settings_balance_value, locationBalanceLabel(override)))
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.settings_balance_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                LocationChoice(stringResource(R.string.settings_balance_inherit), override == null,
                    "sourceBalance-${source.name}-inherit") { open = false; onSelected(source, null) }
                (1..5).forEach { level ->
                    LocationChoice(locationBalanceLabel(level), override == level, "sourceBalance-${source.name}-$level") {
                        open = false
                        onSelected(source, level)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}
