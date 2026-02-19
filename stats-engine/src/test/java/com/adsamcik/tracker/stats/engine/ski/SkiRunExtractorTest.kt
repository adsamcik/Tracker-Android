package com.adsamcik.tracker.stats.engine.ski

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SkiRunExtractorTest {

	private fun locationsForSegment(
		startMs: Long,
		endMs: Long,
		startLat: Double = 47.0,
		startLon: Double = 11.0,
		startAlt: Float = 2000f,
		endAlt: Float = 1500f,
		count: Int = 20,
		speed: Float = 8f,
	): List<SkiLocationPoint> {
		val intervalMs = (endMs - startMs) / count
		return (0 until count).map { i ->
			val fraction = i.toDouble() / (count - 1).coerceAtLeast(1)
			SkiLocationPoint(
				timeMs = startMs + i * intervalMs,
				latitudeDeg = startLat + fraction * 0.005,
				longitudeDeg = startLon + fraction * 0.005,
				altitudeM = startAlt + (endAlt - startAlt) * fraction.toFloat(),
				speedMps = speed,
			)
		}
	}

	@Nested
	inner class Extraction {

		@Test
		fun `extractFromSkiDay - two descents and two lifts produce correct summary`() {
			val segments = listOf(
				SkiStateSegment(SkiState.LIFT_UP, 0L, 480_000L),
				SkiStateSegment(SkiState.DOWNHILL_RUN, 480_000L, 660_000L),
				SkiStateSegment(SkiState.LIFT_UP, 660_000L, 1_140_000L),
				SkiStateSegment(SkiState.DOWNHILL_RUN, 1_140_000L, 1_320_000L),
			)

			val locations = mutableListOf<SkiLocationPoint>()
			// Lift 1: ascending 1800→2300m
			locations += locationsForSegment(0L, 480_000L, startAlt = 1800f, endAlt = 2300f, speed = 3f)
			// Descent 1: descending 2300→1800m
			locations += locationsForSegment(480_000L, 660_000L, startAlt = 2300f, endAlt = 1800f, speed = 10f)
			// Lift 2
			locations += locationsForSegment(660_000L, 1_140_000L, startAlt = 1800f, endAlt = 2300f, speed = 3f)
			// Descent 2
			locations += locationsForSegment(1_140_000L, 1_320_000L, startAlt = 2300f, endAlt = 1800f, speed = 10f)

			val summary = SkiRunExtractor.extract(segments, locations)

			summary.totalRuns shouldBe 2
			// Each descent drops 500m → total vertical ~-1000m (negative because descending)
			assert(summary.totalVerticalM < 0f) { "Total vertical should be negative for descents" }
			assert(summary.totalDistanceM > 0f) { "Total distance should be positive" }
			summary.runs shouldHaveSize 4
		}

		@Test
		fun `emptySegments - produces summary with all zeros`() {
			val summary = SkiRunExtractor.extract(emptyList(), emptyList())

			summary.totalRuns shouldBe 0
			summary.totalVerticalM shouldBe 0f
			summary.totalDistanceM shouldBe 0f
			summary.totalLiftTimeMs shouldBe 0L
			summary.totalRunTimeMs shouldBe 0L
			summary.runs shouldHaveSize 0
		}

		@Test
		fun `noLocationsForSegment - metrics are zero when no GPS points in range`() {
			val segments = listOf(
				SkiStateSegment(SkiState.DOWNHILL_RUN, 0L, 180_000L),
			)
			// Locations are outside the segment time range
			val locations = listOf(
				SkiLocationPoint(500_000L, 47.0, 11.0, 2000f, 8f),
				SkiLocationPoint(600_000L, 47.01, 11.01, 1500f, 8f),
			)

			val summary = SkiRunExtractor.extract(segments, locations)

			summary.totalRuns shouldBe 1
			summary.totalVerticalM shouldBe 0f
			summary.totalDistanceM shouldBe 0f
			summary.runs[0].maxSpeedMps shouldBe 0f
		}
	}

	@Nested
	inner class HaversineDistance {

		@Test
		fun `haversineDistanceComputation - 1 degree lat at equator is approximately 111km`() {
			val distance = SkiRunExtractor.haversineDistance(
				lat1 = 0.0, lon1 = 0.0,
				lat2 = 1.0, lon2 = 0.0,
			)
			// Expected ~111,195 meters for 1 degree of latitude
			distance.toFloat() shouldBe (111_195f plusOrMinus 200f)
		}

		@Test
		fun `haversineDistanceComputation - same point returns zero`() {
			val distance = SkiRunExtractor.haversineDistance(
				lat1 = 47.0, lon1 = 11.0,
				lat2 = 47.0, lon2 = 11.0,
			)
			distance shouldBe 0.0
		}
	}
}
