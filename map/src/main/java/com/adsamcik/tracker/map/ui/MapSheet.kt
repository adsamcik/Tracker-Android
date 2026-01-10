package com.adsamcik.tracker.map.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.layers.registry.LayerRegistry
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.SheetVisibility
import com.adsamcik.tracker.shared.map.layers.LayerDescriptor

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
    val effectiveBottomInsetPx = bottomInsetPx + systemBottom

    // Drive the bottom sheet to target state based on visibility in store
    LaunchedEffect(visibility) {
        when (visibility) {
            SheetVisibility.Hidden -> bottomSheetState.hide()
            SheetVisibility.Peek -> bottomSheetState.partialExpand()
            SheetVisibility.Expanded -> bottomSheetState.expand()
        }
    }

    // Reflect user drag changes back to store (close to hidden if dragged down)
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

    // Compute layout
    val density = LocalDensity.current
    
    // We want the search bar to float above the nav bar / bottom inset.
    val searchBarHeightDp = with(density) { searchRowHeightPx.toDp() }
    val bottomInsetDp = with(density) { effectiveBottomInsetPx.toDp() }
    
    // Peek height: 0 because we handle the "Peek" UI (Search Bar) outside the sheet.
    // The sheet only contains the expanded content (Filters, Layers).
    // IMPORTANT: If peekHeight is 0, user cannot drag it up easily. 
    // We need a way to trigger expand. The Search Bar click or Layers button does that.
    val peekHeight = 0.dp

    Box(modifier = modifier.fillMaxSize()) {
        BottomSheetScaffold(
            modifier = Modifier.fillMaxSize(),
            scaffoldState = scaffoldState,
            sheetPeekHeight = peekHeight,
            topBar = {},
            sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            sheetContent = {
                // Content only visible when expanded
                // Use safe content padding so last items are not hidden behind floating search bar
                val safeBottomPadding = if (searchRowHeightPx > 0) {
                     searchBarHeightDp + bottomInsetDp + 16.dp 
                } else {
                     88.dp + bottomInsetDp // Fallback approximate
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    contentPadding = PaddingValues(
                        top = 16.dp, 
                        bottom = safeBottomPadding, 
                        start = 16.dp, 
                        end = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // --- Header & Error ---
                    if (showErrorMessage != null) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Text(
                                    text = showErrorMessage ?: "",
                                    modifier = Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    // --- Filters Section ---
                    item {
                        Text(
                            text = "Filters",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // Quality Slider
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Quality", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "%.2fx".format(uiState.quality), 
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Low", style = MaterialTheme.typography.labelSmall)
                                Slider(
                                    value = uiState.quality,
                                    onValueChange = { v -> store.setQuality(v) },
                                    valueRange = 0.25f..2.0f,
                                    steps = 6,
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                )
                                Text("High", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // Date Range
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("All time", style = MaterialTheme.typography.titleMedium)
                                val rangeText = remember(uiState.dateRange) {
                                    val start = uiState.dateRange.first
                                    val end = uiState.dateRange.last
                                    if (start == 0L && end == Long.MAX_VALUE) null else {
                                        val fmt = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                                        "${fmt.format(java.util.Date(start))} - ${fmt.format(java.util.Date(end))}"
                                    }
                                }
                                if (rangeText != null) {
                                     Text(rangeText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            
                            Button(
                                onClick = { showDateRangeDialog = true },
                                contentPadding = PaddingValues(horizontal = 24.dp)
                            ) {
                                Text(stringResource(R.string.map_date_range_button))
                            }
                        }
                    }

                    // --- Layers Section ---
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Map Layers",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    items(layers, key = { it.id }) { layer ->
                        val isSelected = uiState.activeLayerIds.contains(layer.id)
                        MapLayerCard(
                            layer = layer,
                            isSelected = isSelected,
                            onSelect = {
                                try {
                                    store.dispatch(MapEvent.SelectLayer(layer.id))
                                    showErrorMessage = null
                                } catch (e: Exception) {
                                    showErrorMessage = "Failed to load layer: ${e.message}"
                                }
                            }
                        )
                    }
                }
            },
            content = { }
        )
        
        // Floating Search Bar & Controls (Outside Sheet)
        // Positioned at BottomCenter, respecting inset
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = (effectiveBottomInsetPx.takeIf { it > 0 }?.let { with(density) { it.toDp() } } ?: 0.dp) + 16.dp)
                .padding(horizontal = 16.dp)
                .onSizeChanged { searchRowHeightPx = it.height }
        ) {
            // Search Field - Frosted Glass Style
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(end = 64.dp) // Leave space for the vertical stack
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Row(
                   verticalAlignment = Alignment.CenterVertically,
                   modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Icon(
                        Icons.Filled.Search, 
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(modifier = Modifier.weight(1f)) {
                         TextField(
                            value = uiState.search.query,
                            onValueChange = { q -> store.dispatch(MapEvent.UpdateSearchQuery(q)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { f ->
                                    if (f.isFocused != uiState.search.hasFocus) {
                                        store.dispatch(MapEvent.SetSearchFocus(f.isFocused))
                                    }
                                },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.map_search_placeholder)) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            )
                        )
                    }
                    if (uiState.search.query.isNotEmpty()) {
                        IconButton(onClick = { store.dispatch(MapEvent.UpdateSearchQuery("")) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                }
            }
            
            // vertical stack of controls - Frosted Glass Style
            // Hide when sheet is expanded or keyboard is visible to prevent overlap
            if (imeBottom == 0 && visibility != SheetVisibility.Expanded) {
                 Column(
                     modifier = Modifier.align(Alignment.BottomEnd),
                     verticalArrangement = Arrangement.spacedBy(12.dp),
                     horizontalAlignment = Alignment.CenterHorizontally
                 ) {
                     // Layers
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                        tonalElevation = 2.dp,
                        shadowElevation = 4.dp,
                        modifier = Modifier.size(52.dp)
                    ) {
                        IconButton(onClick = { 
                            if (visibility == SheetVisibility.Expanded) {
                                store.dispatch(MapEvent.SetSheet(SheetVisibility.Peek))
                            } else {
                                store.dispatch(MapEvent.SetSheet(SheetVisibility.Expanded))
                            }
                        }) {
                            Icon(
                                Icons.Filled.Layers, 
                                contentDescription = "Layers",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Date
                     Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                        tonalElevation = 2.dp,
                        shadowElevation = 4.dp,
                        modifier = Modifier.size(52.dp)
                    ) {
                        IconButton(onClick = { showDateRangeDialog = true }) {
                            Icon(
                                Icons.Filled.DateRange, 
                                contentDescription = "Date",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    
                    // Location
                    val isFollowing = uiState.isFollowing
                     Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                        tonalElevation = 2.dp,
                        shadowElevation = 4.dp,
                        modifier = Modifier.size(52.dp)
                    ) {
                        IconButton(onClick = { store.dispatch(MapEvent.ToggleFollow) }) {
                            Icon(
                                Icons.Filled.MyLocation, 
                                contentDescription = stringResource(R.string.tips_map_my_location_title), 
                                tint = if (isFollowing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                 }
            }
        }
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
                ) { Text(stringResource(R.string.map_date_range_dialog_ok)) }
            },
            dismissButton = { TextButton(onClick = { showDateRangeDialog = false }) { Text(stringResource(R.string.map_date_range_dialog_cancel)) } }
        ) {
            DateRangePicker(state = pickerState)
        }
    }

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
    LaunchedEffect(bottomSheetState, peekHeight, effectiveBottomInsetPx, searchRowHeightPx) {
         val searchHeightPx = searchRowHeightPx
         val totalBottom = (effectiveBottomInsetPx + searchHeightPx + 48).coerceAtLeast(0) 
         onBottomPaddingChanged(totalBottom)
    }
}

@Composable
fun MapLayerCard(
    layer: LayerDescriptor,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val context = LocalContext.current
    
    // Resolve LayerEntry to get Legend data without fully building logic
    // We assume the factory returns LayerEntry for v2 layers
    val layerEntry = remember(layer) {
        try {
            layer.recipe.factory.create() as? LayerEntry
        } catch (e: Exception) {
            null
        }
    }
    
    val legend = layerEntry?.legend?.legend

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) // Softer background
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Icon
            Surface(
                shape = CircleShape,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = getLayerIcon(layer.id),
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp),
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                // Title
                Text(
                    text = try { context.getString(layer.titleRes) } catch (e: Exception) { layer.id },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                // Legend Preview / Description
                if (legend != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    if (legend.valueList.isNotEmpty()) {
                        // Show colorful legend strip
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                        ) {
                             legend.valueList.forEach { value ->
                                 Box(
                                     modifier = Modifier
                                         .weight(1f)
                                         .fillMaxHeight()
                                         .background(Color(value.color))
                                 )
                             }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val first = legend.valueList.firstOrNull()
                            val last = legend.valueList.lastOrNull()
                            if (first != null) {
                                Text(
                                    try { context.getString(first.nameRes) } catch(e:Exception){""}, 
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (last != null && last != first) {
                                Text(
                                     try { context.getString(last.nameRes) } catch(e:Exception){""},
                                     style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        val descriptionRes = legend.description
                        if (descriptionRes != null && descriptionRes > 0) {
                        // Text description fallback
                             Text(
                                 text = context.getString(descriptionRes),
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                                 maxLines = 2
                             )
                        }
                    }
                }
            }
            
            // Checkmark if selected?
            // Currently using background color which is sufficient
        }
    }
}

private fun getLayerIcon(layerId: String): ImageVector {
    return when (layerId) {
        "location_heatmap" -> Icons.Filled.LocationOn
        "cell_heatmap" -> Icons.Filled.CellTower
        "wifi_heatmap" -> Icons.Filled.Wifi
        "wifi_count_heatmap" -> Icons.Filled.Wifi
        "speed_heatmap" -> Icons.Filled.DirectionsRun
        "location_polyline" -> Icons.Filled.Timeline
        "none" -> Icons.Filled.LayersClear
        else -> Icons.Filled.Layers
    }
}
