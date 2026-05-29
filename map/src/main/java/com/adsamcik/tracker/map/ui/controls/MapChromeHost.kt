package com.adsamcik.tracker.map.ui.controls

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.layers.registry.LayerRegistry
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.SheetVisibility
import kotlinx.coroutines.launch

/**
 * Replacement for the old MapSheet. Owns the compact control bar, the layer / date
 * popovers, the my-location FAB, snackbar host, and the custom date-range dialog.
 *
 * The nav bar stays above everything at the global level — this composable only consumes
 * the nav bar inset so its own content floats above the tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapChromeHost(
	registry: LayerRegistry,
	store: MapStore,
	snackbarHostState: SnackbarHostState,
	bottomInsetPx: Int,
	onBottomPaddingChanged: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	val state by store.state.collectAsStateWithLifecycle()
	val context = LocalContext.current
	val density = LocalDensity.current
	val clipboard = LocalClipboard.current
	val scope = rememberCoroutineScope()
	val haptic = LocalHapticFeedback.current

	val layers = remember(registry) { registry.getAllLayers() }
	var showLayers by remember { mutableStateOf(false) }
	var showQuality by remember { mutableStateOf(false) }
	var showDates by remember { mutableStateOf(false) }
	var showCustomDateRange by remember { mutableStateOf(false) }
	var searchError by remember { mutableStateOf<String?>(null) }
	var searchFocused by remember { mutableStateOf(false) }
	var followAfterPermissionGrant by rememberSaveable { mutableStateOf(false) }
	val locationPermissionFlow = rememberMapLocationPermissionFlow(
		onPermissionGranted = {
			if (followAfterPermissionGrant) {
				followAfterPermissionGrant = false
				store.dispatch(MapEvent.ToggleFollow)
			}
		}
	)

	// Inset handling. The caller (MapNavGraph) already accounts for the floating bottom
	// nav bar AND the system navigation-bar inset in `bottomInsetPx` (see MapNavGraph:
	// PaddingValues(bottom = 96.dp + navBarPad)). Re-adding `systemBottomDp` here would
	// push the chrome ~navInset+nav-bar-height above where it should sit, which is what
	// produced the visible "map controls float halfway up the screen" bug.
	//
	// We only need to override that when the IME is taller than what the caller reserved
	// (keyboard open) so the search field doesn't disappear under the keyboard.
	val imeBottom = WindowInsets.ime.getBottom(density)
	val navBottom = WindowInsets.navigationBars.getBottom(density)
	val systemBottomPx = maxOf(imeBottom, navBottom)
	val systemBottomDp = with(density) { systemBottomPx.toDp() }
	val extraBottomDp = with(density) { bottomInsetPx.toDp() }
	// Sit just above the global floating nav bar. Only 4dp gap — the card and the nav pill
	// read as a stacked pair rather than two disconnected islands. When the IME pushes
	// higher than the caller-provided inset, lift above the IME by that delta.
	val imeOverflowPx = (imeBottom - bottomInsetPx).coerceAtLeast(0)
	val imeOverflowDp = with(density) { imeOverflowPx.toDp() }
	val controlStripBottomDp = imeOverflowDp + 4.dp

	// Report the height the map should keep clear so map attribution/content doesn't sit
	// behind the chrome. Stack is: search pill 56dp + 8dp gap + chip row 40dp = ~104dp,
	// plus the bottom inset we're already accounting for in controlStripBottomDp.
	LaunchedEffect(controlStripBottomDp, systemBottomPx) {
		val clearancePx = with(density) { (controlStripBottomDp + 112.dp).roundToPx() }
		onBottomPaddingChanged(clearancePx.coerceAtLeast(0))
	}

	// Close popovers on system back before letting nav handle it.
	BackHandler(enabled = showLayers || showQuality || showDates) {
		showLayers = false
		showQuality = false
		showDates = false
	}

	// Surface search errors as snackbars so they stay visible even after the user dismisses
	// the bar; same trick the old MapSheet used.
	LaunchedEffect(searchError) {
		searchError?.let { snackbarHostState.showSnackbar(it) }
	}

	val activeLayerId = state.activeLayerIds.firstOrNull()
	val isHeatmapLayerSelected = activeLayerId.isHeatmapLayerId()

	LaunchedEffect(isHeatmapLayerSelected) {
		if (!isHeatmapLayerSelected) {
			showQuality = false
		}
	}

	val activeLayerLabel = remember(activeLayerId, layers) {
		val active = activeLayerId
		if (active.isNullOrBlank() || active == "none") {
			context.getString(R.string.map_layer_none_title)
		} else {
			val descriptor = layers.firstOrNull { it.id == active }
			descriptor?.titleRes?.let {
				try {
					context.getString(it)
				} catch (e: Exception) {
					active
				}
			} ?: active
		}
	}

	val activeQualityLabel = remember(state.quality) {
		context.getString(qualityLabelRes(state.quality))
	}

	val dateRangeLabel = remember(state.dateRange, state.selectedTripContext) {
		when {
			state.selectedTripContext != null -> context.getString(R.string.map_date_range_selected_trip)
			else -> matchingMapDateRangePreset(state.dateRange)
				?.let { context.getString(it.labelRes) }
				?: context.getString(R.string.map_date_preset_custom)
		}
	}

	Box(modifier = modifier.fillMaxSize()) {
		// Bottom control region — stacked so the my-location FAB sits above the strip.
		Column(
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.fillMaxWidth()
				.padding(bottom = controlStripBottomDp),
			horizontalAlignment = Alignment.End,
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			// My-location FAB lives to the right, above the chrome bar. Hide when search is
			// focused — the keyboard rises and would cover it anyway, and an obstructed FAB is
			// worse than no FAB.
			if (!searchFocused && imeBottom == 0) {
				MyLocationFab(
					isFollowing = state.isFollowing,
					onClick = {
						haptic.performHapticFeedback(HapticFeedbackType.LongPress)
						if (locationPermissionFlow.isGranted) {
							store.dispatch(MapEvent.ToggleFollow)
						} else {
							followAfterPermissionGrant = true
							locationPermissionFlow.requestLocationAccess()
						}
					},
					modifier = Modifier.padding(end = 24.dp),
				)
			}

			MapChromeBar(
				searchQuery = state.search.query,
				searchResultStatus = state.search.resultStatus,
				searchFocused = searchFocused,
				onSearchQueryChange = { q -> store.dispatch(MapEvent.UpdateSearchQuery(q)) },
				onSearchSubmit = { store.dispatch(MapEvent.SubmitSearch) },
				onSearchFocusChange = { focused -> searchFocused = focused },
				onSearchPaste = {
					scope.launch {
						val text = clipboard.getClipEntry()
							?.clipData
							?.getItemAt(0)
							?.text
							?.toString()
							?.trim()
							.orEmpty()
						if (text.isBlank()) {
							searchError = null
							searchError = context.getString(R.string.map_search_clipboard_empty)
						} else {
							store.dispatch(MapEvent.UpdateSearchQuery(text))
						}
					}
				},
				activeLayerLabel = activeLayerLabel,
				onLayersClick = { showLayers = true },
				showQualityChip = isHeatmapLayerSelected,
				qualityLabel = activeQualityLabel,
				onQualityClick = { showQuality = true },
				dateRangeLabel = dateRangeLabel,
				onDatesClick = { showDates = true },
				layersExpanded = showLayers,
				qualityExpanded = showQuality,
				datesExpanded = showDates,
			)
		}

		SnackbarHost(
			hostState = snackbarHostState,
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(bottom = controlStripBottomDp + 80.dp),
		)

		if (showLayers) {
			LayerPickerPopover(
				layers = layers,
				activeLayerIds = state.activeLayerIds,
				activeLegend = state.legend,
				onLayerSelected = { id ->
					try {
						store.dispatch(MapEvent.SelectLayer(id))
					} catch (e: Exception) {
						searchError = context.getString(R.string.map_layer_load_error)
					}
				},
				onDismiss = { showLayers = false },
			)
		}

		if (showQuality && isHeatmapLayerSelected) {
			QualityPickerPopover(
				quality = state.quality,
				onQualityChange = { value -> store.setQuality(value) },
				onDismiss = { showQuality = false },
			)
		}

		if (showDates) {
			DateRangePopover(
				currentRange = state.dateRange,
				onSetRange = { store.setDateRange(it) },
				onRequestCustom = { showCustomDateRange = true },
				onDismiss = { showDates = false },
			)
		}

		// Sheet-visibility signal is legacy-but-harmless; keep the store in sync with our
		// current popover state so any downstream consumer sees a coherent value.
		LaunchedEffect(showLayers, showQuality, showDates) {
			val vis = if (showLayers || showQuality || showDates) SheetVisibility.Expanded else SheetVisibility.Peek
			if (state.sheet.visibility != vis) {
				store.dispatch(MapEvent.SetSheet(vis))
			}
		}
	}

	if (showCustomDateRange) {
		val pickerState = rememberDateRangePickerState()
		DatePickerDialog(
			onDismissRequest = { showCustomDateRange = false },
			confirmButton = {
				TextButton(
					enabled = pickerState.selectedStartDateMillis != null &&
						pickerState.selectedEndDateMillis != null,
					onClick = {
						val start = pickerState.selectedStartDateMillis
						val end = pickerState.selectedEndDateMillis
						if (start != null && end != null) {
							val endInclusive = end + (24L * 60 * 60 * 1000) - 1L
							store.setDateRange(start..endInclusive)
							showCustomDateRange = false
						}
					},
				) { Text(stringResource(R.string.map_date_range_dialog_ok)) }
			},
			dismissButton = {
				TextButton(onClick = { showCustomDateRange = false }) {
					Text(stringResource(R.string.map_date_range_dialog_cancel))
				}
			},
		) {
			DateRangePicker(state = pickerState)
		}
	}
}

@Composable
private fun MyLocationFab(
	isFollowing: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		modifier = modifier.size(56.dp),
		shape = CircleShape,
		color = if (isFollowing) {
			MaterialTheme.colorScheme.primaryContainer
		} else {
			MaterialTheme.colorScheme.surfaceContainerHighest
		},
		tonalElevation = 3.dp,
		shadowElevation = 6.dp,
	) {
		IconButton(
			onClick = onClick,
			modifier = Modifier.testTag("map_my_location_button"),
		) {
			Icon(
				imageVector = Icons.Filled.MyLocation,
				contentDescription = stringResource(R.string.tips_map_my_location_title),
				tint = if (isFollowing) {
					MaterialTheme.colorScheme.onPrimaryContainer
				} else {
					MaterialTheme.colorScheme.onSurface
				},
			)
		}
	}
}

private fun String?.isHeatmapLayerId(): Boolean = this?.endsWith("_heatmap") == true

private fun qualityLabelRes(quality: Float): Int = when {
	quality < 0.75f -> R.string.map_quality_fast
	quality > 1.5f -> R.string.map_quality_detailed
	else -> R.string.map_quality_balanced
}
