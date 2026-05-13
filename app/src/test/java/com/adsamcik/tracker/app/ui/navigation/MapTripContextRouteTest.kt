package com.adsamcik.tracker.app.ui.navigation

import io.kotest.matchers.shouldBe
import org.junit.Test

class MapTripContextRouteTest {

	@Test
	fun `map trip context preserves selected trip navigation arguments`() {
		val route = MapTripContext(tripId = 42L, startMs = 1_700_000_000_000L, endMs = 1_700_000_900_000L)

		route.tripId shouldBe 42L
		route.startMs shouldBe 1_700_000_000_000L
		route.endMs shouldBe 1_700_000_900_000L
	}
}
