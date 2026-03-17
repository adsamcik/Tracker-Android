package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.viewmodel.WifiStatsLoadState
import java.util.Locale

@Composable
fun WifiStatsDialog(
	visible: Boolean,
	state: WifiStatsLoadState,
	onDismiss: () -> Unit,
) {
	if (!visible) return

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(text = stringResource(R.string.stats_wifi_dialog_title), style = MaterialTheme.typography.headlineSmall) },
		text = {
			when (state) {
				WifiStatsLoadState.Idle, WifiStatsLoadState.Loading -> {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(12.dp),
					) {
						CircularProgressIndicator()
						Text(
							text = stringResource(R.string.stats_wifi_dialog_loading),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}

				WifiStatsLoadState.Empty -> {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						Text(
							text = stringResource(R.string.stats_wifi_dialog_empty_title),
							style = MaterialTheme.typography.titleMedium,
						)
						Text(
							text = stringResource(R.string.stats_wifi_dialog_empty_subtitle),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}

				is WifiStatsLoadState.Error -> {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						Text(
							text = stringResource(R.string.stats_wifi_dialog_error),
							style = MaterialTheme.typography.titleMedium,
							color = MaterialTheme.colorScheme.error,
						)
						Text(
							text = state.message,
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}

				is WifiStatsLoadState.Success -> {
					Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
						WifiStatRow(
							label = stringResource(R.string.stats_wifi_unique_networks),
							value = state.summary.uniqueNetworks.formatReadable(),
						)
						WifiStatRow(
							label = stringResource(R.string.stats_wifi_total_scans),
							value = state.summary.totalScans.formatReadable(),
						)
						WifiStatRow(
							label = stringResource(R.string.stats_wifi_average_per_scan),
							value = String.format(Locale.getDefault(), "%.1f", state.summary.averageNetworksPerScan),
						)
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) {
				Text(text = stringResource(android.R.string.ok))
			}
		},
	)
}

@Composable
private fun WifiStatRow(label: String, value: String) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 2.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Text(text = value, style = MaterialTheme.typography.titleMedium)
	}
}
