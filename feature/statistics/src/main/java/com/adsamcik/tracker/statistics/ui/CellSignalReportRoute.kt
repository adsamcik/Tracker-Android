package com.adsamcik.tracker.statistics.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.shared.base.data.CellType
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.StatsPresenterViewModel
import com.adsamcik.tracker.stats.api.repository.CellSignalReport
import com.adsamcik.tracker.stats.api.repository.CellTowerStat
import com.adsamcik.tracker.stats.api.repository.NetworkTypeSignalStat
import com.adsamcik.tracker.statistics.viewmodel.CellSignalReportLoadState
import java.util.Locale

/**
 * Full-screen cell-signal report: overview totals, a per-technology breakdown (share of samples and
 * normalized signal quality) and the most-frequently-observed towers. Mirrors [WifiStatsRoute].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellSignalReportRoute(
    onBack: () -> Unit,
) {
    val vm: StatsPresenterViewModel = hiltViewModel()
    val state by vm.cellSignalReportState.collectAsState()

    LaunchedEffect(Unit) {
        vm.loadCellSignalReport()
    }

    CellSignalReportRouteContent(state = state, onBack = onBack)
}

// internal — extracted for compose tests (the Hilt-backed entry composable cannot be invoked from a
// unit test without a Hilt graph, so the stateless body lives here).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CellSignalReportRouteContent(
    state: CellSignalReportLoadState,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("cellSignalReportRoute"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_cell_signal_dialog_title)) },
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
                CellSignalReportLoadState.Idle,
                CellSignalReportLoadState.Loading -> CellSignalLoading()
                CellSignalReportLoadState.Empty -> CellSignalEmpty()
                is CellSignalReportLoadState.Error -> CellSignalError(s.message)
                is CellSignalReportLoadState.Success -> CellSignalContent(s.report)
            }
        }
    }
}

@Composable
private fun CellSignalLoading() {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.stats_cell_signal_dialog_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CellSignalEmpty() {
    CellSignalMessage(
        title = stringResource(R.string.stats_cell_signal_dialog_empty_title),
        subtitle = stringResource(R.string.stats_cell_signal_dialog_empty_subtitle),
        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        titleColor = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun CellSignalError(message: String) {
    CellSignalMessage(
        title = stringResource(R.string.stats_cell_signal_dialog_error),
        subtitle = message,
        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
        titleColor = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun CellSignalMessage(
    title: String,
    subtitle: String,
    tint: androidx.compose.ui.graphics.Color,
    titleColor: androidx.compose.ui.graphics.Color,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Filled.SignalCellularAlt,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = tint,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = titleColor,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun CellSignalContent(report: CellSignalReport) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        CellBigMetric(
            label = stringResource(R.string.stats_cell_signal_total_samples),
            value = report.totalSamples.formatReadable(),
        )
        HorizontalDivider()
        CellBigMetric(
            label = stringResource(R.string.stats_cell_signal_distinct_towers),
            value = report.distinctTowers.formatReadable(),
        )

        if (report.networkTypes.isNotEmpty()) {
            HorizontalDivider()
            CellSectionTitle(stringResource(R.string.stats_cell_signal_section_networks))
            report.networkTypes.forEach { NetworkTypeRow(it) }
        }

        if (report.topTowers.isNotEmpty()) {
            HorizontalDivider()
            CellSectionTitle(stringResource(R.string.stats_cell_signal_section_towers))
            report.topTowers.forEach { TowerRow(it) }
        }
    }
}

@Composable
private fun CellSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun NetworkTypeRow(stat: NetworkTypeSignalStat) {
    val name = stringResource(cellTypeNameRes(stat.networkType))
    val secondary = stringResource(
        R.string.stats_cell_signal_network_secondary,
        percent(stat.sharePercent),
        stat.distinctCells.formatReadable(),
    )
    CellDetailRow(primary = name, secondary = secondary, value = percent(stat.avgQualityPercent))
}

@Composable
private fun TowerRow(stat: CellTowerStat) {
    val carrier = stringResource(
        R.string.stats_cell_signal_carrier,
        stat.mcc.toString(),
        stat.mnc.toString(),
    )
    val detail = stringResource(
        R.string.stats_cell_signal_tower_detail,
        stat.cellId.toString(),
        stat.sampleCount.formatReadable(),
    )
    CellDetailRow(primary = carrier, secondary = detail, value = percent(stat.avgQualityPercent))
}

@Composable
private fun CellDetailRow(primary: String, secondary: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CellBigMetric(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun cellTypeNameRes(networkType: Int): Int =
    (CellType.entries.getOrNull(networkType) ?: CellType.Unknown).nameRes

private fun percent(value: Double): String =
    String.format(Locale.getDefault(), "%.0f%%", value)
