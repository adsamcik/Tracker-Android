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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Wifi
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
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.StatsPresenterViewModel
import com.adsamcik.tracker.statistics.viewmodel.WifiStatsLoadState
import java.util.Locale

/**
 * Full-screen Wi-Fi statistics view. Replaces the prior modal dialog so the
 * three metric rows have proper breathing room and the user can use the
 * device's BACK button as a real navigation action rather than a dialog
 * dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiStatsRoute(
    onBack: () -> Unit,
) {
    val vm: StatsPresenterViewModel = hiltViewModel()
    val state by vm.wifiStatsState.collectAsState()

    LaunchedEffect(Unit) {
        vm.loadWifiStats()
    }

    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("wifiStatsRoute"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_wifi_dialog_title)) },
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
                WifiStatsLoadState.Idle, WifiStatsLoadState.Loading -> WifiStatsLoading()
                WifiStatsLoadState.Empty -> WifiStatsEmpty()
                is WifiStatsLoadState.Error -> WifiStatsError(s.message)
                is WifiStatsLoadState.Success -> WifiStatsContent(
                    uniqueNetworks = s.summary.uniqueNetworks,
                    totalScans = s.summary.totalScans,
                    averageNetworksPerScan = s.summary.averageNetworksPerScan,
                )
            }
        }
    }
}

@Composable
private fun WifiStatsLoading() {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.stats_wifi_dialog_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WifiStatsEmpty() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Filled.Wifi,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        )
        Text(
            text = stringResource(R.string.stats_wifi_dialog_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.stats_wifi_dialog_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun WifiStatsError(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Filled.Wifi,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
        )
        Text(
            text = stringResource(R.string.stats_wifi_dialog_error),
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
private fun WifiStatsContent(
    uniqueNetworks: Long,
    totalScans: Long,
    averageNetworksPerScan: Double,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        WifiBigMetric(
            label = stringResource(R.string.stats_wifi_unique_networks),
            value = uniqueNetworks.formatReadable(),
        )
        HorizontalDivider()
        WifiBigMetric(
            label = stringResource(R.string.stats_wifi_total_scans),
            value = totalScans.formatReadable(),
        )
        HorizontalDivider()
        WifiBigMetric(
            label = stringResource(R.string.stats_wifi_average_per_scan),
            value = String.format(Locale.getDefault(), "%.1f", averageNetworksPerScan),
        )
    }
}

@Composable
private fun WifiBigMetric(label: String, value: String) {
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
