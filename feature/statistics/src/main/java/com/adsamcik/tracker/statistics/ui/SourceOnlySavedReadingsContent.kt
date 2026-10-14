package com.adsamcik.tracker.statistics.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.SourceHistoryDetailState
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Actual source measurements without a fabricated Location trip or cross-run count. */
@Composable
internal fun SourceOnlySavedReadingsContent(detail: SourceHistoryDetailState.Loaded) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val entry = detail.selection.entry
        val from = when (entry) {
            is SourceAwareHistoryPageEntry.StepsOnly -> entry.history.startTime.raw
            is SourceAwareHistoryPageEntry.PressureOnly -> entry.history.startTime.raw
            else -> return
        }
        val to = when (entry) {
            is SourceAwareHistoryPageEntry.StepsOnly -> entry.history.endTime.raw
            is SourceAwareHistoryPageEntry.PressureOnly -> entry.history.endTime.raw
            else -> return
        }
        val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
        Text(if (from == to) format.format(Instant.ofEpochMilli(from)) else
            "${format.format(Instant.ofEpochMilli(from))} → ${format.format(Instant.ofEpochMilli(to))}",
            style = MaterialTheme.typography.bodyMedium)
        when (entry) {
            is SourceAwareHistoryPageEntry.StepsOnly -> detail.members.forEach { member ->
                val steps = (member.session as? SessionHistoryQuery.Found)?.history?.steps ?: return@forEach
                MetricCard(label = stringResource(R.string.trip_detail_steps),
                    value = steps.count?.toString() ?: stringResource(R.string.history_value_unavailable),
                    supportingText = if (steps.hasCompleteValue) null else stringResource(R.string.history_value_partial))
            }
            is SourceAwareHistoryPageEntry.PressureOnly -> TripDetailPressureCard(entry.history.pressure)
            else -> Unit
        }
    }
}
