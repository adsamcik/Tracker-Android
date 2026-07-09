package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the engine's fourth render shape — circle markers ([MarkerAggregator] + [CircleEncoder]
 * over [SpatialData.Markers]).
 */
@DisplayName("Circle (marker) shape")
class MarkersTest {

	private fun point(lat: Double, weight: Double) = WeightedGeoFeature(lat, 14.0, 0L, weight)
	private fun ctx() = AggContext(zoom = 12f, quality = 1f, maxPoints = 5_000)

	@Nested
	@DisplayName("MarkerAggregator")
	inner class Aggregation {

		@Test
		fun `passes points through as markers`() {
			val markers = MarkerAggregator().aggregate(
				listOf(point(50.0, 0.2), point(51.0, 0.8)), ctx(),
			).points
			markers shouldHaveSize 2
		}

		@Test
		fun `caps to the heaviest markers when over budget`() {
			val many = (1..20).map { point(50.0 + it * 0.01, it / 20.0) }
			val markers = MarkerAggregator(maxMarkers = 5).aggregate(many, ctx()).points
			markers shouldHaveSize 5
			// Kept the heaviest five (weights 0.8..1.0).
			(markers.minOf { it.weight } >= 0.75) shouldBe true
		}
	}

	@Nested
	@DisplayName("CircleEncoder")
	inner class Encoding {

		private val encoder = CircleEncoder(
			colorStops = listOf(0.0f to 0xFF26A69A.toInt(), 1.0f to 0xFFEC407A.toInt()),
			minRadiusDp = 6f,
			maxRadiusDp = 26f,
			opacity = 0.85f,
		)

		@Test
		fun `empty field encodes to null`() {
			encoder.encode(SpatialData.Markers(emptyList()), RenderContext(1f)).shouldBeNull()
		}

		@Test
		fun `non-empty field encodes to a Circle config carrying weights and radii`() {
			val config = encoder.encode(
				SpatialData.Markers(listOf(point(50.0, 0.42))), RenderContext(1f),
			)
			config.shouldBeInstanceOf<MapLibreLayerConfig.Circle>()
			config.geoJson.contains(""""type":"Point"""") shouldBe true
			config.geoJson.contains(""""weight":0.42""") shouldBe true
			config.minRadiusDp shouldBe 6f
			config.maxRadiusDp shouldBe 26f
			config.weightProperty shouldBe "weight"
		}

		@Test
		fun `carries a render-time animation when supplied`() {
			val pulse = com.adsamcik.tracker.map.presentation.bridge.LayerAnimation.Pulse(
				periodMs = 1900, minScale = 0.9f, maxScale = 1.1f,
			)
			val animated = CircleEncoder(
				colorStops = listOf(0.0f to 0xFF000000.toInt(), 1.0f to 0xFFFFFFFF.toInt()),
				minRadiusDp = 6f, maxRadiusDp = 26f, animation = pulse,
			)
			val config = animated.encode(
				SpatialData.Markers(listOf(point(50.0, 0.5))), RenderContext(1f),
			) as MapLibreLayerConfig.Circle
			config.animation shouldBe pulse
		}
	}
}
