package com.adsamcik.tracker.statistics.wifi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.shared.base.extension.formatAsShortDateTime
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.stats.api.repository.DEFAULT_WIFI_OBSERVATION_BROWSE_LIMIT
import com.adsamcik.tracker.stats.api.repository.WifiObservationBrowseFilter
import com.adsamcik.tracker.stats.api.repository.WifiObservationBrowseItem
import com.adsamcik.tracker.statistics.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Wi-Fi list browser in Compose (legacy ManageActivity version removed).
 * Header + summary row + data rows with optional filter dialog.
 * Redesigned with Outdoor Modern aesthetic.
 */
@AndroidEntryPoint
class WifiBrowseActivityCompose : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		title = getString(R.string.wifilist_title)
		setContent {
			AppTheme {
				WifiBrowseRoute()
			}
		}
	}
}

@Composable
private fun WifiBrowseRoute(
	viewModel: WifiBrowseViewModel = hiltViewModel(),
) {
	val uiState by viewModel.uiState.collectAsState()
	val filter by viewModel.filter.collectAsState()
	var showDialog by remember { mutableStateOf(false) }

	WifiBrowseScreen(
		uiState = uiState,
		onOpenFilter = { showDialog = true },
	)

	if (showDialog) {
		WifiFilterDialog(
			current = filter,
			onDismiss = { showDialog = false },
			onApply = { newFilter ->
				viewModel.applyFilter(newFilter)
				showDialog = false
			},
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WifiBrowseScreen(
	uiState: WifiBrowseUiState,
	onOpenFilter: () -> Unit,
) {
	val horizontalTableScroll = rememberScrollState()

	Scaffold(
		containerColor = MaterialTheme.colorScheme.background,
		floatingActionButton = {
			FloatingActionButton(
				onClick = onOpenFilter,
				containerColor = MaterialTheme.colorScheme.primary,
				contentColor = MaterialTheme.colorScheme.onPrimary,
			) {
				Icon(
					Icons.Default.FilterList,
					contentDescription = stringResource(id = R.string.wifilist_title),
				)
			}
		},
	) { padding ->
		LazyColumn(
			modifier = Modifier
				.fillMaxSize()
				.padding(padding),
		) {
			when (uiState) {
				WifiBrowseUiState.Loading -> {
					item { LoadingContent() }
				}

				is WifiBrowseUiState.Error -> {
					item { ErrorContent(uiState.message) }
				}

				is WifiBrowseUiState.Success -> {
					item { SummaryRow(count = uiState.items.size) }
					item { HeaderRow(horizontalTableScroll) }
					items(
						items = uiState.items,
						key = { item ->
							listOf(
								item.bssid,
								item.ssid,
								item.capabilities,
								item.frequency.toString(),
								item.firstSeenAt.raw.toString(),
								item.lastSeenAt.raw.toString(),
							).joinToString("|")
						},
					) { item ->
						WifiItemRow(item, horizontalTableScroll)
					}
				}
			}

			item {
				val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
				Spacer(modifier = Modifier.height(72.dp + navBottom))
			}
		}
	}
}

@Composable
private fun LoadingContent() {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(24.dp),
		contentAlignment = Alignment.Center,
	) {
		CircularProgressIndicator()
	}
}

@Composable
private fun ErrorContent(message: String) {
	Text(
		text = message,
		color = MaterialTheme.colorScheme.error,
		style = MaterialTheme.typography.bodyMedium,
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 24.dp),
	)
}

@Composable
private fun SummaryRow(count: Int) {
	GlassCard(
		modifier = Modifier
			.fillMaxWidth()
			.padding(16.dp),
	) {
		Text(
			text = stringResource(R.string.wifilist_count, count),
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

@Composable
private fun HeaderRow(horizontalScrollState: ScrollState) {
	Box(
		modifier = Modifier
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.padding(vertical = 8.dp),
	) {
		WifiTableRow(
			bssid = stringResource(R.string.wifilist_title_bssid),
			ssid = stringResource(R.string.wifilist_title_ssid),
			capabilities = stringResource(R.string.wifilist_title_capabilities),
			frequency = stringResource(R.string.wifilist_title_frequency),
			firstSeen = stringResource(R.string.wifilist_title_first_seen),
			lastSeen = stringResource(R.string.wifilist_title_last_seen),
			header = true,
			horizontalScrollState = horizontalScrollState,
		)
	}
}

@Composable
private fun WifiItemRow(
	item: WifiObservationBrowseItem,
	horizontalScrollState: ScrollState,
) {
	Column {
		WifiTableRow(
			bssid = item.bssid,
			ssid = item.ssid,
			capabilities = item.capabilities,
			frequency = stringResource(R.string.wifilist_item_frequency, item.frequency),
			firstSeen = item.firstSeenAt.raw.formatAsShortDateTime(),
			lastSeen = item.lastSeenAt.raw.formatAsShortDateTime(),
			header = false,
			horizontalScrollState = horizontalScrollState,
		)
		Spacer(
			modifier = Modifier
				.height(1.dp)
				.fillMaxWidth()
				.background(MaterialTheme.colorScheme.outlineVariant),
		)
	}
}

@Composable
private fun WifiTableRow(
	bssid: String,
	ssid: String,
	capabilities: String,
	frequency: String,
	firstSeen: String,
	lastSeen: String,
	header: Boolean,
	horizontalScrollState: ScrollState,
) {
	val rowModifier = Modifier
		.fillMaxWidth()
		.horizontalScroll(horizontalScrollState)
		.padding(horizontal = 8.dp, vertical = 12.dp)

	Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
		ColumnCell(bssid, header)
		ColumnCell(ssid, header)
		ColumnCell(capabilities, header)
		ColumnCell(frequency, header)
		ColumnCell(firstSeen, header)
		ColumnCell(lastSeen, header)
	}
}

@Composable
private fun ColumnCell(text: String, header: Boolean) {
	Column(Modifier.width(100.dp)) {
		Text(
			text = text,
			style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
			fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
			color = if (header) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun WifiFilterDialog(
	current: WifiObservationBrowseFilter,
	onDismiss: () -> Unit,
	onApply: (WifiObservationBrowseFilter) -> Unit,
) {
	var bssid by remember { mutableStateOf(current.bssid.orEmpty()) }
	var ssid by remember { mutableStateOf(current.ssid.orEmpty()) }
	var capabilities by remember { mutableStateOf(current.capabilities.orEmpty()) }
	var frequency by remember { mutableStateOf(current.frequencyPrefix.orEmpty()) }
	var count by remember {
		mutableStateOf(
			if (current.limit == DEFAULT_WIFI_OBSERVATION_BROWSE_LIMIT) {
				""
			} else {
				current.limit.toString()
			},
		)
	}

	AlertDialog(
		onDismissRequest = onDismiss,
		containerColor = MaterialTheme.colorScheme.surface,
		confirmButton = {
			TextButton(
				onClick = {
					val parsedCount = count
						.toIntOrNull()
						?.coerceAtLeast(1)
						?: DEFAULT_WIFI_OBSERVATION_BROWSE_LIMIT
					onApply(
						WifiObservationBrowseFilter(
							bssid = bssid.ifBlank { null },
							ssid = ssid.ifBlank { null },
							capabilities = capabilities.ifBlank { null },
							frequencyPrefix = frequency.ifBlank { null },
							limit = parsedCount,
						),
					)
				},
			) {
				Text(stringResource(android.R.string.ok))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(android.R.string.cancel))
			}
		},
		title = { Text(stringResource(R.string.wifilist_title)) },
		text = {
			Column(Modifier.verticalScroll(rememberScrollState())) {
				FilterField(
					value = bssid,
					onValueChange = { bssid = it },
					label = stringResource(R.string.wifilist_filter_bssid),
				)
				FilterField(
					value = ssid,
					onValueChange = { ssid = it },
					label = stringResource(R.string.wifilist_filter_ssid),
				)
				FilterField(
					value = capabilities,
					onValueChange = { capabilities = it },
					label = stringResource(R.string.wifilist_filter_capabilities),
				)
				FilterField(
					value = frequency,
					onValueChange = { frequency = it },
					label = stringResource(R.string.wifilist_filter_frequency),
				)
				FilterField(
					value = count,
					onValueChange = { count = it },
					label = stringResource(R.string.wifilist_filter_count),
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
				)
			}
		},
	)
}

@Composable
private fun FilterField(
	value: String,
	onValueChange: (String) -> Unit,
	label: String,
	keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
	OutlinedTextField(
		value = value,
		onValueChange = onValueChange,
		label = { Text(label) },
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 4.dp),
		singleLine = true,
		keyboardOptions = keyboardOptions,
	)
}
}
