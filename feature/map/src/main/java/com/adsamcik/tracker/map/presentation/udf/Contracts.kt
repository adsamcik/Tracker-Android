package com.adsamcik.tracker.map.presentation.udf

import androidx.compose.runtime.Immutable
import androidx.annotation.StringRes
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.shared.CoordinateBounds
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

@Immutable
data class CameraModel(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val zoom: Float = 0f,
    val tilt: Float = 0f,
    val bearing: Float = 0f,
)

@Immutable
data class SheetStateModel(
    val visibility: SheetVisibility = SheetVisibility.Peek
)

@Immutable
enum class SheetVisibility { Hidden, Peek, Expanded }

@Immutable
data class LegendItem(
    val label: String,
    val color: Int,
    @StringRes val labelRes: Int? = null,
)

@Immutable
data class LatLngModel(val lat: Double, val lng: Double)

/**
 * Result of tapping the interactive speed heatmap: the average and maximum recorded speed (m/s)
 * around [latLng]. [sampleCount] is 0 when the user tapped a spot with no nearby speed data, which
 * the UI surfaces as an explicit "no data here" message rather than stale numbers.
 */
@Immutable
data class SpeedProbeModel(
    val latLng: LatLngModel,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val sampleCount: Int,
) {
    val hasData: Boolean get() = sampleCount > 0
}

/**
 * Result of tapping the map to reveal the nearest place name via the offline reverse geocoder.
 * [title] is the most specific label (e.g. "Vinohradská, Prague"); [subtitle] is an optional
 * secondary line (country). [isLoading] is true while the lookup is in flight; [hasResult] is
 * false when nothing was found, which the UI surfaces as an explicit "no place here" message.
 */
@Immutable
data class PlaceCalloutModel(
    val latLng: LatLngModel,
    val title: String? = null,
    val subtitle: String? = null,
    val isLoading: Boolean = false,
) {
    val hasResult: Boolean get() = title != null
}

@Immutable
data class SelectedTripMapContext(
    val tripId: Long,
    val startMs: Long,
    val endMs: Long,
)

@Immutable
sealed interface MapOverlayState {
    @Immutable
    data class UserMarker(val latLng: LatLngModel, val bearing: Float? = null) : MapOverlayState
    @Immutable
    data class AccuracyCircle(val latLng: LatLngModel, val radiusM: Double) : MapOverlayState
    @Immutable
    data class SearchMarker(val latLng: LatLngModel) : MapOverlayState
    @Immutable
    data class Polyline(
        val points: ImmutableList<LatLngModel>,
        val colorArgb: Int = 0xFF007AFF.toInt(),
        val widthPx: Float = 6f,
    ) : MapOverlayState
}

@Immutable
data class SearchState(
    val query: String = "",
    val hasFocus: Boolean = false,
    val resultStatus: SearchResultStatus = SearchResultStatus.Idle,
)

@Immutable
enum class SearchResultStatus { Idle, Found, NotFound }

@Immutable
data class MapUiSettings(
    val isMapToolbarEnabled: Boolean = false,
    val isIndoorLevelPickerEnabled: Boolean = false,
    val isCompassEnabled: Boolean = false,
    val isMyLocationButtonEnabled: Boolean = false,
)

@Immutable
data class MapState(
    val activeLayerIds: ImmutableSet<String> = persistentSetOf(),
    val camera: CameraModel = CameraModel(),
    val isFollowing: Boolean = false,
    val sheet: SheetStateModel = SheetStateModel(),
    val overlays: ImmutableList<MapOverlayState> = persistentListOf(),
    val legend: ImmutableList<LegendItem> = persistentListOf(),
    val search: SearchState = SearchState(),
    val uiSettings: MapUiSettings = MapUiSettings(),
    val quality: Float = 1f,
    val dateRange: LongRange = 0L..Long.MAX_VALUE,
    val selectedTripContext: SelectedTripMapContext? = null,
    val layerLoadingProgress: Int = 0,
    val layerConfig: MapLibreLayerConfig? = null,
    val speedProbe: SpeedProbeModel? = null,
    val placeCallout: PlaceCalloutModel? = null,
)

sealed interface MapEvent {
    data object ToggleFollow : MapEvent
    data class SetFollowing(val isFollowing: Boolean) : MapEvent
    data object FollowCanceled : MapEvent
    data object ShowSheet : MapEvent
    data object HideSheet : MapEvent
    data class SetSheet(val visibility: SheetVisibility) : MapEvent
    data class SelectLayer(val id: String) : MapEvent
    data class CameraMoved(
        val position: CameraModel,
        val byGesture: Boolean,
        /**
         * The map's real visible viewport (already padded for prefetch), derived from the map
         * projection. Used for spatial layer queries; null when the projection is not yet ready,
         * in which case the store falls back to a camera-derived estimate.
         */
        val visibleBounds: Bounds? = null,
    ) : MapEvent
    data class SetQuality(val value: Float) : MapEvent
    data class SetDateRange(val range: LongRange) : MapEvent
    data class UpdateSearchQuery(val query: String) : MapEvent
    data class SetSearchFocus(val hasFocus: Boolean) : MapEvent
    data object SubmitSearch : MapEvent
    data class SetUserLocation(val latLng: LatLngModel, val accuracyM: Double) : MapEvent
    data class SetBearing(val bearing: Float) : MapEvent

    /** Accessibility zoom controls: step the map zoom in / out by one level. */
    data object ZoomIn : MapEvent
    data object ZoomOut : MapEvent

    /**
     * User tapped the map while the speed heatmap is active. Queries the average/maximum speed
     * within [radiusMeters] of the tapped point. Ignored when the speed heatmap is not the active
     * layer.
     */
    data class ProbeSpeedAt(val lat: Double, val lng: Double, val radiusMeters: Double) : MapEvent

    /** Dismiss the speed-probe callout. */
    data object DismissSpeedProbe : MapEvent

    /**
     * User tapped the map (no interactive layer active) to reveal the nearest place name via the
     * offline reverse geocoder.
     */
    data class ReverseGeocodeAt(val lat: Double, val lng: Double) : MapEvent

    /** Dismiss the reverse-geocode place callout. */
    data object DismissPlaceCallout : MapEvent
}

sealed interface MapEffect {
    data object ShowFollowCanceled : MapEffect
    data class CenterCamera(val bounds: CoordinateBounds) : MapEffect
    data class SetCameraBearing(val bearing: Float) : MapEffect
    data object ShowSearchFormatHint : MapEffect

    /** Animate the camera zoom by [delta] zoom levels (positive = in, negative = out). */
    data class ZoomBy(val delta: Float) : MapEffect
}
