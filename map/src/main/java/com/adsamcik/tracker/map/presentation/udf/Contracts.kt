package com.adsamcik.tracker.map.presentation.udf

import androidx.compose.runtime.Immutable
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
    data class UserMarker(val latLng: LatLngModel) : MapOverlayState
    @Immutable
    data class AccuracyCircle(val latLng: LatLngModel, val radiusM: Double) : MapOverlayState
}

@Immutable
data class SearchState(
    val query: String = "",
    val hasFocus: Boolean = false
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
)

sealed interface MapEvent {
    data object ToggleFollow : MapEvent
    data class SelectLayer(val id: String) : MapEvent
}

sealed interface MapEffect {
    data object ShowFollowCanceled : MapEffect
}
