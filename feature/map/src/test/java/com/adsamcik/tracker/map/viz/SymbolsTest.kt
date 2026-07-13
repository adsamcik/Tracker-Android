package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Symbol shape")
class SymbolsTest {

	private fun point(kind: String, priority: Float, label: String = "Label") = SymbolFeature(
		lat = 50.0 + priority,
		lon = 14.0,
		time = priority.toLong(),
		label = label,
		iconKey = kind,
		priority = priority,
	)

	private fun ctx() = AggContext(zoom = 12f, quality = 1f, maxPoints = 5_000)

	@Nested
	inner class Aggregation {

		@Test
		fun `caps symbols by priority`() {
			val points = listOf(point("a", 1f), point("a", 3f), point("a", 2f))

			val symbols = SymbolAggregator(maxSymbols = 2).aggregate(points, ctx()).points

			symbols.map { it.priority }.sortedDescending() shouldBe listOf(3f, 2f)
		}
	}

	@Nested
	inner class Encoding {

		private val encoder = SymbolEncoder(
			styles = mapOf(
				"a" to SymbolStyle(iconRes = 101, iconColorArgb = 0xFF00AAFF.toInt()),
				"b" to SymbolStyle(iconRes = 202, iconColorArgb = 0xFFFFAA00.toInt()),
			),
		)

		@Test
		fun `empty symbols encode to null`() {
			encoder.encode(SpatialData.Symbols(emptyList()), RenderContext(1f)).shouldBeNull()
		}

		@Test
		fun `one icon family encodes to one symbol layer with escaped labels`() {
			val config = encoder.encode(
				SpatialData.Symbols(listOf(point("a", 1f, "Run \"North\"\nFast"))),
				RenderContext(1f),
			)

			config.shouldBeInstanceOf<MapLibreLayerConfig.Symbol>()
			config.iconRes shouldBe 101
			config.geoJson shouldContain """"label":"Run \"North\"\nFast""""
		}

		@Test
		fun `different icon families encode as a flat composite`() {
			val config = encoder.encode(
				SpatialData.Symbols(listOf(point("a", 2f), point("b", 1f))),
				RenderContext(1f),
			)

			config.shouldBeInstanceOf<MapLibreLayerConfig.Composite>()
			config.layers shouldHaveSize 2
			config.layers.forEach { it.shouldBeInstanceOf<MapLibreLayerConfig.Symbol>() }
		}
	}
}
