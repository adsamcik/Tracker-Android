package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.Stat

/**
 * Dialog displaying weekly (last 7 days) summary statistics.
 * 
 * @param visible Whether the dialog is shown
 * @param stats List of weekly statistics to display
 * @param onDismiss Called when user dismisses dialog
 */
@Composable
fun WeekDialog(
    visible: Boolean,
    stats: List<Stat>,
    onDismiss: () -> Unit
) {
    if (visible) {
        AlertDialog(
            modifier = Modifier.testTag("weekDialog"),
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = stringResource(R.string.stats_weekly_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(stats) { stat ->
                        WeekStatRow(stat = stat)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("weekDialog_dismiss")
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

/**
 * Individual row for displaying a weekly statistic in the week dialog.
 */
@Composable
private fun WeekStatRow(stat: Stat) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = stringResource(stat.nameRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stat.data.toString(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}