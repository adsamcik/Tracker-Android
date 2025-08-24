package com.adsamcik.tracker.map.presentation.udf

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import com.google.android.gms.maps.model.TileProvider

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
    val isVisible: Boolean = true
)

@Immutable
data class LegendItem(
    val label: String,
    val color: Int
)

@Immutable
data class LatLngModel(val lat: Double, val lng: Double)

@Immutable
sealed interface MapOverlayState {
    @Immutable
    data class UserMarker(val latLng: LatLngModel, val bearing: Float? = null) : MapOverlayState
    @Immutable
    data class AccuracyCircle(val latLng: LatLngModel, val radiusM: Double) : MapOverlayState
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
    val hasFocus: Boolean = false
)

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
    // Phase 2 additions
    val quality: Float = 1f,
    val dateRange: LongRange = 0L..Long.MAX_VALUE,
    val tileGenerationInProgress: Int = 0,
    // Phase 3: expose active TileProvider for Compose TileOverlay
    val tileProvider: TileProvider? = null,
)

sealed interface MapEvent {
    data object ToggleFollow : MapEvent
    data object FollowCanceled : MapEvent
    data object ShowSheet : MapEvent
    data object HideSheet : MapEvent
    data class SelectLayer(val id: String) : MapEvent
    data class CameraMoved(val position: CameraModel, val byGesture: Boolean) : MapEvent
    data class SetQuality(val value: Float) : MapEvent
    data class SetDateRange(val range: LongRange) : MapEvent
    // Search
    data class UpdateSearchQuery(val query: String) : MapEvent
    data object SubmitSearch : MapEvent
    data class GeocodeResult(val bounds: com.google.android.gms.maps.model.LatLngBounds?) : MapEvent
    // Emitted by sensor pipeline to render user marker and accuracy circle declaratively
    data class SetUserLocation(val latLng: LatLngModel, val accuracyM: Double) : MapEvent
    // Emitted by sensor pipeline to update user/device bearing in degrees [0, 360)
    data class SetBearing(val bearing: Float) : MapEvent
}

sealed interface MapEffect {
    data object ShowFollowCanceled : MapEffect
    data class CenterCamera(val bounds: com.google.android.gms.maps.model.LatLngBounds) : MapEffect
    data class SetCameraBearing(val bearing: Float) : MapEffect
    // Ask host to perform geocoding for the given query (Android service)
    data class PerformGeocode(val query: String) : MapEffect
}
