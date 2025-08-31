package com.adsamcik.tracker.map.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.map.layers.registry.LayerRegistry
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.SheetVisibility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSheet(
    registry: LayerRegistry,
    store: MapStore,
    modifier: Modifier = Modifier,
    bottomInsetPx: Int = 0,
    onBottomPaddingChanged: (Int) -> Unit = {}
) {
    val state by store.state.collectAsState()
    val uiState = state
    val visibility = uiState.sheet.visibility

    val bottomSheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = false,
        confirmValueChange = { true }
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = bottomSheetState)

    val context = LocalContext.current
    val layers = remember(registry) { registry.getAllLayers() }
    var showErrorMessage by remember { mutableStateOf<String?>(null) }
    var showDateRangeDialog by remember { mutableStateOf(false) }
    var searchRowHeightPx by remember { mutableStateOf(0) }
    // Track keyboard visibility via ime bottom inset
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val navBottom = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    val systemBottom = maxOf(imeBottom, navBottom)
    val effectiveBottomInsetPx = maxOf(bottomInsetPx, systemBottom)

    // Drive the bottom sheet to target state based on visibility in store
    LaunchedEffect(visibility) {
        when (visibility) {
            SheetVisibility.Hidden -> bottomSheetState.hide()
            SheetVisibility.Peek -> bottomSheetState.partialExpand()
            SheetVisibility.Expanded -> bottomSheetState.expand()
        }
    }

    // Reflect user drag changes back to store
    LaunchedEffect(bottomSheetState) {
        snapshotFlow { bottomSheetState.currentValue }
            .collect { cur ->
                val vis = when (cur) {
                    SheetValue.Hidden -> SheetVisibility.Hidden
                    SheetValue.PartiallyExpanded -> SheetVisibility.Peek
                    SheetValue.Expanded -> SheetVisibility.Expanded
                }
                if (vis != visibility) {
                    store.dispatch(MapEvent.SetSheet(vis))
                }
            }
    }

    // Reveal handle when Hidden, respecting navigation bar (inset passed from host)
    if (visibility == SheetVisibility.Hidden) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val insetDp = with(density) { effectiveBottomInsetPx.toDp() }
        val navPadding = if (insetDp < 16.dp) 16.dp else insetDp
        Box(modifier = modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navPadding + 12.dp)
                    .clickable { store.dispatch(MapEvent.SetSheet(SheetVisibility.Peek)) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Search", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        return
    }

    // Sheet visible (Peek/Expanded): compute peek height from inset
    val density2 = androidx.compose.ui.platform.LocalDensity.current
    val insetDp2 = with(density2) { effectiveBottomInsetPx.toDp() }
    val safeInset = if (insetDp2 < 16.dp) 16.dp else insetDp2
    val measuredRowHeight = if (searchRowHeightPx > 0) with(density2) { searchRowHeightPx.toDp() } else 56.dp + 24.dp
    val peekHeight = measuredRowHeight + 8.dp + safeInset

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = scaffoldState,
        sheetPeekHeight = peekHeight,
        // Use default drag handle for familiarity
        topBar = {},
        sheetContent = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Search row (always visible)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .onSizeChanged { searchRowHeightPx = it.height },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = uiState.search.query,
                        onValueChange = { q -> store.dispatch(MapEvent.UpdateSearchQuery(q)) },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { f ->
                                if (f.isFocused != uiState.search.hasFocus) {
                                    store.dispatch(MapEvent.SetSearchFocus(f.isFocused))
                                }
                            },
                        singleLine = true,
                        placeholder = { Text("Search...") },
                        shape = CircleShape,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                tonalElevation = 1.dp
                            ) {
                                IconButton(onClick = { store.dispatch(MapEvent.SubmitSearch) }) {
                                    Icon(Icons.Filled.Search, contentDescription = "Search")
                                }
                            }
                        }
                    )

                    // Hide extra icons when keyboard open (legacy parity)
                    if (imeBottom == 0) {
                        // Date range icon
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)) {
                            IconButton(onClick = { showDateRangeDialog = true }) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Filled.DateRange,
                                    contentDescription = "Date range"
                                )
                            }
                        }

                        // Locate icon
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)) {
                            IconButton(onClick = { store.dispatch(MapEvent.ToggleFollow) }) {
                                Icon(Icons.Filled.MyLocation, contentDescription = "My location")
                            }
                        }
                    }
                }

                if (visibility == SheetVisibility.Expanded) {
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { store.dispatch(MapEvent.ToggleFollow) }) {
                                val enabled = uiState.isFollowing
                                val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                Icon(
                                    Icons.Filled.MyLocation,
                                    contentDescription = "Toggle follow location",
                                    tint = tint
                                )
                            }
                            // Hide sheet action
                            IconButton(onClick = { store.dispatch(MapEvent.HideSheet) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Hide")
                            }
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
        },
        content = { /* no-op: map below handles interactions */ }
    )

    // Auto-expand when user focuses search or keyboard is visible; auto-peek when keyboard hides and not manually expanded
    LaunchedEffect(uiState.search.hasFocus, imeBottom) {
        if (uiState.search.hasFocus || imeBottom > 0) {
            bottomSheetState.expand()
        } else if (visibility == SheetVisibility.Expanded) {
            // Return to peek when keyboard closes and input not focused
            bottomSheetState.partialExpand()
        }
    }

    // Report current bottom padding for the map (visible sheet height)
    LaunchedEffect(bottomSheetState, peekHeight, effectiveBottomInsetPx) {
        snapshotFlow { bottomSheetState.requireOffset() }
            .collect { offsetPx ->
                // BottomSheetScaffold places the sheet from the bottom. Visible height = sheetHeight - offset.
                // We approximate sheetHeight by using the layout’s height minus the offset to bottom.
                // However, requireOffset() returns the distance from expanded top to current. When expanded, offset=0.
                // For Map padding, use the sheet’s current visible height above the bottom system inset.
                val peekPx = with(density2) { peekHeight.toPx() }
                val visiblePx = when (bottomSheetState.currentValue) {
                    SheetValue.Hidden -> 0f
                    SheetValue.PartiallyExpanded -> peekPx
                    SheetValue.Expanded -> kotlin.math.max(peekPx, peekPx + (0f - offsetPx)) // expanded covers more
                }
                // Add bottom inset so map content clears both sheet and nav/gesture area
                val totalBottom = (visiblePx + effectiveBottomInsetPx).toInt().coerceAtLeast(0)
                onBottomPaddingChanged(totalBottom)
            }
    }
}
