@file:Suppress("FunctionNaming")

package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.stats.api.repository.ImportedStepsHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ImportedStepsHistoryMember
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import java.text.NumberFormat

/** One logical import; expansion exposes exact recordings, never a fabricated logical total. */
@Composable
internal fun RecentImportedStepsRow(
	history: ImportedStepsHistoryEntry,
	onTripClick: ((Long) -> Unit)?,
) {
	var expanded by remember(history.key) { mutableStateOf(false) }
	Column(Modifier.fillMaxWidth()) {
		Text(
			text = stringResource(R.string.dashboard_imported_steps_title),
			style = MaterialTheme.typography.bodyMedium,
		)
		Text(
			text = relativeTime(history.physicalMembers.maxOf { it.startTime.raw }),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		if (history.physicalMembers.size > 1) {
			TextButton(onClick = { expanded = !expanded }) {
				Text(pluralStringResource(
					if (expanded) {
						R.plurals.dashboard_imported_steps_hide_recordings
					} else {
						R.plurals.dashboard_imported_steps_show_recordings
					},
					history.physicalMembers.size,
					history.physicalMembers.size,
				))
			}
		}
		if (history.physicalMembers.size == 1 || expanded) {
			history.physicalMembers.forEachIndexed { index, member ->
				ImportedStepsMemberRow(member, index + 1, onTripClick)
			}
		}
	}
}

@Composable
private fun ImportedStepsMemberRow(
	member: ImportedStepsHistoryMember,
	recordingNumber: Int,
	onTripClick: ((Long) -> Unit)?,
) {
	val label = stringResource(R.string.dashboard_imported_steps_recording, recordingNumber)
	val value = when {
		member.steps.hasCompleteValue -> stringResource(
			R.string.dashboard_imported_steps_count,
			NumberFormat.getIntegerInstance().format(requireNotNull(member.steps.count)),
		)
		member.steps.isLowerBound -> stringResource(
			R.string.dashboard_imported_steps_lower_bound,
			NumberFormat.getIntegerInstance().format(requireNotNull(member.steps.count)),
		)
		member.steps.productState == HistoryProductState.MATERIALIZING ->
			stringResource(R.string.dashboard_recent_steps_materializing)
		else -> stringResource(R.string.dashboard_imported_steps_unavailable)
	}
	if (onTripClick != null) {
		TextButton(onClick = { onTripClick(member.segmentId) }) {
			Text("$label · $value")
		}
	} else {
		Text("$label · $value", style = MaterialTheme.typography.bodySmall)
	}
}
