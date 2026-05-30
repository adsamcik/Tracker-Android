package com.adsamcik.tracker.app.settings.osm

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.components.SectionHeader
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.osm.imp.OsmImportState
import com.adsamcik.tracker.shared.base.database.data.OsmImportEntity
import java.text.DateFormat
import java.util.Date

/**
 * Map data (OpenStreetMap) section embedded into [DataSettingsScreen]. Lists
 * imported regions, exposes the SAF picker for adding a new region, shows
 * live import progress (with cancel), and lets the user delete a region.
 *
 * The MIME type filter is &#42;/&#42; (any) because the standard `.osm.pbf` MIME type
 * (`application/x-pbf`) is not widely supported by document providers.
 */
fun LazyListScope.osmImportSection() {
	item { OsmImportSectionContent() }
}

@Composable
private fun OsmImportSectionContent() {
	val viewModel: OsmImportSettingsViewModel = hiltViewModel()
	val uiState by viewModel.uiState.collectAsState()
	val context = LocalContext.current

	val picker = rememberLauncherForActivityResult(
		contract = ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) viewModel.importFromUri(uri)
	}

	var pendingRemoval by remember { mutableStateOf<OsmImportEntity?>(null) }

	Column {
		SectionHeader(stringResource(R.string.settings_osm_import_section))

		val (subtitleRes, subtitleArgs) = importActionSubtitle(uiState.runtimeState)
		val isRunning = uiState.runtimeState is OsmImportState.Running
		SettingsItem(
			title = stringResource(R.string.settings_osm_import_action_title),
			subtitle = if (subtitleArgs.isEmpty()) {
				stringResource(subtitleRes)
			} else {
				stringResource(subtitleRes, *subtitleArgs.toTypedArray())
			},
			icon = Icons.Default.Map,
			onClick = {
				if (isRunning) {
					viewModel.cancelImport()
				} else {
					picker.launch(arrayOf("*/*"))
				}
			},
		)
		if (isRunning) {
			LinearProgressIndicator(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp),
			)
		}

		if (uiState.imports.isEmpty()) {
			Text(
				text = stringResource(R.string.settings_osm_imports_empty),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
			)
		} else {
			uiState.imports.forEach { entry ->
				ImportedRegionRow(entry, onDelete = { pendingRemoval = entry })
				HorizontalDivider(
					modifier = Modifier.padding(start = 16.dp, end = 16.dp),
					thickness = 0.5.dp,
				)
			}
		}

		Text(
			text = stringResource(R.string.settings_osm_imports_attribution),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
		)
	}

	val toRemove = pendingRemoval
	if (toRemove != null) {
		AlertDialog(
			onDismissRequest = { pendingRemoval = null },
			title = { Text(stringResource(R.string.settings_osm_import_remove_title)) },
			text = {
				Text(
					stringResource(
						R.string.settings_osm_import_remove_message,
						toRemove.displayName,
					),
				)
			},
			confirmButton = {
				Button(
					onClick = {
						viewModel.removeImport(toRemove)
						pendingRemoval = null
					},
					colors = ButtonDefaults.buttonColors(
						containerColor = MaterialTheme.colorScheme.error,
					),
				) {
					Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_delete))
				}
			},
			dismissButton = {
				TextButton(onClick = { pendingRemoval = null }) {
					Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_cancel))
				}
			},
		)
	}
}

@Composable
private fun ImportedRegionRow(
	entry: OsmImportEntity,
	onDelete: () -> Unit,
) {
	val date = remember(entry.importedAt) {
		DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.importedAt))
	}
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Icon(
			imageVector = Icons.Default.Map,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = entry.displayName,
				style = MaterialTheme.typography.bodyLarge,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = stringResource(
					R.string.settings_osm_import_entry_subtitle,
					entry.wayCount,
					date,
				),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		IconButton(onClick = onDelete) {
			Icon(
				imageVector = Icons.Default.Delete,
				contentDescription = stringResource(
					R.string.settings_osm_import_remove_title,
				),
				tint = MaterialTheme.colorScheme.error,
			)
		}
	}
}

/**
 * Returns the (stringRes, args) for the subtitle under the "Import OSM region"
 * action row. Pulled into a helper so it can be unit-tested independently.
 */
private fun importActionSubtitle(state: OsmImportState): Pair<Int, List<Any>> {
	return when (state) {
		is OsmImportState.Running -> R.string.settings_osm_import_action_running to emptyList()
		is OsmImportState.Failed ->
			R.string.settings_osm_import_action_failed to listOf(state.message)
		is OsmImportState.Success ->
			R.string.settings_osm_import_action_success to
				listOf<Any>(state.wayCount, state.nodeCount)
		OsmImportState.Idle, OsmImportState.Cancelled ->
			R.string.settings_osm_import_action_summary to emptyList()
	}
}

/** Visible only for unit tests. */
@Suppress("unused")
internal val Icons_Close_marker = Icons.Default.Close
