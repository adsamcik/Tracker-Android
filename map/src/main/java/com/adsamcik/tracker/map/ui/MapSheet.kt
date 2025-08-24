package com.adsamcik.tracker.map.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.map.layers.registry.LayerRegistry
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.udf.MapEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSheet(
    registry: LayerRegistry,
    store: MapStore,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by store.state.collectAsState()
    val uiState = state
    val isSheetVisible = uiState.sheet.isVisible

    var showErrorMessage by remember { mutableStateOf<String?>(null) }
    var showDateRangeDialog by remember { mutableStateOf(false) }

    if (isSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { store.dispatch(MapEvent.HideSheet) },
            sheetState = sheetState,
            modifier = modifier
        ) {
            val layers = remember { registry.getAllLayers() }
            val context = LocalContext.current

            Column(modifier = Modifier.fillMaxWidth()) {
                // Search bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = uiState.search.query,
                        onValueChange = { q -> store.dispatch(MapEvent.UpdateSearchQuery(q)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        label = { Text("Search") }
                    )
                    Button(onClick = { store.dispatch(MapEvent.SubmitSearch) }) {
                        Text("Go")
                    }
                }

                // Header with controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Map Controls",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    // My Location button
                    IconButton(onClick = { store.dispatch(MapEvent.ToggleFollow) }) {
                        val enabled = uiState.isFollowing
                        val tint = if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Icon(
                            Icons.Filled.MyLocation,
                            contentDescription = "Toggle follow location",
                            tint = tint
                        )
                    }
                }

                // Error message display
                showErrorMessage?.let { message ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                // Filters
                Text(
                    text = "Filters",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium
                )
                // Quality slider
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Quality", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(12.dp))
                    Slider(
                        value = uiState.quality,
                        onValueChange = { v -> store.setQuality(v) },
                        valueRange = 0.25f..2.0f,
                        steps = 7,
                        modifier = Modifier.weight(1f)
                    )
                }
                // Date range picker launcher
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val rangeText = remember(uiState.dateRange) {
                        val start = uiState.dateRange.first
                        val end = uiState.dateRange.last
                        if (start == 0L && end == Long.MAX_VALUE) "All time" else {
                            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                            "${fmt.format(java.util.Date(start))} to ${fmt.format(java.util.Date(end))}"
                        }
                    }
                    Text(rangeText, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { showDateRangeDialog = true }) { Text("Date range") }
                }

                if (showDateRangeDialog) {
                    val pickerState = rememberDateRangePickerState()
                    DatePickerDialog(
                        onDismissRequest = { showDateRangeDialog = false },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val start = pickerState.selectedStartDateMillis
                                    val end = pickerState.selectedEndDateMillis
                                    if (start != null && end != null) {
                                        // include full end day by extending to end-of-day - 1ms
                                        val endInclusive = end + (24L * 60 * 60 * 1000) - 1L
                                        store.setDateRange(start..endInclusive)
                                        showDateRangeDialog = false
                                    }
                                },
                                enabled = pickerState.selectedStartDateMillis != null && pickerState.selectedEndDateMillis != null
                            ) { Text("OK") }
                        },
                        dismissButton = { TextButton(onClick = { showDateRangeDialog = false }) { Text("Cancel") } }
                    ) {
                        DateRangePicker(state = pickerState)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Map Layers",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.titleMedium
                )

                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(layers, key = { it.id }) { layer ->
                        val isSelected = uiState.activeLayerIds.contains(layer.id)

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    try {
                                        store.dispatch(MapEvent.SelectLayer(layer.id))
                                        showErrorMessage = null
                                    } catch (e: Exception) {
                                        showErrorMessage = "Failed to load layer: ${e.message}"
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            )
                        ) {
                            Text(
                                text = try {
                                    context.getString(layer.titleRes)
                                } catch (e: Exception) {
                                    layer.id
                                },
                                modifier = Modifier.padding(16.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }

                // Legend display
                if (uiState.legend.isNotEmpty()) {
                    Text(
                        text = "Legend",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium
                    )

                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(uiState.legend) { legendItem ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(Color(legendItem.color), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = legendItem.label.ifEmpty { "Unknown" },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                // Tile generation progress
                if (uiState.tileGenerationInProgress > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Generating ${uiState.tileGenerationInProgress} tiles...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
