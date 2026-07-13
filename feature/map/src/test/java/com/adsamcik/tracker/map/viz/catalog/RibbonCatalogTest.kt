package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.GeoRepository
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Altitude and activity ribbons")
class RibbonCatalogTest {

	@Test
	fun `altitude uses a stable global normalization window`() {
		altitudeWeight(-500.0).shouldBeExactly(0.0)
		altitudeWeight(-100.0).shouldBeExactly(0.0)
		altitudeWeight(1_950.0).shouldBeExactly(0.5)
		altitudeWeight(4_000.0).shouldBeExactly(1.0)
		altitudeWeight(8_000.0).shouldBeExactly(1.0)
	}

	@Test
	fun `catalog pipelines expose stable ids`() {
		val repo = mockk<GeoRepository>()

		altitudeRibbon(repo).id shouldBe "altitude_ribbon"
		activityRibbon(repo).id shouldBe "activity_ribbon"
	}

	@Test
	fun `ribbon ramps stay opaque along the complete route`() {
		(ALTITUDE_RIBBON_RAMP + ACTIVITY_RIBBON_RAMP).forEach { (_, argb) ->
			((argb ushr 24) and 0xFF) shouldBe 0xFF
		}
	}
}
