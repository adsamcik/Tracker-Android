package com.adsamcik.tracker.statistics.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.presenter.StatsPresenterViewModel
import com.adsamcik.tracker.statistics.viewmodel.StatsLoadState

/**
 * Full-screen summary view that lists overall tracking stats (time, distance,
 * counts, …). Replaces the prior modal dialog: a full route gives the rows
 * room to breathe, lets the device's BACK button work as a real navigation
 * action, supports up-button + deep linking, and frees the dialog's modal
 * scrim from blocking the rest of the Stats screen during the (often-slow)
 * aggregate query.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryRoute(
    onBack: () -> Unit,
) {
    val vm: StatsPresenterViewModel = hiltViewModel()
    val state by vm.summaryStatsState.collectAsState()

    LaunchedEffect(Unit) {
        // Each visit re-aggregates so the user always sees fresh totals even
        // when sessions are added since the last open. Cheap query; safe.
        vm.loadSummaryStats()
    }

    SummaryRouteContent(state = state, onBack = onBack)
}

// internal — extracted for compose tests (Hilt-backed entry composable cannot be
// invoked from a unit test without a Hilt graph, so the stateless body lives here).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SummaryRouteContent(
    state: StatsLoadState,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("summaryRoute"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_sum_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_navigate_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is StatsLoadState.Idle, is StatsLoadState.Loading -> SummaryLoading()
                is StatsLoadState.Success -> if (s.stats.isEmpty()) SummaryEmpty() else SummaryList(s.stats)
                is StatsLoadState.Error -> SummaryError(s.message)
            }
        }
    }
}

@Composable
private fun SummaryLoading() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.stats_summary_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryEmpty() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Outlined.Assessment,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        )
        Text(
            text = stringResource(R.string.stats_summary_empty),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.stats_summary_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun SummaryError(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Outlined.Assessment,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
        )
        Text(
            text = stringResource(R.string.stats_summary_error),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun SummaryList(stats: List<Stat>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 24.dp,
            vertical = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = stats, key = { it.nameRes }) { stat ->
            SummaryRow(stat)
        }
    }
}

@Composable
private fun SummaryRow(stat: Stat) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = stat.iconRes),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(stat.nameRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stat.data.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
