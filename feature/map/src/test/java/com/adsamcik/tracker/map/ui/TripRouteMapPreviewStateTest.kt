package com.adsamcik.tracker.map.ui

import com.adsamcik.tracker.feature.map.api.preview.RoutePoint
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TripRouteMapPreviewStateTest {

	@Test
	fun `saved route draws measured sections without a line across the gap`() {
		val sections = routePreviewSections(listOf(
			RoutePoint(37.0, -122.0, 0), RoutePoint(37.001, -122.0, 0),
			RoutePoint(52.0, 13.0, 1), RoutePoint(52.001, 13.0, 1),
		))

		sections shouldBe listOf(
			listOf(LatLngModel(37.0, -122.0), LatLngModel(37.001, -122.0)),
			listOf(LatLngModel(52.0, 13.0), LatLngModel(52.001, 13.0)),
		)
		GeoJsonConverter.segmentsToFeatureCollection(sections) shouldBe
			"""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[[-122.0,37.0],[-122.0,37.001]]},"properties":{}},{"type":"Feature","geometry":{"type":"LineString","coordinates":[[13.0,52.0],[13.0,52.001]]},"properties":{}}]}"""
	}

	@Test
	fun `an invalid preview point cannot bridge its valid neighbours`() {
		routePreviewSections(listOf(RoutePoint(50.0, 14.0), RoutePoint(Double.NaN, 14.0),
			RoutePoint(50.001, 14.001))) shouldBe listOf(
			listOf(LatLngModel(50.0, 14.0)), listOf(LatLngModel(50.001, 14.001)))
	}

	@Test
	fun `preview is loading while MapLibre is still initializing`() {
		tripPreviewContentState(
			hasBaseStyle = true,
			mapLibreReady = false,
			routePointCount = 2,
			preparingBasemap = false,
			mapLoadFailed = false,
		) shouldBe TripPreviewContentState.Loading
	}

	@Test
	fun `preview reports unavailable when initialization fails`() {
		tripPreviewContentState(
			hasBaseStyle = true,
			mapLibreReady = false,
			routePointCount = 2,
			preparingBasemap = false,
			mapLoadFailed = true,
		) shouldBe TripPreviewContentState.Error
	}

	@Test
	fun `preview reports empty when there are no route points`() {
		tripPreviewContentState(
			hasBaseStyle = true,
			mapLibreReady = true,
			routePointCount = 1,
			preparingBasemap = false,
			mapLoadFailed = false,
		) shouldBe TripPreviewContentState.Error
	}

	@Test
	fun `preview is ready only when map and route are available`() {
		tripPreviewContentState(
			hasBaseStyle = true,
			mapLibreReady = true,
			routePointCount = 2,
			preparingBasemap = false,
			mapLoadFailed = false,
		) shouldBe TripPreviewContentState.Map
	}

	@Test
	fun `preview tears down the map (Loading) while host is not visible`() {
		tripPreviewContentState(
			hasBaseStyle = true,
			mapLibreReady = true,
			routePointCount = 2,
			preparingBasemap = false,
			mapLoadFailed = false,
			isVisible = false,
		) shouldBe TripPreviewContentState.Loading
	}
}
