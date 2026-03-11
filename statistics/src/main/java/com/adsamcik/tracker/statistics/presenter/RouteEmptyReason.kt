package com.adsamcik.tracker.statistics.presenter

/**
 * Describes why the route map cannot be displayed for a trip.
 * Used to select a contextual empty-state message in the UI.
 */
enum class RouteEmptyReason {
	/** Legacy-migrated session — GPS coordinates were not preserved. */
	LEGACY_NO_ROUTE,

	/** Distance was recorded but no GPS points are available for rendering. */
	DISTANCE_NO_ROUTE,

	/** No distance and no GPS points — truly empty session. */
	NO_DATA,
}

/**
 * Pure function that determines why a route map is empty, or `null` when the route can render.
 *
 * @param routePointCount number of GPS points available for the trip
 * @param hasDistance      whether the session recorded a non-zero distance
 * @param sourceLabel     the resolved source label (e.g. "Legacy", "GPS", "Fused")
 */
fun resolveRouteEmptyReason(
	routePointCount: Int,
	hasDistance: Boolean,
	sourceLabel: String,
): RouteEmptyReason? {
	if (routePointCount >= 2) return null
	return when {
		sourceLabel == "Legacy" -> RouteEmptyReason.LEGACY_NO_ROUTE
		hasDistance -> RouteEmptyReason.DISTANCE_NO_ROUTE
		else -> RouteEmptyReason.NO_DATA
	}
}
