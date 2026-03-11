package com.adsamcik.tracker.statistics.wifi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.base.extension.formatAsShortDateTime
import com.adsamcik.tracker.shared.utils.style.compose.AppColors
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.statistics.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wi‑Fi list browser in Compose (legacy ManageActivity version removed).
 * Header + summary row + data rows with optional filter dialog.
 * Redesigned with Outdoor Modern aesthetic.
 */
class WifiBrowseActivityCompose : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        title = getString(R.string.wifilist_title)
        setContent { 
            // Use AppTheme to respect system settings (light/dark)
            AppTheme { 
                WifiBrowseRoute() 
            } 
        }
    }
}

private data class WifiFilter(
    val bssid: String? = null,
    val ssid: String? = null,
    val capabilities: String? = null,
    val frequency: String? = null,
    val count: Long = DEFAULT_LIMIT
)

private const val DEFAULT_LIMIT = 1000L

@Composable
private fun WifiBrowseRoute() {
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<WifiObservation>() }
    var filter by remember { mutableStateOf(WifiFilter()) }
    var showDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // initial load
    LaunchedEffect(filter, context) {
        scope.launch(Dispatchers.Default) {
            val dao = AppDatabase.database(context).wifiObservationDao()
            val list = withContext(Dispatchers.IO) {
                dao.getAllBetween(0L, Long.MAX_VALUE)
            }
            val filtered = applyFilter(list, filter)
            withContext(Dispatchers.Main) {
                items.clear(); items.addAll(filtered.sortedBy { it.bssid })
            }
        }
    }

    WifiBrowseScreen(
        items = items,
        filter = filter,
        onOpenFilter = { showDialog = true }
    )

    if (showDialog) {
        WifiFilterDialog(
            current = filter,
            onDismiss = { showDialog = false },
            onApply = { newFilter ->
                filter = newFilter; showDialog = false
            }
        )
    }
}

private fun needsFiltering(f: WifiFilter): Boolean =
    !f.bssid.isNullOrBlank() || !f.ssid.isNullOrBlank() || !f.capabilities.isNullOrBlank() || !f.frequency.isNullOrBlank()

private fun applyFilter(list: List<WifiObservation>, f: WifiFilter): List<WifiObservation> {
    var result = list
    if (!f.bssid.isNullOrBlank()) result = result.filter { it.bssid.contains(f.bssid, ignoreCase = true) }
    if (!f.ssid.isNullOrBlank()) result = result.filter { it.ssid.contains(f.ssid, ignoreCase = true) }
    if (!f.capabilities.isNullOrBlank()) result = result.filter { it.capabilities.contains(f.capabilities, ignoreCase = true) }
    if (!f.frequency.isNullOrBlank()) {
        val freqPrefix = f.frequency
        result = result.filter { it.frequency.toString().startsWith(freqPrefix) }
    }
    return result.take(f.count.toInt())
}

private fun buildWhere(f: WifiFilter): Pair<String, Array<String>> {
    val conditions = mutableListOf<String>()
    val args = mutableListOf<String>()
    if (!f.bssid.isNullOrBlank()) { conditions += "bssid LIKE ?"; args += "%${f.bssid}%" }
    if (!f.ssid.isNullOrBlank()) { conditions += "ssid LIKE ?"; args += "%${f.ssid}%" }
    if (!f.capabilities.isNullOrBlank()) { conditions += "capabilities LIKE ?"; args += "%${f.capabilities}%" }
    if (!f.frequency.isNullOrBlank()) { conditions += "frequency LIKE ?"; args += "${f.frequency}%" }
    return conditions.joinToString(" AND ") to args.toTypedArray()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WifiBrowseScreen(
    items: List<WifiObservation>,
    filter: WifiFilter,
    onOpenFilter: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onOpenFilter,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.FilterList, contentDescription = stringResource(id = R.string.wifilist_title))
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SummaryRow(count = items.size)
                HeaderRow()
                
                // We use a simple loop instead of LazyColumn for now as it's inside a ScrollView 
                // (legacy structure from XML days, but maybe should be LazyColumn if really large. 
                // However, user scrolling horizontally breaks LazyColumn often without careful setup. 
                // Keeping verticalScroll for simplicity as items are limited by default query limit).
                items.forEach { WifiItemRow(it) }
                
                Spacer(modifier = Modifier.height(72.dp)) // space for FAB
            }
        }
    }
}

@Composable
private fun SummaryRow(count: Int) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.wifilist_count, count),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun HeaderRow() {
    // Header should be visible and high contrast
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 8.dp)) {
        WifiTableRow(
            bssid = stringResource(R.string.wifilist_title_bssid),
            ssid = stringResource(R.string.wifilist_title_ssid),
            capabilities = stringResource(R.string.wifilist_title_capabilities),
            frequency = stringResource(R.string.wifilist_title_frequency),
            firstSeen = stringResource(R.string.wifilist_title_first_seen),
            lastSeen = stringResource(R.string.wifilist_title_last_seen),
            header = true
        )
    }
}

@Composable
private fun WifiItemRow(item: WifiObservation) {
    Column {
        WifiTableRow(
            bssid = item.bssid,
            ssid = item.ssid,
            capabilities = item.capabilities,
            frequency = stringResource(R.string.wifilist_item_frequency, item.frequency),
            firstSeen = item.timeMs.formatAsShortDateTime(),
            lastSeen = item.timeMs.formatAsShortDateTime(),
            header = false
        )
        // Add a separator
        Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun WifiTableRow(
    bssid: String,
    ssid: String,
    capabilities: String,
    frequency: String,
    firstSeen: String,
    lastSeen: String,
    header: Boolean
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 8.dp, vertical = 12.dp)

    Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ColumnCell(bssid, header)
        ColumnCell(ssid, header)
        ColumnCell(capabilities, header, weight = 2f)
        ColumnCell(frequency, header)
        ColumnCell(firstSeen, header)
        ColumnCell(lastSeen, header)
    }
}

@Composable
private fun ColumnCell(text: String, header: Boolean, weight: Float = 1f) {
    Column(Modifier.width(100.dp)) { // Fixed width for columns since we scroll horizontally
        Text(
            text = text,
            style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
            color = if (header) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WifiFilterDialog(
    current: WifiFilter,
    onDismiss: () -> Unit,
    onApply: (WifiFilter) -> Unit
) {
    var bssid by remember { mutableStateOf(current.bssid.orEmpty()) }
    var ssid by remember { mutableStateOf(current.ssid.orEmpty()) }
    var capabilities by remember { mutableStateOf(current.capabilities.orEmpty()) }
    var frequency by remember { mutableStateOf(current.frequency.orEmpty()) }
    var count by remember { mutableStateOf(if (current.count == DEFAULT_LIMIT) "" else current.count.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface, // Use theme surface (likely dark)
        confirmButton = {
            TextButton(onClick = {
                val parsedCount = count.toLongOrNull() ?: DEFAULT_LIMIT
                onApply(
                    WifiFilter(
                        bssid = bssid.ifBlank { null },
                        ssid = ssid.ifBlank { null },
                        capabilities = capabilities.ifBlank { null },
                        frequency = frequency.ifBlank { null },
                        count = parsedCount
                    )
                )
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        title = { Text(stringResource(R.string.wifilist_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FilterField(value = bssid, onValueChange = { bssid = it }, label = stringResource(R.string.wifilist_filter_bssid))
                FilterField(value = ssid, onValueChange = { ssid = it }, label = stringResource(R.string.wifilist_filter_ssid))
                FilterField(value = capabilities, onValueChange = { capabilities = it }, label = stringResource(R.string.wifilist_filter_capabilities))
                FilterField(value = frequency, onValueChange = { frequency = it }, label = stringResource(R.string.wifilist_filter_frequency))
                FilterField(value = count, onValueChange = { count = it }, label = stringResource(R.string.wifilist_filter_count))
            }
        }
    )
}

@Composable
private fun FilterField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        singleLine = true
    )
}
