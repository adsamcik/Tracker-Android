package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.presentation.bridge.LayerAnimation
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.bridge.buildFlowGradientStops
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Arc shape")
class ArcsTest {

	private fun trip(
		group: String,
		category: String = "car",
		startLat: Double = 50.0,
		startLon: Double = 14.0,
		endLat: Double = 50.2,
		endLon: Double = 14.4,
		time: Long = 0L,
	) = ArcFeature(startLat, startLon, endLat, endLon, time, group, category)

	private val context = AggContext(zoom = 8f, quality = 1f, maxPoints = 100)

	@Nested
	inner class Aggregation {

		@Test
		fun `groups repeated directed journeys and creates a deterministic curve`() {
			val features = listOf(
				trip("home-work-car", time = 1L),
				trip("home-work-car", time = 2L),
			)

			val first = ArcAggregator(curvePoints = 12).aggregate(features, context).arcs.single()
			val second = ArcAggregator(curvePoints = 12).aggregate(features, context).arcs.single()

			first.points shouldBe second.points
			first.frequency shouldBe 2
			first.points shouldHaveSize 12
			first.points.first().lat shouldBe 50.0
			first.points.last().lat shouldBe 50.2
			first.weight shouldBeGreaterThan 0f
		}

		@Test
		fun `keeps transport modes as separate visual flows`() {
			val arcs = ArcAggregator().aggregate(
				listOf(
					trip("home-work-car", category = "car"),
					trip("home-work-walk", category = "walk"),
				),
				context,
			).arcs

			arcs shouldHaveSize 2
		}

		@Test
		fun `uses the shortest longitude path across the antimeridian`() {
			val arc = ArcAggregator(curvePoints = 12).aggregate(
				listOf(trip("dateline", startLon = 179.0, endLon = -179.0)),
				context,
			).arcs.single()

			arc.points.last().lng shouldBe 181.0
			arc.points.zipWithNext().maxOf { (first, second) ->
				kotlin.math.abs(second.lng - first.lng)
			} shouldBeLessThan 1.0
		}

		@Test
		fun `drops dateline-equivalent zero-length endpoints without NaN geometry`() {
			ArcAggregator().aggregate(
				listOf(trip("same-dateline", startLat = 0.0, endLat = 0.0, startLon = -180.0, endLon = 180.0)),
				context,
			).arcs.shouldBeEmpty()
		}
	}

	@Nested
	inner class Encoding {

		@Test
		fun `encodes each arc as a stable base plus animated comet line`() {
			val arc = FlowArc(
				points = listOf(
					com.adsamcik.tracker.map.presentation.udf.LatLngModel(50.0, 14.0),
					com.adsamcik.tracker.map.presentation.udf.LatLngModel(50.1, 14.2),
				),
				category = "car",
				frequency = 3,
				weight = 0.6f,
				phaseOffset = 0.25f,
			)

			val config = ArcEncoder().encode(SpatialData.Arcs(listOf(arc)), RenderContext(1f))

			config.shouldBeInstanceOf<MapLibreLayerConfig.Composite>()
			config.layers shouldHaveSize 2
			config.layers[0].shouldBeInstanceOf<MapLibreLayerConfig.Line>()
			val comet = config.layers[1].shouldBeInstanceOf<MapLibreLayerConfig.GradientLine>()
			comet.animation.shouldBeInstanceOf<LayerAnimation.Flow>()
		}
	}

	@Test
	fun `flow gradient is monotonic and loops through transparent tails`() {
		val stops = buildFlowGradientStops(
			colorArgb = 0xFFFF8800.toInt(),
			phase = 0.35f,
			trailFraction = 0.2f,
		)

		stops.first().first shouldBe 0f
		stops.last().first shouldBe 1f
		stops.zipWithNext().all { (a, b) -> b.first > a.first } shouldBe true
		stops.any { (_, color) -> (color ushr 24) == 0 } shouldBe true
		stops.any { (_, color) -> (color ushr 24) > 200 } shouldBe true
	}
}
