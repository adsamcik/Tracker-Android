package com.adsamcik.tracker.map.presentation.bridge

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("MapLibreLayerConfig")
class MapLibreLayerConfigTest {

	@Nested
	@DisplayName("Heatmap")
	inner class HeatmapTests {

		@Test
		fun `stores all fields`() {
			val colorStops = listOf(0.0f to 0xFF0000, 1.0f to 0x00FF00)
			val config = MapLibreLayerConfig.Heatmap(
				geoJson = """{"type":"FeatureCollection"}""",
				colorStops = colorStops,
				radiusPx = 30f,
				intensity = 0.8f,
				opacity = 0.5f,
				weightProperty = "score"
			)
			config.geoJson shouldBe """{"type":"FeatureCollection"}"""
			config.colorStops shouldBe colorStops
			config.radiusPx shouldBe 30f
			config.intensity shouldBe 0.8f
			config.opacity shouldBe 0.5f
			config.weightProperty shouldBe "score"
		}

		@Test
		fun `default values`() {
			val config = MapLibreLayerConfig.Heatmap(
				geoJson = "{}",
				colorStops = emptyList()
			)
			config.radiusPx shouldBe 20f
			config.intensity shouldBe 1f
			config.opacity shouldBe 0.8f
			config.weightProperty shouldBe "weight"
		}

		@Test
		fun `is MapLibreLayerConfig`() {
			val config: MapLibreLayerConfig = MapLibreLayerConfig.Heatmap(
				geoJson = "{}",
				colorStops = emptyList()
			)
			config.shouldBeInstanceOf<MapLibreLayerConfig.Heatmap>()
		}

		@Test
		fun `equality works`() {
			val stops = listOf(0.5f to 0xABCDEF)
			val c1 = MapLibreLayerConfig.Heatmap(geoJson = "a", colorStops = stops)
			val c2 = MapLibreLayerConfig.Heatmap(geoJson = "a", colorStops = stops)
			c1 shouldBe c2
			c1.hashCode() shouldBe c2.hashCode()
		}

		@Test
		fun `inequality for different geoJson`() {
			val stops = listOf(0.5f to 0xABCDEF)
			val c1 = MapLibreLayerConfig.Heatmap(geoJson = "a", colorStops = stops)
			val c2 = MapLibreLayerConfig.Heatmap(geoJson = "b", colorStops = stops)
			c1 shouldNotBe c2
		}

		@Test
		fun `render key ignores heatmap geoJson changes`() {
			val stops = listOf(0.5f to 0xABCDEF)
			val c1 = MapLibreLayerConfig.Heatmap(geoJson = "a", colorStops = stops)
			val c2 = MapLibreLayerConfig.Heatmap(geoJson = "b", colorStops = stops)

			c1.renderKey(index = 0) shouldBe c2.renderKey(index = 0)
		}

		@Test
		fun `render key changes when heatmap style changes`() {
			val c1 = MapLibreLayerConfig.Heatmap(geoJson = "a", colorStops = listOf(0.5f to 0xABCDEF))
			val c2 = MapLibreLayerConfig.Heatmap(geoJson = "a", colorStops = listOf(1.0f to 0xABCDEF))

			c1.renderKey(index = 0) shouldNotBe c2.renderKey(index = 0)
		}
	}

	@Nested
	@DisplayName("Line")
	inner class LineTests {

		@Test
		fun `stores all fields`() {
			val config = MapLibreLayerConfig.Line(
				geoJson = """{"line":true}""",
				colorArgb = 0xFF00FF00.toInt(),
				widthDp = 8f,
				opacity = 0.6f
			)
			config.geoJson shouldBe """{"line":true}"""
			config.colorArgb shouldBe 0xFF00FF00.toInt()
			config.widthDp shouldBe 8f
			config.opacity shouldBe 0.6f
		}

		@Test
		fun `default values`() {
			val config = MapLibreLayerConfig.Line(
				geoJson = "{}",
				colorArgb = 0
			)
			config.widthDp shouldBe 4f
			config.opacity shouldBe 1f
		}

		@Test
		fun `is MapLibreLayerConfig`() {
			val config: MapLibreLayerConfig = MapLibreLayerConfig.Line(
				geoJson = "{}",
				colorArgb = 0
			)
			config.shouldBeInstanceOf<MapLibreLayerConfig.Line>()
		}

		@Test
		fun `equality works`() {
			val c1 = MapLibreLayerConfig.Line(geoJson = "x", colorArgb = 123)
			val c2 = MapLibreLayerConfig.Line(geoJson = "x", colorArgb = 123)
			c1 shouldBe c2
		}
	}

	@Nested
	@DisplayName("Composite")
	inner class CompositeTests {

		@Test
		fun `stores layers`() {
			val heatmap = MapLibreLayerConfig.Heatmap(geoJson = "h", colorStops = emptyList())
			val line = MapLibreLayerConfig.Line(geoJson = "l", colorArgb = 0)
			val composite = MapLibreLayerConfig.Composite(layers = listOf(heatmap, line))
			composite.layers shouldHaveSize 2
			composite.layers[0].shouldBeInstanceOf<MapLibreLayerConfig.Heatmap>()
			composite.layers[1].shouldBeInstanceOf<MapLibreLayerConfig.Line>()
		}

		@Test
		fun `empty composite`() {
			val composite = MapLibreLayerConfig.Composite(layers = emptyList())
			composite.layers.shouldBeEmpty()
		}

		@Test
		fun `nested composite`() {
			val inner = MapLibreLayerConfig.Composite(
				layers = listOf(MapLibreLayerConfig.Line(geoJson = "l", colorArgb = 0))
			)
			val outer = MapLibreLayerConfig.Composite(layers = listOf(inner))
			outer.layers shouldHaveSize 1
			outer.layers[0].shouldBeInstanceOf<MapLibreLayerConfig.Composite>()
		}

		@Test
		fun `is MapLibreLayerConfig`() {
			val config: MapLibreLayerConfig = MapLibreLayerConfig.Composite(layers = emptyList())
			config.shouldBeInstanceOf<MapLibreLayerConfig.Composite>()
		}

		@Test
		fun `equality works`() {
			val layers = listOf(MapLibreLayerConfig.Line(geoJson = "x", colorArgb = 1))
			val c1 = MapLibreLayerConfig.Composite(layers = layers)
			val c2 = MapLibreLayerConfig.Composite(layers = layers)
			c1 shouldBe c2
		}
	}

	@Nested
	@DisplayName("Sealed hierarchy")
	inner class SealedHierarchyTests {

		@Test
		fun `when expression covers all subtypes`() {
			val configs: List<MapLibreLayerConfig> = listOf(
				MapLibreLayerConfig.Heatmap(geoJson = "", colorStops = emptyList()),
				MapLibreLayerConfig.Line(geoJson = "", colorArgb = 0),
				MapLibreLayerConfig.Fill(geoJson = "", colorStops = emptyList()),
				MapLibreLayerConfig.Composite(layers = emptyList()),
			)
			val labels = configs.map { config ->
				when (config) {
					is MapLibreLayerConfig.Heatmap -> "heatmap"
					is MapLibreLayerConfig.Line -> "line"
					is MapLibreLayerConfig.Fill -> "fill"
					is MapLibreLayerConfig.Composite -> "composite"
				}
			}
			labels shouldBe listOf("heatmap", "line", "fill", "composite")
		}
	}
}
