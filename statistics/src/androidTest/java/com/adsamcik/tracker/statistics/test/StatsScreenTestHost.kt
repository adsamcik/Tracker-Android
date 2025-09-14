package com.adsamcik.tracker.statistics.test

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.statistics.fragment.AppendUiState
import com.adsamcik.tracker.statistics.fragment.RefreshUiState
import com.adsamcik.tracker.statistics.fragment.StatsScreen

/**
 * Test host for StatsScreen that provides controllable state and callback spies.
 * Used by UI tests to verify refresh/append states and user interactions.
 */
@Composable
internal fun StatsScreenTestHost(
    refreshState: RefreshUiState,
    appendState: AppendUiState,
    onRetry: () -> Unit,
    onShowSummary: () -> Unit,
    onShowWeek: () -> Unit,
    onOpenWifi: () -> Unit,
    includeSampleSessionRow: Boolean = false,
    onOpenDetails: (Long) -> Unit = {},
) {
    Column {
        StatsScreen(
            refreshState = refreshState,
            appendState = appendState,
            onRetry = onRetry,
            onShowSummary = onShowSummary,
            onShowWeek = onShowWeek,
            onOpenWifi = onOpenWifi,
        )
        
        // Add a sample session row for intent testing if requested
        if (includeSampleSessionRow) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("stats_session_row")
                    .clickable { onOpenDetails(1234L) }
                    .padding(16.dp)
            ) {
                Text(
                    text = "Sample Session",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
