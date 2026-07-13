package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.presentation.bridge.LayerAnimation
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Signal Aurora encoder")
class SignalAuroraTest {

	@Test
	fun `encodes one field as a broad halo and tighter bright core`() {
		val field = SpatialData.WeightedCells(
			listOf(WeightedGeoFeature(50.0, 14.0, 0L, 0.8)),
		)

		val config = AuroraEncoder(
			haloColorStops = listOf(0f to 0, 1f to 0xFF7E57C2.toInt()),
			coreColorStops = listOf(0f to 0, 1f to 0xFFFFFFFF.toInt()),
		).encode(field, RenderContext(quality = 1f))

		config.shouldBeInstanceOf<MapLibreLayerConfig.Composite>()
		config.layers shouldHaveSize 2
		val halo = config.layers[0].shouldBeInstanceOf<MapLibreLayerConfig.Heatmap>()
		val core = config.layers[1].shouldBeInstanceOf<MapLibreLayerConfig.Heatmap>()
		(halo.radiusPx > core.radiusPx) shouldBe true
		halo.animation.shouldBeInstanceOf<LayerAnimation.Pulse>()
		core.animation.shouldBeInstanceOf<LayerAnimation.Pulse>()
	}
}
