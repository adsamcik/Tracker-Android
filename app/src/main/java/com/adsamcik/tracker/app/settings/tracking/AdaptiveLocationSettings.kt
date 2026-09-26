package com.adsamcik.tracker.app.settings.tracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.TrackingSettingsUiState
import com.adsamcik.tracker.shared.preferences.tracking.LocationBackgroundMode
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism

@Composable
internal fun AdaptiveLocationSettings(
	state: TrackingSettingsUiState,
	onEnabledChanged: (Boolean) -> Unit,
	onModeChanged: (LocationBackgroundMode) -> Unit,
	onBalanceChanged: (Int) -> Unit,
	onAssistanceChanged: (Boolean) -> Unit,
	onLocationPermission: () -> Unit,
	onMotionPermission: () -> Unit,
) {
	var confirmEnable by remember { mutableStateOf<Boolean?>(null) }
	var showBalance by remember { mutableStateOf(false) }
	val adaptive = state.locationBackgroundMode == LocationBackgroundMode.ADAPTIVE
	Column(
		modifier = Modifier.fillMaxWidth().padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		LocationSwitch(
			title = stringResource(R.string.settings_all_day_location_title),
			checked = state.ambientLocationEnabled,
			tag = "ambientSource-location",
			onChecked = { enabled -> if (enabled) confirmEnable = true else onEnabledChanged(false) },
		)
		Text(
			text = stringResource(
				if (state.ambientLocationEnabled) R.string.settings_all_day_location_saved
				else R.string.settings_all_day_location_off,
			),
			style = MaterialTheme.typography.bodySmall,
		)
		if (state.ambientLocationEnabled) {
			Column(Modifier.selectableGroup()) {
				LocationChoice(
					stringResource(R.string.settings_location_mode_adaptive), adaptive,
					"locationMode-adaptive",
				) { if (!adaptive) confirmEnable = false }
				LocationChoice(
					stringResource(R.string.settings_location_mode_passive), !adaptive,
					"locationMode-passive",
				) { if (adaptive) onModeChanged(LocationBackgroundMode.PASSIVE_ONLY) }
			}
			Text(
				stringResource(if (adaptive) R.string.settings_location_adaptive_summary else R.string.settings_location_passive_summary),
				style = MaterialTheme.typography.bodySmall,
			)
			Text(
				if (adaptive && state.ambientSourceAvailability.getValue(AmbientTrackingSource.LOCATION).let {
					it.isOperational && it.mechanism != AmbientAcquisitionMechanism.ADAPTIVE_LOCATION
				}) {
					// A passive acknowledgement is not an acknowledgement of adaptive collection.
					stringResource(R.string.settings_location_adaptive_not_ready)
				} else ambientOperationalSummary(state.ambientSourceAvailability.getValue(AmbientTrackingSource.LOCATION)),
				style = MaterialTheme.typography.bodySmall,
			)
			if (!state.permissionCapabilities.hasBackgroundLocation) {
				TextButton(onClick = onLocationPermission, modifier = Modifier.testTag("locationGrantAccess")) {
					Text(stringResource(R.string.settings_location_allow_access))
				}
			}
		}
		TextButton(onClick = { showBalance = true }, modifier = Modifier.testTag("locationBalance")) {
			Text(
				if (state.locationBalanceLevel == null) stringResource(R.string.settings_location_adopt_profile)
				else stringResource(R.string.settings_location_balance_value, locationBalanceLabel(state.locationBalanceLevel)),
			)
		}
		if (state.locationProfileVersion == 1) {
			LocationSwitch(
				stringResource(R.string.settings_location_motion_assistance),
				state.locationMotionAssistanceEnabled, "locationMotionAssistance", onAssistanceChanged,
			)
			Text(stringResource(R.string.settings_location_motion_assistance_summary), style = MaterialTheme.typography.bodySmall)
			if (state.locationMotionAssistanceEnabled && !state.activityPermissionGranted) {
				TextButton(onClick = onMotionPermission, modifier = Modifier.testTag("locationGrantMotion")) {
					Text(stringResource(R.string.settings_location_allow_motion))
				}
			}
		}
		if (state.locationSettingsSaveFailed) {
			Text(stringResource(R.string.settings_location_save_failed), color = MaterialTheme.colorScheme.error)
		}
	}
	confirmEnable?.let { enable ->
		AlertDialog(
			onDismissRequest = { confirmEnable = null },
			title = { Text(stringResource(R.string.settings_all_day_location_title)) },
			text = { Text(stringResource(R.string.settings_location_adaptive_disclosure)) },
			confirmButton = {
				TextButton(onClick = {
					confirmEnable = null
					if (enable) onEnabledChanged(true) else onModeChanged(LocationBackgroundMode.ADAPTIVE)
				}, modifier = Modifier.testTag("confirmAdaptiveLocation")) {
					Text(stringResource(R.string.settings_location_use_adaptive))
				}
			},
			dismissButton = {
				TextButton(onClick = { confirmEnable = null }) { Text(stringResource(android.R.string.cancel)) }
			},
		)
	}
	if (showBalance) {
		AlertDialog(
			onDismissRequest = { showBalance = false },
			title = { Text(stringResource(R.string.settings_location_balance_title)) },
			text = {
				Column(Modifier.selectableGroup()) {
					Text(stringResource(R.string.settings_location_balance_summary))
					(1..5).forEach { level ->
						LocationChoice(locationBalanceLabel(level), state.locationBalanceLevel == level, "locationBalance-$level") {
							showBalance = false
							onBalanceChanged(level)
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { showBalance = false }) { Text(stringResource(android.R.string.cancel)) }
			},
		)
	}
}

@Composable
private fun locationBalanceLabel(level: Int): String = stringResource(
	when (level) {
		1 -> R.string.settings_location_balance_1
		2 -> R.string.settings_location_balance_2
		3 -> R.string.settings_location_balance_3
		4 -> R.string.settings_location_balance_4
		else -> R.string.settings_location_balance_5
	},
)

@Composable
private fun LocationSwitch(title: String, checked: Boolean, tag: String, onChecked: (Boolean) -> Unit) {
	Row(
		Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChecked)
			.padding(vertical = 8.dp).testTag(tag),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
		Switch(checked, onCheckedChange = null)
	}
}

@Composable
private fun LocationChoice(title: String, selected: Boolean, tag: String, onClick: () -> Unit) {
	Row(
		Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
			.padding(vertical = 8.dp).testTag(tag),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RadioButton(selected, onClick = null)
		Text(title, Modifier.padding(start = 8.dp))
	}
}
