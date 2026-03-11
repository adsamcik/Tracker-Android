package com.adsamcik.tracker.map.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.layers.registry.LayerRegistry
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.SearchResultStatus
import com.adsamcik.tracker.map.presentation.udf.SheetVisibility
import com.adsamcik.tracker.map.shared.layers.LayerDescriptor
import com.adsamcik.tracker.shared.utils.style.compose.BottomSheetShape
import com.adsamcik.tracker.shared.utils.style.compose.MomentumPillShape
import java.util.Locale

internal const val MAP_SHEET_DRAG_HANDLE_TAG = "map_sheet_drag_handle"

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
    val state by store.state.collectAsStateWithLifecycle()
    val uiState = state
    val visibility = uiState.sheet.visibility
    val bottomSheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = false,
        confirmValueChange = { true }
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = bottomSheetState)
    BackHandler(enabled = visibility == SheetVisibility.Expanded) {
        store.dispatch(MapEvent.SetSheet(SheetVisibility.Peek))
    }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
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
    val currentCenterCoordinates = remember(uiState.camera.lat, uiState.camera.lng) {
        String.format(Locale.US, "%.5f, %.5f", uiState.camera.lat, uiState.camera.lng)
    }

    // Peek height includes the visible controls plus the safe area hidden behind app/system bars.
    val peekHeight = 192.dp + bottomInsetDp
    val visibleSheetHeight = if (visibility == SheetVisibility.Hidden) 0.dp else peekHeight

    Box(modifier = modifier.fillMaxSize()) {
        BottomSheetScaffold(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    customActions = listOf(
                        androidx.compose.ui.semantics.CustomAccessibilityAction(
                            label = if (visibility == SheetVisibility.Expanded) "Collapse sheet" else "Expand sheet"
                        ) {
                            if (visibility == SheetVisibility.Expanded) {
                                store.dispatch(MapEvent.SetSheet(SheetVisibility.Peek))
                            } else {
                                store.dispatch(MapEvent.SetSheet(SheetVisibility.Expanded))
                            }
                            true
                        }
                    )
                },
            scaffoldState = scaffoldState,
            sheetPeekHeight = peekHeight,
            sheetShape = BottomSheetShape,
            sheetDragHandle = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag(MAP_SHEET_DRAG_HANDLE_TAG),
                    contentAlignment = Alignment.Center
                ) {
                    BottomSheetDefaults.DragHandle()
                }
            },
            topBar = {},
            containerColor = Color.Transparent,
            sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            sheetContent = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = bottomInsetDp)
                ) {
                    // Search bar — always visible in peek
                    Surface(
                        shape = MomentumPillShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        tonalElevation = 2.dp,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp)
                            .heightIn(min = 92.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                IconButton(
                                    onClick = { store.dispatch(MapEvent.SubmitSearch) },
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = stringResource(R.string.description_map_search),
                                        modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    TextField(
                                        value = uiState.search.query,
                                        onValueChange = { q -> store.dispatch(MapEvent.UpdateSearchQuery(q)) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 56.dp)
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
                                IconButton(
                                    onClick = { store.dispatch(MapEvent.UpdateSearchQuery("")) },
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                    enabled = uiState.search.query.isNotEmpty()
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.map_search_clear),
                                        tint = if (uiState.search.query.isNotEmpty()) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                        }
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, end = 12.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    onClick = {
                                        val clipboardText = clipboardManager.getText()?.text?.trim().orEmpty()
                                        if (clipboardText.isBlank()) {
                                            showErrorMessage = null
                                            showErrorMessage = context.getString(R.string.map_search_clipboard_empty)
                                        } else {
                                            store.dispatch(MapEvent.UpdateSearchQuery(clipboardText))
                                            store.dispatch(MapEvent.SubmitSearch)
                                        }
                                    },
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                ) {
                                    Text(text = stringResource(R.string.map_search_paste))
                                }
                                Text(
                                    text = stringResource(
                                        R.string.map_search_current_center,
                                        currentCenterCoordinates
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Search result feedback
                    if (uiState.search.resultStatus == SearchResultStatus.NotFound) {
                        Text(
                            text = stringResource(R.string.map_search_not_found),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    } else if (uiState.search.resultStatus == SearchResultStatus.Found) {
                        Text(
                            text = stringResource(R.string.map_search_found),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    // Quick layer chips — always visible in peek for one-tap switching
                    val quickLayerScrollState = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(quickLayerScrollState)
                            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        layers.forEach { layer ->
                            val isLayerSelected = if (layer.id == "none") {
                                uiState.activeLayerIds.isEmpty()
                            } else {
                                uiState.activeLayerIds.contains(layer.id)
                            }
                            FilterChip(
                                selected = isLayerSelected,
                                onClick = {
                                    try {
                                        store.dispatch(MapEvent.SelectLayer(layer.id))
                                    } catch (_: Exception) { }
                                },
                                label = {
                                    Text(
                                        text = try { context.getString(layer.titleRes) } catch (_: Exception) { layer.id },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                leadingIcon = if (isLayerSelected) {
                                    {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else {
                                    {
                                        Icon(
                                            getLayerIcon(layer.id),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                shape = MaterialTheme.shapes.small,
                            )
                        }
                    }

                    // Sheet body — only render when not in peek to avoid content bleeding below
                    if (visibility != SheetVisibility.Peek && visibility != SheetVisibility.Hidden) {
                        val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
                        val maxSheetContentHeight = screenHeightDp * 0.65f
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxSheetContentHeight),
                            contentPadding = PaddingValues(
                                top = 16.dp,
                                bottom = 16.dp,
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

                            // Map Detail (replaces quality slider)
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        stringResource(R.string.map_quality_label),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    val qualityOptions = listOf(
                                        "Fast" to 0.5f,
                                        "Balanced" to 1.0f,
                                        "Detailed" to 2.0f,
                                    )
                                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                        qualityOptions.forEachIndexed { index, (label, value) ->
                                            SegmentedButton(
                                                selected = uiState.quality == value,
                                                onClick = { store.setQuality(value) },
                                                shape = SegmentedButtonDefaults.itemShape(
                                                    index = index,
                                                    count = qualityOptions.size
                                                ),
                                            ) {
                                                Text(label)
                                            }
                                        }
                                    }
                                }
                            }

                            // Date Range
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.tips_map_date_range_title), style = MaterialTheme.typography.titleMedium)
                                    val isAllTime = uiState.dateRange.first == 0L && uiState.dateRange.last == Long.MAX_VALUE
                                    val now = remember { System.currentTimeMillis() }
                                    val oneWeekMs = 7L * 24 * 60 * 60 * 1000
                                    val oneMonthMs = 30L * 24 * 60 * 60 * 1000

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        FilterChip(
                                            selected = isAllTime,
                                            onClick = { store.setDateRange(0L..Long.MAX_VALUE) },
                                            label = { Text(stringResource(R.string.map_date_range_all_time)) },
                                            shape = MaterialTheme.shapes.small,
                                        )
                                        FilterChip(
                                            selected = !isAllTime && uiState.dateRange.first >= now - oneWeekMs,
                                            onClick = { store.setDateRange((now - oneWeekMs)..now) },
                                            label = { Text(stringResource(R.string.map_date_preset_week)) },
                                            shape = MaterialTheme.shapes.small,
                                        )
                                        FilterChip(
                                            selected = !isAllTime && uiState.dateRange.first >= now - oneMonthMs && uiState.dateRange.first < now - oneWeekMs,
                                            onClick = { store.setDateRange((now - oneMonthMs)..now) },
                                            label = { Text(stringResource(R.string.map_date_preset_month)) },
                                            shape = MaterialTheme.shapes.small,
                                        )
                                        FilterChip(
                                            selected = !isAllTime && uiState.dateRange.first < now - oneMonthMs,
                                            onClick = { showDateRangeDialog = true },
                                            label = { Text(stringResource(R.string.map_date_preset_custom)) },
                                            trailingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                            shape = MaterialTheme.shapes.small,
                                        )
                                    }

                                    if (!isAllTime) {
                                        val rangeText = remember(uiState.dateRange) {
                                            val fmt = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                                            "${fmt.format(java.util.Date(uiState.dateRange.first))} – ${fmt.format(java.util.Date(uiState.dateRange.last))}"
                                        }
                                        Text(
                                            rangeText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
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
                                val isSelected = if (layer.id == "none") {
                                    uiState.activeLayerIds.isEmpty()
                                } else {
                                    uiState.activeLayerIds.contains(layer.id)
                                }
                                MapLayerCard(
                                    layer = layer,
                                    isSelected = isSelected,
                                    onSelect = {
                                        try {
                                            store.dispatch(MapEvent.SelectLayer(layer.id))
                                            showErrorMessage = null
                                        } catch (e: Exception) {
                                            showErrorMessage = context.getString(R.string.map_layer_load_error)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            },
            content = { }
        )
        
        // Floating map controls (outside sheet)
        val haptic = LocalHapticFeedback.current
        if (imeBottom == 0) {
            Column(
                modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 16.dp,
                            bottom = visibleSheetHeight + 16.dp
                        ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Layers — hide when expanded (sheet already shows layers)
                if (visibility != SheetVisibility.Expanded) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        tonalElevation = 2.dp,
                        shadowElevation = 4.dp,
                        modifier = Modifier.size(56.dp)
                    ) {
                        val layersDesc = stringResource(R.string.map_layers_button)
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                store.dispatch(MapEvent.SetSheet(SheetVisibility.Expanded))
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .semantics {
                                    contentDescription = layersDesc
                                },
                        ) {
                            Icon(
                                Icons.Filled.Layers,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Location — always visible (even when expanded)
                val isFollowing = uiState.isFollowing
                Surface(
                    shape = CircleShape,
                    color = if (isFollowing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    tonalElevation = 2.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(56.dp)
                ) {
                    val locationDesc = stringResource(R.string.tips_map_my_location_title)
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            store.dispatch(MapEvent.ToggleFollow)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics {
                                contentDescription = locationDesc
                                stateDescription = if (isFollowing) "Following" else "Not following"
                                role = Role.Switch
                            },
                    ) {
                        Icon(
                            Icons.Filled.MyLocation,
                            contentDescription = null,
                            tint = if (isFollowing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Snackbar for layer loading errors - positioned above FABs
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = visibleSheetHeight + 80.dp)
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

    // Only auto-expand for keyboard visibility; collapse when keyboard hides
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0) {
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
    LaunchedEffect(visibility, peekHeight) {
        val visibleSheetHeightPx = with(density) { visibleSheetHeight.roundToPx() }
        onBottomPaddingChanged(visibleSheetHeightPx.coerceAtLeast(0))
    }

    // First-visit onboarding tip
    val mapPrefs = remember { com.adsamcik.tracker.shared.preferences.Preferences.getPref(context) }
    val tipShown = remember { mapPrefs.getBoolean("map_tips_shown_v2", false) }
    if (!tipShown) {
        LaunchedEffect(Unit) {
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.tips_map_sheet_description),
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            mapPrefs.edit { setBoolean("map_tips_shown_v2", true) }
        }
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
            .semantics(mergeDescendants = true) {
                selected = isSelected
                role = Role.RadioButton
                stateDescription = if (isSelected) "Selected" else "Not selected"
            },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
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
                        val firstLabel = legend.valueList.firstOrNull()?.let { try { context.getString(it.nameRes) } catch (_: Exception) { "" } } ?: ""
                        val lastLabel = legend.valueList.lastOrNull()?.let { try { context.getString(it.nameRes) } catch (_: Exception) { "" } } ?: ""
                        // Show colorful legend strip
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraSmall)
                                .semantics { contentDescription = "Legend: $firstLabel to $lastLabel" }
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
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (last != null && last != first) {
                                Text(
                                     try { context.getString(last.nameRes) } catch(e:Exception){""},
                                     style = MaterialTheme.typography.bodyMedium,
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
            
            if (isSelected) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
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

