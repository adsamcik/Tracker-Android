package com.adsamcik.tracker.shared.utils.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Shared presentation only; callers use the existing tracking control APIs. */
@Composable
fun TrackingActionsMenu(
	menuLabel: String,
	turnOffLabel: String,
	onTurnOffTracking: () -> Unit,
	pauseLabel: String? = null,
	onPauseAutomaticTracking: (() -> Unit)? = null,
) {
	var expanded by remember { mutableStateOf(false) }
	Box {
		IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("trackingActionsMenu")) {
			Icon(Icons.Default.MoreVert, contentDescription = menuLabel)
		}
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			if (pauseLabel != null && onPauseAutomaticTracking != null) {
				DropdownMenuItem(text = { Text(pauseLabel) }, onClick = {
					expanded = false
					onPauseAutomaticTracking()
				}, modifier = Modifier.testTag("pauseAutomaticTracking"))
			}
			DropdownMenuItem(text = { Text(turnOffLabel) }, onClick = {
				expanded = false
				onTurnOffTracking()
			}, modifier = Modifier.testTag("turnOffTracking"))
		}
	}
}
