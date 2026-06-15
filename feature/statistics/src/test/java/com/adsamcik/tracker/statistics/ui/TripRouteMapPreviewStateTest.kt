package com.adsamcik.tracker.statistics.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TripRouteMapPreviewStateTest {

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
