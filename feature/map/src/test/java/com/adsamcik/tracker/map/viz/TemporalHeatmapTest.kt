package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Temporal heatmap")
class TemporalHeatmapTest {

	private fun point(
		lat: Double = 50.0,
		lon: Double = 14.0,
		time: Long,
		weight: Double = 1.0,
	) = WeightedGeoFeature(lat, lon, time, weight)

	private fun ctx(maxPoints: Int = 1_000) = AggContext(zoom = 17f, quality = 1f, maxPoints = maxPoints)

	@Nested
	@DisplayName("visit density")
	inner class VisitDensity {
		private val aggregator = TemporalHeatmapAggregator(TemporalHeatMetric.VisitDensity)

		@Test
		fun `heat is independent of collection frequency within one visit`() {
			val everyTwoSeconds = (0L..60L step 2L).map { point(time = it * 1_000L) }
			val everyTenSeconds = (0L..60L step 10L).map { point(time = it * 1_000L) }

			val denseCadenceHeat = aggregator.aggregate(everyTwoSeconds, ctx()).isolatedCells.single().weight
			val sparseCadenceHeat = aggregator.aggregate(everyTenSeconds, ctx()).isolatedCells.single().weight

			denseCadenceHeat shouldBe (sparseCadenceHeat plusOrMinus 1e-12)
		}

		@Test
		fun `an hour in one place remains a cool single visit`() {
			val oneHour = (0L..3_600L step 10L).map { point(time = it * 1_000L) }

			val heat = aggregator.aggregate(oneHour, ctx()).isolatedCells.single().weight

			(heat < 0.3) shouldBe true
		}

		@Test
		fun `short interval return contributes much less than another day`() {
			val afterOneHour = aggregator.aggregate(
				listOf(point(time = 0L), point(time = 60L * 60L * 1_000L)),
				ctx(),
			).isolatedCells.single().weight
			val afterOneDay = aggregator.aggregate(
				listOf(point(time = 0L), point(time = 24L * 60L * 60L * 1_000L)),
				ctx(),
			).isolatedCells.single().weight

			(afterOneDay > afterOneHour) shouldBe true
			(afterOneHour < 0.3) shouldBe true
		}

		@Test
		fun `duplicate timestamps do not manufacture visits`() {
			val one = aggregator.aggregate(listOf(point(time = 1_000L)), ctx())
				.isolatedCells.single().weight
			val duplicated = aggregator.aggregate(
				List(100) { point(time = 1_000L, weight = if (it == 99) 1.0 else 0.5) },
				ctx(),
			).isolatedCells.single().weight

			duplicated shouldBe (one plusOrMinus 1e-12)
		}

		@Test
		fun `location confidence reduces false heat`() {
			val highConfidence = listOf(point(time = 0L), point(time = 10_000L))
			val lowConfidence = listOf(point(time = 0L, weight = 0.2), point(time = 10_000L, weight = 0.2))

			val high = aggregator.aggregate(highConfidence, ctx()).isolatedCells.single().weight
			val low = aggregator.aggregate(lowConfidence, ctx()).isolatedCells.single().weight

			(low < high) shouldBe true
		}

		@Test
		fun `continuous movement becomes a path instead of overlapping heat points`() {
			val field = aggregator.aggregate(
				listOf(
					point(lat = 50.0, time = 0L),
					point(lat = 50.0001, time = 10_000L),
					point(lat = 50.0002, time = 20_000L),
				),
				ctx(),
			)

			field.paths shouldHaveSize 1
			field.paths.single() shouldHaveSize 3
			field.isolatedCells shouldHaveSize 0
		}

		@Test
		fun `a cell represented by a segment never also emits a point`() {
			val field = aggregator.aggregate(
				listOf(
					// Earlier stationary observation in the first path cell.
					point(lat = 50.0, time = 0L),
					// Later continuous movement starts in that same cell.
					point(lat = 50.0, time = 3_600_000L),
					point(lat = 50.0001, time = 3_610_000L),
				),
				ctx(),
			)

			field.paths shouldHaveSize 1
			field.isolatedCells shouldHaveSize 0
		}

		@Test
		fun `twenty second gap remains connected`() {
			val field = aggregator.aggregate(
				listOf(
					point(lat = 50.0, time = 0L),
					point(lat = 50.0001, time = 20_000L),
				),
				ctx(),
			)

			field.paths shouldHaveSize 1
		}

		@Test
		fun `gap beyond twenty seconds never bridges with a line`() {
			val field = aggregator.aggregate(
				listOf(
					point(lat = 50.0, time = 0L),
					point(lat = 50.0001, time = 20_001L),
				),
				ctx(),
			)

			field.paths shouldHaveSize 0
			field.isolatedCells shouldHaveSize 2
		}

		@Test
		fun `impossible teleport never bridges with a line`() {
			val field = aggregator.aggregate(
				listOf(
					point(lat = 50.0, time = 0L),
					point(lat = 51.0, time = 1_000L),
				),
				ctx(),
			)

			field.paths shouldHaveSize 0
			field.isolatedCells shouldHaveSize 2
		}

		@Test
		fun `poor accuracy jitter remains point heat`() {
			val field = aggregator.aggregate(
				listOf(
					point(lat = 50.0, time = 0L, weight = 0.5),
					point(lat = 50.0001, time = 10_000L, weight = 0.5),
					point(lat = 50.0002, time = 20_000L, weight = 0.5),
				),
				ctx(),
			)

			field.paths shouldHaveSize 0
			(field.isolatedCells.isNotEmpty()) shouldBe true
		}
	}

	@Nested
	@DisplayName("average value")
	inner class AverageValue {
		private val aggregator = TemporalHeatmapAggregator(TemporalHeatMetric.AverageValue)

		@Test
		fun `cell value is a time weighted mean`() {
			val field = aggregator.aggregate(
				listOf(
					point(time = 0L, weight = 0.2),
					point(time = 10_000L, weight = 0.8),
					point(time = 20_000L, weight = 0.8),
				),
				ctx(),
			)

			field.isolatedCells.single().weight shouldBe (0.65 plusOrMinus 1e-12)
		}

		@Test
		fun `reported movement and recent positions produce a speed path`() {
			val field = aggregator.aggregate(
				listOf(
					point(lat = 50.0, time = 0L, weight = 0.1),
					point(lat = 50.0001, time = 10_000L, weight = 0.1),
				),
				ctx(),
			)

			field.paths shouldHaveSize 1
		}
	}

	@Nested
	@DisplayName("encoder")
	inner class Encoder {
		private val encoder = TemporalHeatmapEncoder(
			colorStops = listOf(0f to 0x00000000, 1f to 0xFFFF0000.toInt()),
			baseRadiusPx = 18f,
		)

		@Test
		fun `encodes point heat and movement line as separate composite children`() {
			val field = SpatialData.HeatField(
				isolatedCells = listOf(point(time = 0L, weight = 0.4)),
				paths = listOf(
					listOf(
						point(lat = 50.0, time = 0L, weight = 0.2),
						point(lat = 50.001, time = 10_000L, weight = 0.6),
					),
				),
			)

			val config = encoder.encode(field, RenderContext(quality = 1f))
				.shouldBeInstanceOf<MapLibreLayerConfig.Composite>()
			config.layers shouldHaveSize 3
			val pointGlow = config.layers[0].shouldBeInstanceOf<MapLibreLayerConfig.Circle>()
			val pointCore = config.layers[1].shouldBeInstanceOf<MapLibreLayerConfig.Circle>()
			val lineLayer = config.layers[2].shouldBeInstanceOf<MapLibreLayerConfig.HeatLine>()
			pointGlow.minRadiusDp shouldBe 18f
			pointGlow.maxRadiusDp shouldBe 18f
			// A line's complete width equals the corresponding point's diameter.
			lineLayer.glowWidthDp shouldBe pointGlow.minRadiusDp * 2f
			lineLayer.widthDp shouldBe pointCore.minRadiusDp * 2f
		}

		@Test
		fun `movement-only field does not emit point kernels`() {
			val field = SpatialData.HeatField(
				isolatedCells = emptyList(),
				paths = listOf(
					listOf(
						point(lat = 50.0, time = 0L, weight = 0.2),
						point(lat = 50.001, time = 10_000L, weight = 0.6),
					),
				),
			)

			val config = encoder.encode(field, RenderContext(quality = 1f))
				.shouldBeInstanceOf<MapLibreLayerConfig.Composite>()
			config.layers shouldHaveSize 1
			config.layers.single().shouldBeInstanceOf<MapLibreLayerConfig.HeatLine>()
		}

		@Test
		fun `empty field has no render children`() {
			val config = encoder.encode(
				SpatialData.HeatField(emptyList(), emptyList()),
				RenderContext(quality = 1f),
			).shouldBeInstanceOf<MapLibreLayerConfig.Composite>()

			config.layers shouldHaveSize 0
		}
	}
}
