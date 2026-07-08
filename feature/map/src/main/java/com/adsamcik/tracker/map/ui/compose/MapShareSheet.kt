package com.adsamcik.tracker.map.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.export.MapShareResolution
import com.adsamcik.tracker.shared.base.R as BaseR

// Contract:
// Input: visible flag drives presence; selectedResolution is the current radio selection;
//   isPreparing gates interaction while a capture is in flight.
// Output: onResolutionSelected on radio tap; onShareClick to start capture+share; onDismiss when
//   the user backs out (ignored while isPreparing since a capture is already underway).
// Errors: None (pure UI). Caller drives the actual MapSnapshotRenderer/MapImageShareHelper calls
//   and surfaces failures (e.g. via snackbar) itself.
/**
 * Bottom sheet for "share map as image": lets the user pick an output [MapShareResolution] and
 * kicks off the capture. Rendering happens off-screen (see MapSnapshotRenderer) so this sheet only
 * shows a "preparing…" state — there is no live preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapShareSheet(
	visible: Boolean,
	selectedResolution: MapShareResolution,
	isPreparing: Boolean,
	onResolutionSelected: (MapShareResolution) -> Unit,
	onShareClick: () -> Unit,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
) {
	if (!visible) return
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

	ModalBottomSheet(
		onDismissRequest = { if (!isPreparing) onDismiss() },
		sheetState = sheetState,
		modifier = modifier.testTag("map_share_sheet"),
	) {
		Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
			Text(
				text = stringResource(R.string.map_share_sheet_title),
				style = MaterialTheme.typography.titleLarge,
			)
			Text(
				text = stringResource(R.string.map_share_sheet_subtitle),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
			)

			MapShareResolution.entries.forEach { resolution ->
				ResolutionRow(
					label = stringResource(resolution.labelRes),
					selected = resolution == selectedResolution,
					enabled = !isPreparing,
					onClick = { onResolutionSelected(resolution) },
					modifier = Modifier.testTag("map_share_resolution_${resolution.name.lowercase()}"),
				)
			}

			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 20.dp, bottom = 12.dp),
				horizontalArrangement = Arrangement.End,
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (isPreparing) {
					CircularProgressIndicator(modifier = Modifier.padding(end = 16.dp).size(20.dp))
					Text(
						text = stringResource(R.string.map_share_preparing),
						style = MaterialTheme.typography.labelLarge,
					)
				} else {
					TextButton(onClick = onDismiss) {
						Text(stringResource(BaseR.string.generic_cancel))
					}
					TextButton(
						onClick = onShareClick,
						modifier = Modifier.testTag("map_share_confirm_button"),
					) {
						Text(stringResource(R.string.map_share_action))
					}
				}
			}
		}
	}
}

@Composable
private fun ResolutionRow(
	label: String,
	selected: Boolean,
	enabled: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
			.semantics { role = Role.RadioButton }
			.padding(vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RadioButton(selected = selected, onClick = null, enabled = enabled)
		Text(
			text = label,
			style = MaterialTheme.typography.bodyLarge,
			modifier = Modifier.padding(start = 12.dp),
		)
	}
}
