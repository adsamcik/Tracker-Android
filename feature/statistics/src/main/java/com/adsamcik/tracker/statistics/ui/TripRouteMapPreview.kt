package com.adsamcik.tracker.statistics.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.adsamcik.tracker.map.MapLibreInitializer
import com.adsamcik.tracker.map.basemap.BasemapManager
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.shared.MapStyleProvider
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.map.MapPreferenceKeys
import com.adsamcik.tracker.statistics.R
import kotlinx.coroutines.CancellationException
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration.Companion.milliseconds

private const val MIN_SPAN_DEGREES = 0.001

private val NO_GESTURES = GestureOptions(
	isRotateEnabled = false,
	isScrollEnabled = false,
	isTiltEnabled = false,
	isZoomEnabled = false,
	isDoubleTapEnabled = false,
	isQuickZoomEnabled = false,
)

/**
 * MapLibre-based route preview for trip detail.
 *
 * Renders the trip polyline on an offline basemap with start/end markers.
 * All gestures are disabled — this is a static preview card.
 *
 * @param points route coordinates already filtered and simplified for preview rendering.
 */
@Composable
fun TripRouteMapPreview(
	points: List<LatLngModel>,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val isDark = isSystemInDarkTheme()

	val mapLibreReady by MapLibreInitializer.isReady.collectAsState()
	val prefs = remember { Preferences(context) }
	val customPath by prefs.observeString(MapPreferenceKeys.BASEMAP_PATH, "")
		.collectAsState(initial = "")
	var basemapPath by remember { mutableStateOf<String?>(null) }
	var mapReady by remember { mutableStateOf(false) }
	var preparingBasemap by remember { mutableStateOf(true) }
	var mapLoadFailed by remember { mutableStateOf(false) }

	LaunchedEffect(Unit) {
		if (!mapLibreReady) {
			MapLibreInitializer.initialize(context.applicationContext)
		}
		try {
			preparingBasemap = true
			mapLoadFailed = false
			basemapPath = BasemapManager(context).ensureDefaultBasemap()
		} catch (_: CancellationException) {
			throw CancellationException()
		} catch (_: Exception) {
			basemapPath = null
		} finally {
			preparingBasemap = false
		}
	}

	val baseStyle = remember(customPath, isDark, basemapPath) {
		when {
			customPath.isNotBlank() -> {
				MapStyleProvider.customStyleJson(customPath, isDark)?.let { BaseStyle.Json(it) }
					?: basemapPath?.let { path -> BaseStyle.Json(MapStyleProvider.defaultStyleJson(path, isDark)) }
			}
			basemapPath != null -> {
				BaseStyle.Json(MapStyleProvider.defaultStyleJson(requireNotNull(basemapPath), isDark))
			}
			else -> null
		}
	}

	val routeCoords = remember(points) {
		points.filter { it.lat.isFinite() && it.lng.isFinite() }
	}

	val bounds = remember(routeCoords) {
		if (routeCoords.size < 2) return@remember null
		var minLat = Double.MAX_VALUE
		var maxLat = -Double.MAX_VALUE
		var minLon = Double.MAX_VALUE
		var maxLon = -Double.MAX_VALUE
		routeCoords.forEach { coord ->
			minLat = minOf(minLat, coord.lat)
			maxLat = maxOf(maxLat, coord.lat)
			minLon = minOf(minLon, coord.lng)
			maxLon = maxOf(maxLon, coord.lng)
		}
		// Ensure a minimum span so identical start/end doesn't over-zoom
		val latSpan = (maxLat - minLat).coerceAtLeast(MIN_SPAN_DEGREES)
		val lonSpan = (maxLon - minLon).coerceAtLeast(MIN_SPAN_DEGREES)
		val centerLat = (minLat + maxLat) / 2.0
		val centerLon = (minLon + maxLon) / 2.0
		BoundingBox(
			west = centerLon - lonSpan / 2.0,
			south = centerLat - latSpan / 2.0,
			east = centerLon + lonSpan / 2.0,
			north = centerLat + latSpan / 2.0,
		)
	}

	val initialPosition = remember(routeCoords) {
		if (routeCoords.size >= 2) {
			val avgLat = routeCoords.map { it.lat }.average()
			val avgLng = routeCoords.map { it.lng }.average()
			// GeoJSON / MapLibre Position is (longitude, latitude) — NOT (lat, lng).
			CameraPosition(target = Position(avgLng, avgLat), zoom = 12.0)
		} else {
			CameraPosition(target = Position(0.0, 0.0), zoom = 2.0)
		}
	}

	val cameraState = rememberCameraState(firstPosition = initialPosition)

	// Tear down the native MapLibre renderer whenever the screen is not resumed.
	// Mirrors MapScreen: the GL render thread otherwise issues onDrawFrame against
	// a surface being destroyed at onStop, crashing natively in libmaplibre.so
	// (mbgl::android::MapRenderer::render). Gating on RESUMED disposes the GL
	// renderer at onPause, BEFORE surface teardown; cameraState is hoisted above
	// this gate so the camera is restored when the preview returns to the foreground.
	val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
	val isMapVisible = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

	// Fit camera to bounds once the map loads
	LaunchedEffect(mapReady, bounds) {
		if (mapReady && bounds != null) {
			try {
				cameraState.animateTo(
					boundingBox = bounds,
					padding = PaddingValues(24.dp),
					duration = 0.milliseconds,
				)
			} catch (_: CancellationException) {
				throw CancellationException()
			} catch (_: Exception) {
				// Camera fit failed — acceptable, initial position is a reasonable fallback
			}
		}
	}

	Box(modifier = modifier, contentAlignment = Alignment.Center) {
		when (
			tripPreviewContentState(
				hasBaseStyle = baseStyle != null,
				mapLibreReady = mapLibreReady,
				routePointCount = routeCoords.size,
				preparingBasemap = preparingBasemap,
				mapLoadFailed = mapLoadFailed,
				isVisible = isMapVisible,
			)
		) {
			TripPreviewContentState.Map -> {
				MaplibreMap(
					modifier = Modifier.fillMaxSize(),
					baseStyle = requireNotNull(baseStyle),
					cameraState = cameraState,
					options = MapOptions(
						gestureOptions = NO_GESTURES,
						ornamentOptions = OrnamentOptions(
							isScaleBarEnabled = false,
							isLogoEnabled = false,
							isAttributionEnabled = false,
						),
					),
					onMapLoadFinished = { mapReady = true },
					onMapLoadFailed = {
						mapReady = false
						mapLoadFailed = true
					},
				) {
					RouteLineLayer(routeCoords)
					RouteEndpointLayers(routeCoords)
				}
			}
			TripPreviewContentState.Loading -> {
				CircularProgressIndicator(
					modifier = Modifier.align(Alignment.Center),
					strokeWidth = 2.dp,
				)
			}
			TripPreviewContentState.Error -> {
				Surface(
					shape = MaterialTheme.shapes.medium,
					color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
				) {
					Text(
						text = androidx.compose.ui.res.stringResource(R.string.trip_route_preview_unavailable),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
					)
				}
			}
		}
	}
}

internal enum class TripPreviewContentState { Loading, Map, Error }

internal fun tripPreviewContentState(
	hasBaseStyle: Boolean,
	mapLibreReady: Boolean,
	routePointCount: Int,
	preparingBasemap: Boolean,
	mapLoadFailed: Boolean,
	isVisible: Boolean = true,
): TripPreviewContentState = when {
	mapLoadFailed -> TripPreviewContentState.Error
	routePointCount < 2 -> TripPreviewContentState.Error
	// While the host is paused/backgrounded, keep the native map OUT of
	// composition so its GL render thread is torn down before the surface.
	hasBaseStyle && mapLibreReady && !isVisible -> TripPreviewContentState.Loading
	hasBaseStyle && mapLibreReady -> TripPreviewContentState.Map
	preparingBasemap || !mapLibreReady -> TripPreviewContentState.Loading
	else -> TripPreviewContentState.Error
}

@Composable
private fun RouteLineLayer(routeCoords: List<LatLngModel>) {
	val geoJson = remember(routeCoords) {
		GeoJsonConverter.lineToFeatureCollection(routeCoords)
	}
	val source = rememberGeoJsonSource(data = GeoJsonData.JsonString(geoJson))
	LineLayer(
		id = "trip-route-line",
		source = source,
		color = const(MaterialTheme.colorScheme.primary),
		width = const(4.dp),
		opacity = const(0.85f),
	)
}

@Composable
private fun RouteEndpointLayers(routeCoords: List<LatLngModel>) {
	val start = routeCoords.firstOrNull() ?: return
	val end = routeCoords.lastOrNull() ?: return

	val startGeoJson = remember(start) {
		GeoJsonConverter.pointToFeature(start.lat, start.lng)
	}
	val endGeoJson = remember(end) {
		GeoJsonConverter.pointToFeature(end.lat, end.lng)
	}

	val startSource = rememberGeoJsonSource(data = GeoJsonData.JsonString(startGeoJson))
	val endSource = rememberGeoJsonSource(data = GeoJsonData.JsonString(endGeoJson))

	val strokeColor = MaterialTheme.colorScheme.surface

	CircleLayer(
		id = "trip-start-marker",
		source = startSource,
		radius = const(7.dp),
		color = const(MaterialTheme.colorScheme.tertiary),
		strokeColor = const(strokeColor),
		strokeWidth = const(2.dp),
	)
	CircleLayer(
		id = "trip-end-marker",
		source = endSource,
		radius = const(7.dp),
		color = const(MaterialTheme.colorScheme.error),
		strokeColor = const(strokeColor),
		strokeWidth = const(2.dp),
	)
}
