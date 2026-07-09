package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the engine's fifth render shape — attributed gradient ribbons ([SegmentsAggregator] +
 * [GradientLineEncoder] over [SpatialData.Segments]), plus the gradient-stop and ramp helpers.
 */
@DisplayName("GradientLine (ribbon) shape")
class RibbonTest {

	/** lon increases with index so consecutive fixes are a real distance apart. */
	private fun fix(index: Int, weight: Double, time: Long = index.toLong()) =
		WeightedGeoFeature(lat = 50.0, lon = 14.0 + index * 0.001, time = time, weight = weight)

	private fun ctx(maxPoints: Int = 5_000) = AggContext(zoom = 12f, quality = 1f, maxPoints = maxPoints)

	@Nested
	@DisplayName("SegmentsAggregator")
	inner class Aggregation {

		@Test
		fun `orders fixes by time into a path`() {
			val unordered = listOf(fix(2, 0.3, time = 30), fix(0, 0.1, time = 10), fix(1, 0.2, time = 20))
			val path = SegmentsAggregator().aggregate(unordered, ctx()).path
			path.map { it.time } shouldBe listOf(10L, 20L, 30L)
		}

		@Test
		fun `down-samples over budget while keeping the real endpoints`() {
			val many = (0..99).map { fix(it, it / 100.0) }
			val path = SegmentsAggregator(maxPoints = 10).aggregate(many, ctx(maxPoints = 10)).path
			path.size shouldBeLessThanOrEqualTo 10
			path.first() shouldBe many.first()
			path.last() shouldBe many.last()
		}

		@Test
		fun `passes a short path through untouched`() {
			val two = listOf(fix(0, 0.1), fix(1, 0.9))
			SegmentsAggregator().aggregate(two, ctx()).path shouldBe two
		}
	}

	@Nested
	@DisplayName("GradientLineEncoder")
	inner class Encoding {

		private val ramp = listOf(0f to 0xFF0000FF.toInt(), 1f to 0xFFFF0000.toInt())
		private val encoder = GradientLineEncoder(colorStops = ramp, widthDp = 6f)

		@Test
		fun `empty or single-point path encodes to nothing`() {
			encoder.encode(SpatialData.Segments(emptyList()), RenderContext(1f)).shouldBeNull()
			encoder.encode(SpatialData.Segments(listOf(fix(0, 0.5))), RenderContext(1f)).shouldBeNull()
		}

		@Test
		fun `encodes a path to a GradientLine with monotonic stops spanning 0 to 1`() {
			val path = (0..4).map { fix(it, it / 4.0) }
			val config = encoder.encode(SpatialData.Segments(path), RenderContext(1f))
			config.shouldBeInstanceOf<MapLibreLayerConfig.GradientLine>()
			config.geoJson.contains(""""type":"LineString"""") shouldBe true
			config.gradientStops.first().first shouldBe 0f
			config.gradientStops.last().first shouldBe 1f
			// Strictly increasing progress (a hard MapLibre requirement).
			config.gradientStops.zipWithNext().all { (a, b) -> b.first > a.first } shouldBe true
		}
	}

	@Nested
	@DisplayName("buildGradientStops")
	inner class GradientStops {

		@Test
		fun `collapses zero-length steps so progress never repeats`() {
			// Two identical coordinates in the middle produce a zero-length step.
			val ramp = listOf(0f to 0xFF000000.toInt(), 1f to 0xFFFFFFFF.toInt())
			val path = listOf(fix(0, 0.0), fix(1, 0.5), fix(1, 0.6), fix(2, 1.0))
			val stops = buildGradientStops(path, ramp)
			stops.zipWithNext().all { (a, b) -> b.first > a.first } shouldBe true
			stops.first().first shouldBe 0f
			stops.last().first shouldBe 1f
		}

		@Test
		fun `a degenerate zero-length path yields no stops`() {
			val ramp = listOf(0f to 0xFF000000.toInt(), 1f to 0xFFFFFFFF.toInt())
			val samePoint = listOf(fix(0, 0.0), fix(0, 1.0))
			buildGradientStops(samePoint, ramp) shouldHaveSize 0
		}
	}

	@Nested
	@DisplayName("sampleRamp")
	inner class Ramp {

		private val ramp = listOf(0f to 0xFF000000.toInt(), 1f to 0xFFFFFFFF.toInt())

		@Test
		fun `returns endpoints at and beyond the ramp bounds`() {
			sampleRamp(ramp, -1f) shouldBe 0xFF000000.toInt()
			sampleRamp(ramp, 0f) shouldBe 0xFF000000.toInt()
			sampleRamp(ramp, 1f) shouldBe 0xFFFFFFFF.toInt()
			sampleRamp(ramp, 2f) shouldBe 0xFFFFFFFF.toInt()
		}

		@Test
		fun `blends channels at the midpoint`() {
			// Black -> white at 0.5 is opaque mid-grey (0x7F on each RGB channel).
			sampleRamp(ramp, 0.5f) shouldBe 0xFF7F7F7F.toInt()
		}
	}
}
