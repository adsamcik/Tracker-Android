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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.statistics.R
import kotlinx.coroutines.withContext
import java.util.Locale

private val defaultDispatchers = DefaultDispatchersProvider

@Composable
fun WifiStatsDialog(
	visible: Boolean,
	onDismiss: () -> Unit,
) {
	if (!visible) return

	val context = LocalContext.current
	var uiState by remember { mutableStateOf<WifiStatsUiState>(WifiStatsUiState.Loading) }

	LaunchedEffect(context) {
		uiState = WifiStatsUiState.Loading
		uiState = try {
			val summary = withContext(defaultDispatchers.io) {
				val database = AppDatabase.database(context)
				val uniqueNetworks = database.wifiObservationDao().countDistinctBssid()
				val cursor = database.query(
					SimpleSQLiteQuery(
						"SELECT COUNT(*), COUNT(DISTINCT time_ms) FROM wifi_observation"
					)
				)
				cursor.moveToFirst()
				val totalObservations = cursor.getLong(0)
				val distinctScanTimes = cursor.getLong(1)
				cursor.close()
				val averageNetworksPerScan = if (distinctScanTimes > 0) {
					totalObservations.toDouble() / distinctScanTimes
				} else {
					0.0
				}
				WifiStatsSummary(
					uniqueNetworks = uniqueNetworks,
					totalScans = distinctScanTimes,
					averageNetworksPerScan = averageNetworksPerScan,
				)
			}
			if (summary.totalScans == 0L && summary.uniqueNetworks == 0L) {
				WifiStatsUiState.Empty
			} else {
				WifiStatsUiState.Success(summary)
			}
		} catch (e: Throwable) {
			WifiStatsUiState.Error
		}
	}

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(text = stringResource(R.string.stats_wifi_dialog_title), style = MaterialTheme.typography.headlineSmall) },
		text = {
			when (val state = uiState) {
				WifiStatsUiState.Loading -> {
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

				WifiStatsUiState.Empty -> {
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

				WifiStatsUiState.Error -> {
					Text(
						text = stringResource(R.string.stats_wifi_dialog_error),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.error,
					)
				}

				is WifiStatsUiState.Success -> {
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

private sealed interface WifiStatsUiState {
	data object Loading : WifiStatsUiState
	data object Empty : WifiStatsUiState
	data object Error : WifiStatsUiState
	data class Success(val summary: WifiStatsSummary) : WifiStatsUiState
}

private data class WifiStatsSummary(
	val uniqueNetworks: Long,
	val totalScans: Long,
	val averageNetworksPerScan: Double,
)
