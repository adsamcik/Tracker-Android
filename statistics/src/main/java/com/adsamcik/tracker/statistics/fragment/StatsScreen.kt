package com.adsamcik.tracker.statistics.fragment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.paging.compose.LazyPagingItems
import com.adsamcik.tracker.shared.base.data.TrackerSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.statistics.R

/**
 * Refresh state for statistics route. Mirrors the test expectations.
 */
sealed interface RefreshUiState {
    data object Loading : RefreshUiState
    data object Empty : RefreshUiState
    data object Error : RefreshUiState
    data object Content : RefreshUiState
}

/**
 * Append pagination state for statistics route. Mirrors the test expectations.
 */
sealed interface AppendUiState {
    data object NotLoading : AppendUiState
    data object Loading : AppendUiState
    data object Error : AppendUiState
}

/** Test host expects this signature. */
@Composable
fun StatsScreen(
    refreshState: RefreshUiState,
    appendState: AppendUiState,
    onRetry: () -> Unit,
    onShowSummary: () -> Unit,
    onShowWeek: () -> Unit,
    onOpenWifi: () -> Unit,
    // Optional paging sessions supplied by route; tests omit it and rely on placeholders.
    sessions: LazyPagingItems<TrackerSession>? = null,
) {
    Surface(Modifier.fillMaxSize()) {
        when (refreshState) {
            RefreshUiState.Loading -> LoadingState()
            RefreshUiState.Empty -> EmptyState()
            RefreshUiState.Error -> ErrorState(onRetry)
            RefreshUiState.Content -> ContentState(appendState, onRetry, onShowSummary, onShowWeek, onOpenWifi, sessions)
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = stringResource(R.string.stats_no_tracker_sessions))
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        // Using module-specific generic error string
        Text(text = stringResource(R.string.stats_error_generic), modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun ContentState(
    appendState: AppendUiState,
    onRetry: () -> Unit,
    onShowSummary: () -> Unit,
    onShowWeek: () -> Unit,
    onOpenWifi: () -> Unit,
    pagingItems: LazyPagingItems<TrackerSession>? = null,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { HeaderActions(onShowSummary, onShowWeek, onOpenWifi) }
        if (pagingItems != null) {
            val count = pagingItems.itemCount
            items(count) { index ->
                val session = pagingItems[index]
                if (session != null) SessionRow(session)
            }
        } else {
            items(5) { index -> SessionRow(TrackerSession(id = index.toLong(), start = 0, end = 0)) }
        }
        // Footer append UI state inline
        item(key = "append_state_footer") {
            AppendStateSection(appendState, onRetry)
        }
    }
}

@Composable
private fun AppendStateSection(state: AppendUiState, onRetry: () -> Unit) {
    when (state) {
        AppendUiState.Loading -> Column(Modifier.fillMaxWidth()) { repeat(3) { PlaceholderRow(it) } }
        AppendUiState.Error -> AppendErrorRow(onRetry)
        AppendUiState.NotLoading -> Unit
    }
}

@Composable
private fun HeaderActions(onShowSummary: () -> Unit, onShowWeek: () -> Unit, onOpenWifi: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        val summaryLabel = stringResource(R.string.stats_sum_title)
        val weekLabel = stringResource(R.string.stats_weekly_title)
        val wifiLabel = stringResource(R.string.stats_wifi_label)
        IconButton(
            onClick = onShowSummary,
            modifier = Modifier.semantics { contentDescription = summaryLabel }
        ) { Icon(Icons.Filled.Summarize, contentDescription = null) }
        IconButton(
            onClick = onShowWeek,
            modifier = Modifier.semantics { contentDescription = weekLabel }
        ) { Icon(Icons.Filled.DateRange, contentDescription = null) }
        IconButton(
            onClick = onOpenWifi,
            modifier = Modifier.semantics { contentDescription = wifiLabel }
        ) { Icon(Icons.Filled.Wifi, contentDescription = null) }
    }
}

private val sessionDateFormatter: DateTimeFormatter by lazy {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}

@Composable
internal fun SessionRow(session: TrackerSession) {
    val durationMinutes = ((if (session.end > session.start && session.end != 0L) session.end - session.start else 0L) / 60000L).toInt()
    val dateText = if (session.start != 0L) sessionDateFormatter.format(Instant.ofEpochMilli(session.start).atZone(ZoneId.systemDefault())) else "--"
    val steps = session.steps
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(min = 68.dp)
            .testTag("stats_session_row"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text = stringResource(R.string.stats_session_header, session.id), style = MaterialTheme.typography.titleMedium)
            val meta = buildString {
                append(dateText)
                if (durationMinutes > 0) {
                    append("  •  ")
                    append(durationMinutes)
                    append("m")
                }
                if (steps > 0) {
                    append("  •  ")
                    append(steps)
                    append(" steps")
                }
            }.ifBlank { stringResource(R.string.stats_session_subtitle_placeholder) }
            Text(text = meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlaceholderRow(index: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp)
            .testTag("stats_placeholder_$index"),
        contentAlignment = Alignment.CenterStart
    ) {
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)) {}
    }
}

@Composable
private fun AppendErrorRow(onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("stats_append_error"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = stringResource(R.string.stats_append_error), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}
