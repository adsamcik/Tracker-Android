package com.adsamcik.tracker.statistics.detail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.data.SessionActivity

/**
 * Compose dialog for selecting session activity.
 * Replaces the legacy MaterialDialog-based SessionActivitySelection class.
 */
@Composable
fun ActivitySelectionDialog(
    activities: List<SessionActivity>,
    selectedActivityId: Long?,
    onActivitySelected: (SessionActivity) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(com.adsamcik.tracker.statistics.R.string.stats_session_activity_selection_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            if (activities.isEmpty()) {
                Text(
                    text = stringResource(com.adsamcik.tracker.statistics.R.string.stats_session_activity_selection_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("activity_selection_empty")
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("activity_selection_list")
                ) {
                    items(
                        items = activities,
                        key = { it.id }
                    ) { activity ->
                        ActivitySelectionItem(
                            activity = activity,
                            isSelected = activity.id == selectedActivityId,
                            onSelected = { onActivitySelected(activity) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("activity_selection_close")
            ) {
                Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_cancel))
            }
        },
        modifier = modifier.testTag("activity_selection_dialog")
    )
}

@Composable
private fun ActivitySelectionItem(
    activity: SessionActivity,
    isSelected: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                role = Role.RadioButton,
                onClick = onSelected
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("activity_item_${activity.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelected,
            modifier = Modifier.semantics { role = Role.RadioButton }
        )
        Text(
            text = activity.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f)
        )
    }
}
