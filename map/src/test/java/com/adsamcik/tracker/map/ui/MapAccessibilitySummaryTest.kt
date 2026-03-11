package com.adsamcik.tracker.map.ui

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Map accessibility summary")
class MapAccessibilitySummaryTest {

    private val labels = MapAccessibilityLabels(
        mapOverview = "Map overview",
        centeredOnYourLocation = "Map centered on your location",
        showingYourLocation = "Map showing your location",
        loadingData = "Loading recorded map data",
        noRecordedData = "Selected layer has no recorded data yet",
        noLayerSelected = "No map layer selected",
        searchResultVisible = "Search result marker visible",
        activeRecordingVisible = "Active recording path visible",
        recordedRouteHistory = "Showing recorded route history",
        locationHeatmap = "Showing a location heatmap",
        cellHeatmap = "Showing a cell coverage heatmap",
        wifiHeatmap = "Showing a Wi-Fi heatmap",
        wifiCountHeatmap = "Showing a Wi-Fi count heatmap",
        speedHeatmap = "Showing a speed heatmap",
        savedMapData = "Showing saved map data",
        worldScale = "World scale",
        regionalScale = "Regional scale",
        cityScale = "City scale",
        neighborhoodScale = "Neighborhood scale",
        streetScale = "Street scale",
    )

    @Test
    fun `summarizes follow state and heatmap context without coordinates`() {
        val summary = buildMapAccessibilitySummary(
            isFollowing = true,
            hasUserLocation = true,
            hasSearchResult = false,
            isLoading = false,
            hasNoData = false,
            activeLayerIds = setOf("location_heatmap"),
            activeTrackingVisible = false,
            zoom = 12.5f,
            labels = labels,
        )

        summary shouldBe "Map centered on your location. Showing a location heatmap. Neighborhood scale."
    }

    @Test
    fun `prefers loading message while map data is loading`() {
        val summary = buildMapAccessibilitySummary(
            isFollowing = false,
            hasUserLocation = false,
            hasSearchResult = false,
            isLoading = true,
            hasNoData = false,
            activeLayerIds = setOf("location_polyline"),
            activeTrackingVisible = false,
            zoom = 4f,
            labels = labels,
        )

        summary shouldBe "Map overview. Loading recorded map data. World scale."
    }

    @Test
    fun `includes tracking and search markers in the summary`() {
        val summary = buildMapAccessibilitySummary(
            isFollowing = false,
            hasUserLocation = true,
            hasSearchResult = true,
            isLoading = false,
            hasNoData = false,
            activeLayerIds = emptySet(),
            activeTrackingVisible = true,
            zoom = 15f,
            labels = labels,
        )

        summary shouldContain "Map showing your location"
        summary shouldContain "Active recording path visible"
        summary shouldContain "Search result marker visible"
        summary shouldContain "Street scale"
    }
}
