package com.adsamcik.tracker.map.ui

internal data class MapAccessibilityLabels(
    val mapOverview: String,
    val centeredOnYourLocation: String,
    val showingYourLocation: String,
    val loadingData: String,
    val noRecordedData: String,
    val noLayerSelected: String,
    val searchResultVisible: String,
    val activeRecordingVisible: String,
    val recordedRouteHistory: String,
    val observedPresence: String,
    val locationHeatmap: String,
    val cellHeatmap: String,
    val wifiHeatmap: String,
    val wifiCountHeatmap: String,
    val speedHeatmap: String,
    val savedMapData: String,
    val worldScale: String,
    val regionalScale: String,
    val cityScale: String,
    val neighborhoodScale: String,
    val streetScale: String,
)

internal fun buildMapAccessibilitySummary(
    isFollowing: Boolean,
    hasUserLocation: Boolean,
    hasSearchResult: Boolean,
    isLoading: Boolean,
    hasNoData: Boolean,
    activeLayerIds: Set<String>,
    activeTrackingVisible: Boolean,
    zoom: Float,
    labels: MapAccessibilityLabels,
): String {
    val sentences = mutableListOf(
        when {
            isFollowing && hasUserLocation -> labels.centeredOnYourLocation
            hasUserLocation -> labels.showingYourLocation
            else -> labels.mapOverview
        }
    )

    when {
        isLoading -> sentences += labels.loadingData
        hasNoData -> sentences += labels.noRecordedData
        else -> describeVisibleContent(
            activeLayerIds = activeLayerIds,
            activeTrackingVisible = activeTrackingVisible,
            labels = labels,
        )?.let(sentences::add)
    }

    if (hasSearchResult) {
        sentences += labels.searchResultVisible
    }

    sentences += zoomLevelSummary(zoom = zoom, labels = labels)

    return sentences.joinToString(separator = ". ", postfix = ".")
}

private fun describeVisibleContent(
    activeLayerIds: Set<String>,
    activeTrackingVisible: Boolean,
    labels: MapAccessibilityLabels,
): String? {
    val content = linkedSetOf<String>()

    activeLayerIds.forEach { layerId ->
        content += when (layerId) {
            "location_polyline" -> labels.recordedRouteHistory
			"observed_presence" -> labels.observedPresence
            "location_heatmap" -> labels.locationHeatmap
            "cell_heatmap" -> labels.cellHeatmap
            "wifi_heatmap" -> labels.wifiHeatmap
            "wifi_count_heatmap" -> labels.wifiCountHeatmap
            "speed_heatmap" -> labels.speedHeatmap
            else -> labels.savedMapData
        }
    }

    if (activeTrackingVisible) {
        content += labels.activeRecordingVisible
    }

    return when {
        content.isNotEmpty() -> content.joinToString(separator = ". ")
        else -> labels.noLayerSelected
    }
}

private fun zoomLevelSummary(
    zoom: Float,
    labels: MapAccessibilityLabels,
): String = when {
    zoom < 5f -> labels.worldScale
    zoom < 8f -> labels.regionalScale
    zoom < 11f -> labels.cityScale
    zoom < 14f -> labels.neighborhoodScale
    else -> labels.streetScale
}
