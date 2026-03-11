package com.adsamcik.tracker.statistics.presenter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RouteEmptyReasonTest {

	@Test
	fun `returns null when route has enough points to render`() {
		val result = resolveRouteEmptyReason(
			routePointCount = 5,
			hasDistance = true,
			sourceLabel = "GPS",
		)
		assertNull(result)
	}

	@Test
	fun `returns null at minimum renderable threshold of 2 points`() {
		val result = resolveRouteEmptyReason(
			routePointCount = 2,
			hasDistance = true,
			sourceLabel = "GPS",
		)
		assertNull(result)
	}

	@Test
	fun `returns LEGACY_NO_ROUTE for legacy source with distance`() {
		val result = resolveRouteEmptyReason(
			routePointCount = 0,
			hasDistance = true,
			sourceLabel = "Legacy",
		)
		assertEquals(RouteEmptyReason.LEGACY_NO_ROUTE, result)
	}

	@Test
	fun `returns LEGACY_NO_ROUTE for legacy source without distance`() {
		val result = resolveRouteEmptyReason(
			routePointCount = 0,
			hasDistance = false,
			sourceLabel = "Legacy",
		)
		assertEquals(RouteEmptyReason.LEGACY_NO_ROUTE, result)
	}

	@Test
	fun `returns DISTANCE_NO_ROUTE when distance recorded but no points`() {
		val result = resolveRouteEmptyReason(
			routePointCount = 0,
			hasDistance = true,
			sourceLabel = "GPS",
		)
		assertEquals(RouteEmptyReason.DISTANCE_NO_ROUTE, result)
	}

	@Test
	fun `returns DISTANCE_NO_ROUTE with single point and distance`() {
		val result = resolveRouteEmptyReason(
			routePointCount = 1,
			hasDistance = true,
			sourceLabel = "Fused",
		)
		assertEquals(RouteEmptyReason.DISTANCE_NO_ROUTE, result)
	}

	@Test
	fun `returns NO_DATA when no points and no distance`() {
		val result = resolveRouteEmptyReason(
			routePointCount = 0,
			hasDistance = false,
			sourceLabel = "GPS",
		)
		assertEquals(RouteEmptyReason.NO_DATA, result)
	}

	@Test
	fun `returns NO_DATA when no points no distance and unknown source`() {
		val result = resolveRouteEmptyReason(
			routePointCount = 0,
			hasDistance = false,
			sourceLabel = "—",
		)
		assertEquals(RouteEmptyReason.NO_DATA, result)
	}

	@Test
	fun `legacy check is case-sensitive and does not match lowercase`() {
		val result = resolveRouteEmptyReason(
			routePointCount = 0,
			hasDistance = true,
			sourceLabel = "legacy",
		)
		assertEquals(RouteEmptyReason.DISTANCE_NO_ROUTE, result)
	}
}
