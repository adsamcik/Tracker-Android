package com.adsamcik.tracker.shared.utils.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Contract:
// Input: visible flag drives presence; every label supplied by the caller (no domain/resource
//   knowledge lives here) so this stays reusable wherever a running session could otherwise
//   silently resume itself right after being stopped.
// Output: Invokes exactly one of the three action callbacks, then onDismiss; or just onDismiss
//   when the user backs out without choosing.
// Errors: None (pure UI). Caller maintains state hoisting and performs the actual stop/lock calls.
/**
 * Offers a choice of how to stop a session that could otherwise be restarted automatically
 * (e.g. by activity-based auto-tracking) moments after a plain stop.
 *
 * Mirrors the options already surfaced by the persistent tracking notification: a timed lock,
 * a charge-based lock, or an immediate stop that may be superseded by automatic tracking.
 */
@Composable
fun StopTrackingOptionsDialog(
	visible: Boolean,
	title: String,
	message: String,
	stopForMinutesLabel: String,
	stopUntilChargingLabel: String,
	justStopLabel: String,
	cancelLabel: String,
	onStopForMinutes: () -> Unit,
	onStopUntilCharging: () -> Unit,
	onJustStop: () -> Unit,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
) {
	if (!visible) return
	AlertDialog(
		modifier = modifier.testTag("stop_tracking_options_dialog"),
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column(modifier = Modifier.fillMaxWidth()) {
				Text(
					text = message,
					style = MaterialTheme.typography.bodyMedium,
					modifier = Modifier.padding(bottom = 12.dp),
				)
				StopTrackingOptionRow(
					label = stopForMinutesLabel,
					onClick = onStopForMinutes,
					testTag = "stop_tracking_option_for_minutes",
				)
				StopTrackingOptionRow(
					label = stopUntilChargingLabel,
					onClick = onStopUntilCharging,
					testTag = "stop_tracking_option_until_charging",
				)
				StopTrackingOptionRow(
					label = justStopLabel,
					onClick = onJustStop,
					testTag = "stop_tracking_option_just_stop",
				)
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss, modifier = Modifier.testTag("stop_tracking_options_cancel")) {
				Text(cancelLabel)
			}
		},
	)
}

@Composable
private fun StopTrackingOptionRow(
	label: String,
	onClick: () -> Unit,
	testTag: String,
	modifier: Modifier = Modifier,
) {
	Text(
		text = label,
		style = MaterialTheme.typography.bodyLarge,
		modifier = modifier
			.fillMaxWidth()
			.clickable(role = Role.Button, onClick = onClick)
			.semantics { role = Role.Button }
			.padding(vertical = 12.dp)
			.testTag(testTag),
	)
}
