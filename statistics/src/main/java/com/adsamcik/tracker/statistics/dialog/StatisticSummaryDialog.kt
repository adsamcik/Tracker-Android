package com.adsamcik.tracker.statistics.dialog

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.statistics.data.Stat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.adsamcik.tracker.shared.base.R as BaseR

@Composable
fun StatisticSummaryDialog(
	visible: Boolean,
	@StringRes titleRes: Int,
	dataLoader: suspend () -> Collection<Stat>,
	onDismiss: () -> Unit,
) {
	if (!visible) return

	var isLoading by remember { mutableStateOf(true) }
	var stats by remember { mutableStateOf<List<Stat>>(emptyList()) }

	LaunchedEffect(Unit) {
		isLoading = true
		stats = withContext(Dispatchers.Default) { dataLoader().toList() }
		isLoading = false
	}

	AlertDialog(
		modifier = Modifier.testTag("statisticSummaryDialog"),
		onDismissRequest = onDismiss,
		title = { Text(text = stringResource(titleRes)) },
		text = {
			if (isLoading) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.Center,
				) {
					CircularProgressIndicator()
				}
			} else {
				LazyColumn(
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(max = 400.dp),
				) {
					items(stats) { stat ->
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(vertical = 4.dp),
							horizontalArrangement = Arrangement.SpaceBetween,
						) {
							Text(
								text = stringResource(stat.nameRes),
								style = MaterialTheme.typography.bodyMedium,
								modifier = Modifier.weight(1f),
							)
							Text(
								text = stat.data.toString(),
								style = MaterialTheme.typography.bodyMedium,
							)
						}
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) {
				Text(text = stringResource(BaseR.string.generic_ok))
			}
		},
	)
}
