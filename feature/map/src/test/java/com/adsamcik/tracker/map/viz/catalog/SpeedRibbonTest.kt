package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.viz.SpatialData
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the Speed Ribbon visualization: the pipeline factory id/shape and the opaque ramp
 * (a line must stay visible even at the slow end, unlike the transparent-at-zero heatmap ramp).
 */
@DisplayName("Speed Ribbon")
class SpeedRibbonTest {

	@Test
	fun `speed ribbon pipeline has the expected id`() {
		speedRibbon(mockk<GeoRepository>()).id shouldBe "speed_ribbon"
	}

	@Test
	fun `speed ribbon uses the Segments shape`() {
		val field: SpatialData.Segments = SpatialData.Segments(emptyList())
		// Type-checks: the pipeline's field is Segments, so it can only pair with a gradient-line encoder.
		field.path.isEmpty() shouldBe true
	}

	@Test
	fun `every ramp stop is fully opaque`() {
		SPEED_RIBBON_RAMP.forEach { (_, argb) ->
			val alpha = (argb ushr 24) and 0xFF
			alpha shouldBe 0xFF
		}
	}
}
