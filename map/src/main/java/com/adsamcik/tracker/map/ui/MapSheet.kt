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
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
    snackbarHostState: SnackbarHostState,
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
    var expandedByKeyboard by remember { mutableStateOf(false) }
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

    // Show snackbar when error occurs (visible even when sheet is collapsed)
    LaunchedEffect(showErrorMessage) {
        showErrorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Compute layout
    val density = LocalDensity.current
    
    val bottomInsetDp = with(density) { effectiveBottomInsetPx.toDp() }
    
    // Peek height must clear bottom insets (app nav bar + system nav bar) 
    // plus space for drag handle (20dp) + search bar (56dp) + padding (24dp)
    val peekHeight = 100.dp + bottomInsetDp

    Box(modifier = modifier.fillMaxSize()) {
        BottomSheetScaffold(
            modifier = Modifier.fillMaxSize(),
            scaffoldState = scaffoldState,
            sheetPeekHeight = peekHeight,
            sheetDragHandle = {},
            topBar = {},
            containerColor = Color.Transparent,
            sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            sheetContent = {
                // Content only visible when expanded
                val safeBottomPadding = bottomInsetDp + 16.dp

                // Drag handle for sheet affordance
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(2.dp)
                            )
                    )
                }

                // Search bar — always visible in peek
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                    tonalElevation = 2.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp)
                        .height(56.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.description_map_search),
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
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { store.dispatch(MapEvent.SubmitSearch) }),
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
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.map_search_clear))
                            }
                        }
                    }
                }

                // Sheet body — only render when not in peek to avoid content bleeding below
                if (visibility != SheetVisibility.Peek && visibility != SheetVisibility.Hidden) {
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
                            text = stringResource(R.string.map_filters_title),
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
                                Text(stringResource(R.string.map_quality_label), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "%.2fx".format(uiState.quality), 
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.map_quality_low), style = MaterialTheme.typography.labelSmall)
                                Slider(
                                    value = uiState.quality,
                                    onValueChange = { v -> store.setQuality(v) },
                                    valueRange = 0.25f..2.0f,
                                    steps = 6,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp)
                                        .semantics {
                                            contentDescription = "Quality setting: ${String.format("%.2f", uiState.quality)} times"
                                        }
                                )
                                Text(stringResource(R.string.map_quality_high), style = MaterialTheme.typography.labelSmall)
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
                                Text(stringResource(R.string.map_date_range_all_time), style = MaterialTheme.typography.titleMedium)
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
                            text = stringResource(R.string.map_layers_title),
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
                }
            },
            content = { }
        )
        
        // Floating map controls (outside sheet)
        if (imeBottom == 0 && visibility != SheetVisibility.Expanded) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 16.dp,
                        bottom = peekHeight + bottomInsetDp + 16.dp
                    ),
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
                            contentDescription = stringResource(R.string.map_layers_button),
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

        // Snackbar for layer loading errors - positioned above sheet
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = peekHeight + bottomInsetDp + 8.dp)
        )
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

    // Auto-expand when user focuses search or keyboard is visible; auto-peek when keyboard hides
    LaunchedEffect(uiState.search.hasFocus, imeBottom) {
        if (uiState.search.hasFocus || imeBottom > 0) {
            if (visibility != SheetVisibility.Expanded) {
                expandedByKeyboard = true
            }
            bottomSheetState.expand()
        } else if (expandedByKeyboard && visibility == SheetVisibility.Expanded) {
            expandedByKeyboard = false
            bottomSheetState.partialExpand()
        }
    }

    // Report current bottom padding for the map (visible sheet peek height)
    LaunchedEffect(bottomSheetState, peekHeight, effectiveBottomInsetPx) {
         val peekHeightPx = with(density) { peekHeight.roundToPx() }
         val totalBottom = (effectiveBottomInsetPx + peekHeightPx).coerceAtLeast(0) 
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
            .clickable(onClick = onSelect)
            .semantics { selected = isSelected },
        shape = MaterialTheme.shapes.large,
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
                    style = MaterialTheme.typography.titleMedium,
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
                                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraSmall)
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
        "speed_heatmap" -> Icons.AutoMirrored.Filled.DirectionsRun
        "location_polyline" -> Icons.Filled.Timeline
        "none" -> Icons.Filled.LayersClear
        else -> Icons.Filled.Layers
    }
}
