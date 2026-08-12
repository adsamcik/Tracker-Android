package com.adsamcik.tracker.app.settings.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.MapSettingsViewModel
import com.adsamcik.tracker.app.settings.components.SettingsGroupCard
import com.adsamcik.tracker.app.settings.components.SettingsItemWithValue
import com.adsamcik.tracker.app.settings.components.SettingsNoticeCard
import com.adsamcik.tracker.app.settings.components.SettingsRowDivider
import com.adsamcik.tracker.app.settings.components.SingleChoiceDialog
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.map.online.TileProvider

/**
 * Settings section that surfaces the user's online map tile preference.
 *
 * - Master switch (defaults to off → fully offline behaviour preserved)
 * - Privacy disclosure card always visible
 * - Provider picker (built-in providers + "Custom URL") visible only when ON
 * - Custom URL text field visible only when the active provider is "Custom"
 * - Attribution line for the active provider
 *
 * The screen never touches the network directly — the switch + provider id
 * are persisted via [MapSettingsViewModel] / `OnlineMapTilesRepository`,
 * and `MapStore` mirrors those preferences into the `NetworkGateway`.
 */
@Composable
fun OnlineTilesSection(viewModel: MapSettingsViewModel) {
	val state by viewModel.onlineTiles.collectAsState()
	val provider = TileProvider.resolve(state.providerId, state.customUrl)

	Column(modifier = Modifier.padding(top = 12.dp)) {
		SettingsGroupCard(
			title = stringResource(R.string.settings_map_online_tiles_section_title),
			icon = Icons.Default.Cloud,
		) {
			SwitchSettingsItem(
				title = stringResource(R.string.settings_map_online_tiles_enable_title),
				subtitle = if (state.enabled) {
					stringResource(R.string.settings_map_online_tiles_enable_subtitle_on, provider.displayName)
				} else {
					stringResource(R.string.settings_map_online_tiles_enable_subtitle_off)
				},
				icon = Icons.Default.Cloud,
				checked = state.enabled,
				onCheckedChange = viewModel::setOnlineTilesEnabled,
			)

			if (state.enabled) {
				SettingsRowDivider()
				ProviderPickerItem(
					activeProviderId = state.providerId,
					onProviderSelected = viewModel::setOnlineProviderId,
				)

				if (state.providerId == TileProvider.Custom.ID) {
					SettingsRowDivider()
					OutlinedTextField(
						value = state.customUrl,
						onValueChange = viewModel::setOnlineCustomUrl,
						label = { Text(stringResource(R.string.settings_map_online_tiles_custom_url_label)) },
						placeholder = { Text(stringResource(R.string.settings_map_online_tiles_custom_url_hint)) },
						singleLine = true,
						keyboardOptions = KeyboardOptions(
							capitalization = KeyboardCapitalization.None,
							autoCorrectEnabled = false,
							keyboardType = KeyboardType.Uri,
						),
						modifier = Modifier
							.fillMaxWidth()
							.padding(16.dp)
							.testTag("OnlineTilesCustomUrlField"),
					)
				}

				Text(
					text = stringResource(R.string.settings_map_online_tiles_attribution_format, provider.attribution),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(start = 68.dp, end = 16.dp, bottom = 12.dp),
				)
			}
		}
		SettingsNoticeCard(
			text = stringResource(R.string.settings_map_online_tiles_privacy_disclosure),
			icon = Icons.Default.PrivacyTip,
			modifier = Modifier.padding(top = 8.dp),
		)
	}
}

/**
 * Visible picker for the three built-in/custom provider variants. Opens a
 * single-choice dialog rather than rendering an inline radio group to keep the
 * settings scroll list compact.
 */
@Composable
private fun ProviderPickerItem(
	activeProviderId: String,
	onProviderSelected: (String) -> Unit,
) {
	val pickerOptions = listOf(
		PickerOption(
			id = TileProvider.OpenFreeMap.ID,
			labelRes = R.string.settings_map_online_tiles_provider_openfreemap,
		),
		PickerOption(
			id = TileProvider.Protomaps.ID,
			labelRes = R.string.settings_map_online_tiles_provider_protomaps,
		),
		PickerOption(
			id = TileProvider.Custom.ID,
			labelRes = R.string.settings_map_online_tiles_provider_custom,
		),
	)
	val labels = pickerOptions.map { stringResource(it.labelRes) }
	val activeIndex = pickerOptions
		.indexOfFirst { it.id == activeProviderId }
		.coerceAtLeast(0)
	var showDialog by remember { mutableStateOf(false) }

	SettingsItemWithValue(
		title = stringResource(R.string.settings_map_online_tiles_provider_title),
		value = labels[activeIndex],
		icon = Icons.Default.Public,
		onClick = { showDialog = true },
		modifier = Modifier.testTag("OnlineTilesProviderPicker"),
	)

	if (showDialog) {
		SingleChoiceDialog(
			title = stringResource(R.string.settings_map_online_tiles_provider_title),
			options = labels,
			selectedIndex = activeIndex,
			onConfirm = { selected ->
				onProviderSelected(pickerOptions[selected].id)
				showDialog = false
			},
			onDismiss = { showDialog = false },
		)
	}
}

private data class PickerOption(val id: String, val labelRes: Int)
