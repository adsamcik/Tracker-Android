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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.layout.onSizeChanged
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
	onShareMapClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val state by store.state.collectAsStateWithLifecycle()
	val context = LocalContext.current
	val density = LocalDensity.current
	val clipboard = LocalClipboard.current
	val scope = rememberCoroutineScope()
	val haptic = LocalHapticFeedback.current

	val allLayers = remember(registry) { registry.getAllLayers() }
	val legacyHeatmapEnabled by store.legacyHeatmapEnabled.collectAsStateWithLifecycle()
	val zoomButtonsEnabled by store.zoomButtonsEnabled.collectAsStateWithLifecycle()
	// The legacy grid-tile heatmap is an easter egg: hide it from the picker unless the user has
	// enabled it in Map settings.
	val layers = remember(allLayers, legacyHeatmapEnabled) {
		if (legacyHeatmapEnabled) allLayers
		else allLayers.filterNot { it.id == "legacy_heatmap" }
	}
	var showLayers by remember { mutableStateOf(false) }
	var showDates by remember { mutableStateOf(false) }
	var showCustomDateRange by remember { mutableStateOf(false) }
	var searchError by remember { mutableStateOf<String?>(null) }
	var searchFocused by remember { mutableStateOf(false) }
	// Measured height of the bottom control stack (my-location FAB + chrome bar). The snackbar
	// floats above it; a fixed offset would land on the chip row when zoom buttons show or fonts
	// scale, so we anchor to the real height instead.
	var controlStackHeightPx by remember { mutableStateOf(0) }
	var followAfterPermissionGrant by rememberSaveable { mutableStateOf(false) }
	val locationPermissionFlow = rememberMapLocationPermissionFlow(
		onPermissionGranted = {
			if (followAfterPermissionGrant) {
				followAfterPermissionGrant = false
				store.dispatch(MapEvent.ToggleFollow)
			}
		}
	)

	// Inset handling. `bottomInsetPx` (from MapNavGraph) is the floating navigation
	// bar's footprint plus the system navigation-bar inset
	// (AppDimensions.FloatingNavBarReserve + navBarPad). The map *surface* renders
	// full-bleed behind the floating bar, but this chrome overlay must sit above it,
	// so we inset the whole chrome Box by that footprint (see the bottom padding on
	// the Box below). The per-strip `controlStripBottomPx` then only adds the small
	// visual gap, plus any keyboard overflow when the IME is taller than the reserved
	// footprint so the search field never disappears under the keyboard.
	val imeBottom = WindowInsets.ime.getBottom(density)
	val navBottom = WindowInsets.navigationBars.getBottom(density)
	val systemBottomPx = maxOf(imeBottom, navBottom)
	// Visual breathing room between the chrome strip (search/chips/FAB) and the
	// floating navigation pill below. Kept tight so the two frosted-glass controls
	// read as a connected cluster rather than two widely-separated bars.
	val controlStripBottomPx = resolveMapChromeBottomPaddingPx(
		bottomInsetPx = bottomInsetPx,
		imeBottomPx = imeBottom,
		gapPx = with(density) { 8.dp.roundToPx() },
	)
	val controlStripBottomDp = with(density) { controlStripBottomPx.toDp() }
	val chromeBottomInsetDp = with(density) { bottomInsetPx.toDp() }

	// Report the height the map should keep clear so map attribution/content doesn't sit
	// behind the chrome. Stack is: search pill 56dp + 8dp gap + chip row 40dp = ~104dp,
	// plus the bottom inset we're already accounting for in controlStripBottomDp.
	LaunchedEffect(controlStripBottomDp, systemBottomPx) {
		val clearancePx = with(density) { (controlStripBottomDp + 112.dp).roundToPx() }
		onBottomPaddingChanged(clearancePx.coerceAtLeast(0))
	}

	// Close popovers on system back before letting nav handle it.
	BackHandler(enabled = showLayers || showDates) {
		showLayers = false
		showDates = false
	}

	// Surface search errors as snackbars so they stay visible even after the user dismisses
	// the bar; same trick the old MapSheet used.
	LaunchedEffect(searchError) {
		searchError?.let { snackbarHostState.showSnackbar(it) }
	}

	val activeLayerId = state.activeLayerIds.firstOrNull()

	val activeLayerLabel = remember(activeLayerId, layers) {
		val active = activeLayerId
		if (active.isNullOrBlank() || active == "none") {
			context.getString(R.string.map_layer_none_title)
		} else {
			val descriptor = layers.firstOrNull { it.id == active }
			// Prefer the short chip label when set; long titles like
			// "Location polyline" / "Wi-Fi count heatmap" / "Vehicle speed
			// compliance" truncate to "Location polyli..." in the active-layer
			// chip on the map control bar (~360dp width).
			val labelRes = descriptor?.chipLabelRes ?: descriptor?.titleRes
			labelRes?.let {
				try {
					context.getString(it)
				} catch (e: Exception) {
					active
				}
			} ?: active
		}
	}

	val dateRangeLabel = remember(state.dateRange, state.selectedTripContext) {
		when {
			state.selectedTripContext != null -> context.getString(R.string.map_date_range_selected_trip)
			else -> matchingMapDateRangePreset(state.dateRange)
				?.let { context.getString(it.labelRes) }
				?: context.getString(R.string.map_date_preset_custom)
		}
	}

	Box(
		modifier = modifier
			.fillMaxSize()
			.padding(bottom = chromeBottomInsetDp),
	) {
		// Bottom control region — stacked so the my-location FAB sits above the strip.
		Column(
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.fillMaxWidth()
				.padding(bottom = controlStripBottomDp)
				.onSizeChanged { controlStackHeightPx = it.height },
			horizontalAlignment = Alignment.End,
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			// My-location FAB lives to the right, above the chrome bar. Hide when search is
			// focused — the keyboard rises and would cover it anyway, and an obstructed FAB is
			// worse than no FAB.
			if (!searchFocused && imeBottom == 0) {
				// Accessibility zoom buttons (opt-in via Map settings) sit above the FAB so the
				// whole right-side column reads as one control stack.
				if (zoomButtonsEnabled) {
					ZoomControls(
						onZoomIn = { store.dispatch(MapEvent.ZoomIn) },
						onZoomOut = { store.dispatch(MapEvent.ZoomOut) },
						modifier = Modifier.padding(end = 24.dp),
					)
				}
				ShareMapFab(
					onClick = onShareMapClick,
					modifier = Modifier.padding(end = 24.dp),
				)
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
				dateRangeLabel = dateRangeLabel,
				onDatesClick = { showDates = true },
				layersExpanded = showLayers,
				datesExpanded = showDates,
				// Inset the chips + search row 24dp per side to match the floating navigation
				// bar's horizontal inset (FloatingNavigationBar: padding(start/end = 24.dp)).
				// The right-side FAB/zoom controls already sit at end = 24.dp, so all the
				// floating map chrome now shares one edge alignment with the nav bar.
				modifier = Modifier.padding(horizontal = 24.dp),
			)
		}

		// Float the snackbar just above the whole control stack (FAB + chrome bar) so it never
		// overlaps the chips or search field. controlStackHeightPx is the measured content height
		// (excludes the controlStripBottomDp padding, which we re-add here).
		val controlStackHeightDp = with(density) { controlStackHeightPx.toDp() }
		SnackbarHost(
			hostState = snackbarHostState,
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(bottom = controlStripBottomDp + controlStackHeightDp + 12.dp),
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
		LaunchedEffect(showLayers, showDates) {
			val vis = if (showLayers || showDates) SheetVisibility.Expanded else SheetVisibility.Peek
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
	// Frosted-glass material shared with the chrome bar and the floating navigation bar: idle uses
	// the translucent surface tint + glass edge so the FAB reads as the same material; the active
	// (following) state keeps an opaque primaryContainer accent, mirroring the nav bar's selected
	// pill. No shadow — a translucent surface should not cast one.
	Surface(
		modifier = modifier.size(56.dp),
		shape = CircleShape,
		color = if (isFollowing) {
			MaterialTheme.colorScheme.primaryContainer
		} else {
			mapChromeFrostedColor()
		},
		border = if (isFollowing) null else mapChromeGlassBorder(),
		tonalElevation = 0.dp,
		shadowElevation = 0.dp,
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

/** Opens the "share map as image" bottom sheet. Same frosted-glass FAB material as [MyLocationFab]. */
@Composable
private fun ShareMapFab(
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		modifier = modifier.size(56.dp),
		shape = CircleShape,
		color = mapChromeFrostedColor(),
		border = mapChromeGlassBorder(),
		tonalElevation = 0.dp,
		shadowElevation = 0.dp,
	) {
		IconButton(
			onClick = onClick,
			modifier = Modifier.testTag("map_share_button"),
		) {
			Icon(
				imageVector = Icons.Filled.Share,
				contentDescription = stringResource(R.string.map_share_button),
				tint = MaterialTheme.colorScheme.onSurface,
			)
		}
	}
}

/**
 * Accessibility zoom controls: a vertical glass pill with zoom-in (+) and zoom-out (−) buttons,
 * matching the my-location FAB's glass treatment. Opt-in via Map settings for users who can't
 * pinch-zoom comfortably.
 */
@Composable
private fun ZoomControls(
	onZoomIn: () -> Unit,
	onZoomOut: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		modifier = modifier.width(56.dp),
		shape = RoundedCornerShape(28.dp),
		color = mapChromeFrostedColor(),
		border = mapChromeGlassBorder(),
		tonalElevation = 0.dp,
		shadowElevation = 0.dp,
	) {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			IconButton(
				onClick = onZoomIn,
				modifier = Modifier
					.size(56.dp)
					.testTag("map_zoom_in_button"),
			) {
				Icon(
					imageVector = Icons.Filled.Add,
					contentDescription = stringResource(R.string.map_zoom_in),
					tint = MaterialTheme.colorScheme.onSurface,
				)
			}
			HorizontalDivider(
				modifier = Modifier.width(28.dp),
				color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
			)
			IconButton(
				onClick = onZoomOut,
				modifier = Modifier
					.size(56.dp)
					.testTag("map_zoom_out_button"),
			) {
				Icon(
					imageVector = Icons.Filled.Remove,
					contentDescription = stringResource(R.string.map_zoom_out),
					tint = MaterialTheme.colorScheme.onSurface,
				)
			}
		}
	}
}
