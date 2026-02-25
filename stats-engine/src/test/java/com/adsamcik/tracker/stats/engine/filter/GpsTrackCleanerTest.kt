package com.adsamcik.tracker.stats.engine.filter

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GpsTrackCleanerTest {

	private fun point(
		timeMs: Long,
		lat: Double = 47.0,
		lon: Double = 11.0,
		alt: Float? = 1500f,
		speed: Float? = null,
		hAcc: Float? = 5f,
		vAcc: Float? = 8f
	) = GpsPoint(timeMs, lat, lon, alt, speed, hAcc, vAcc)

	@Nested
	inner class AccuracyGateTests {
		@Test
		fun `drops points with accuracy above threshold`() {
			val points = listOf(
				point(1000, hAcc = 5f),
				point(2000, hAcc = 100f),
				point(3000, hAcc = 50f),
				point(4000, hAcc = 200f),
			)
			val config = GpsCleaningConfig(accuracyGateM = 80f, minSegmentPoints = 1)
			val result = GpsTrackCleaner.clean(points, config)

			val allPoints = result.flatMap { it.points }
			allPoints shouldHaveSize 2
		}

		@Test
		fun `keeps points with null accuracy`() {
			val points = listOf(
				point(1000, hAcc = null),
				point(2000, hAcc = null),
				point(3000, hAcc = null),
				point(4000, hAcc = 5f),
			)
			val config = GpsCleaningConfig(minSegmentPoints = 1)
			val result = GpsTrackCleaner.clean(points, config)

			val allPoints = result.flatMap { it.points }
			allPoints shouldHaveSize 4
		}
	}

	@Nested
	inner class SpikeRemovalTests {
		@Test
		fun `removes single-point GPS jump`() {
			// Point at index 1 is ~50km away — impossible in 1 second
			val points = listOf(
				point(1000, lat = 47.0, lon = 11.0),
				point(2000, lat = 47.5, lon = 11.0), // ~55km jump
				point(3000, lat = 47.00001, lon = 11.00001), // ~1.5m from first — easily reachable
			)
			val result = GpsTrackCleaner.removeSpikesBySpeed(points, maxSpeedMps = 50f)

			result shouldHaveSize 2
			result[0].timeMs shouldBe 1000
			result[1].timeMs shouldBe 3000
		}

		@Test
		fun `preserves legitimate fast movement`() {
			// ~111m per degree lat. Moving 0.0003° in 1s ≈ 33m/s — below 50m/s limit
			val points = listOf(
				point(1000, lat = 47.0000, lon = 11.0),
				point(2000, lat = 47.0003, lon = 11.0),
				point(3000, lat = 47.0006, lon = 11.0),
			)
			val result = GpsTrackCleaner.removeSpikesBySpeed(points, maxSpeedMps = 50f)

			result shouldHaveSize 3
		}
	}

	@Nested
	inner class MedianFilterTests {
		@Test
		fun `smooths single-point jitter`() {
			// Middle point has a lat spike
			val points = listOf(
				point(1000, lat = 47.0000),
				point(2000, lat = 47.0001),
				point(3000, lat = 47.0050), // jitter spike
				point(4000, lat = 47.0002),
				point(5000, lat = 47.0003),
			)
			val smoothed = GpsTrackCleaner.medianFilterPosition(points, windowSize = 3)

			// The spike at index 2 should be reduced toward neighbors
			smoothed[2].latitudeDeg shouldBeLessThan 47.005
			smoothed[2].latitudeDeg shouldBeGreaterThan 47.0
		}

		@Test
		fun `preserves overall track shape`() {
			// Steadily increasing latitude
			val points = (0 until 10).map { i ->
				point(
					timeMs = (i * 1000).toLong(),
					lat = 47.0 + i * 0.0001,
					lon = 11.0
				)
			}
			val smoothed = GpsTrackCleaner.medianFilterPosition(points, windowSize = 3)

			// Endpoints may shift slightly due to asymmetric window at edges
			val tolerance = 0.0002
			kotlin.math.abs(smoothed.first().latitudeDeg - points.first().latitudeDeg) shouldBeLessThan tolerance
			kotlin.math.abs(smoothed.last().latitudeDeg - points.last().latitudeDeg) shouldBeLessThan tolerance
			// Nearly monotonically increasing (track shape preserved)
			for (i in 1 until smoothed.size) {
				smoothed[i].latitudeDeg shouldBeGreaterThan smoothed[i - 1].latitudeDeg - 0.0002
			}
		}
	}

	@Nested
	inner class SegmentationTests {
		@Test
		fun `splits track at time gaps`() {
			val points = listOf(
				point(1000),
				point(2000),
				point(3000),
				// 3 minute gap
				point(183_000),
				point(184_000),
				point(185_000),
			)
			val segments = GpsTrackCleaner.segmentByGaps(points, gapMs = 120_000L, minPoints = 1)

			segments shouldHaveSize 2
			segments[0].points shouldHaveSize 3
			segments[1].points shouldHaveSize 3
		}

		@Test
		fun `drops segments with fewer than minPoints`() {
			val points = listOf(
				point(1000),
				point(2000),
				point(3000),
				// gap
				point(183_000),
				point(184_000), // only 2 points in second segment
			)
			val segments = GpsTrackCleaner.segmentByGaps(points, gapMs = 120_000L, minPoints = 3)

			segments shouldHaveSize 1
			segments[0].points shouldHaveSize 3
		}

		@Test
		fun `keeps continuous segment intact`() {
			val points = (0 until 20).map { point((it * 1000).toLong()) }
			val segments = GpsTrackCleaner.segmentByGaps(points, gapMs = 120_000L, minPoints = 3)

			segments shouldHaveSize 1
			segments[0].points shouldHaveSize 20
		}
	}

	@Nested
	inner class IntegrationTests {
		@Test
		fun `full pipeline on synthetic ski track`() {
			val baseTime = 1_000_000L
			val goodPoints = (0 until 30).map { i ->
				// Descending altitude, slight lat/lon drift — ski run
				point(
					timeMs = baseTime + i * 1000L,
					lat = 47.0 + i * 0.00005,
					lon = 11.0 + i * 0.00003,
					alt = 2000f - i * 20f,
					hAcc = 5f
				)
			}

			// Insert a GPS spike at index 10
			val spikePoint = point(
				timeMs = baseTime + 10_500L, // between index 10 and 11
				lat = 48.0, // ~111km away — clearly a spike
				lon = 12.0,
				alt = 2000f,
				hAcc = 5f
			)

			// Insert a poor-accuracy point
			val badAccPoint = point(
				timeMs = baseTime + 15_500L,
				lat = 47.001,
				lon = 11.001,
				alt = 1700f,
				hAcc = 150f
			)

			// Second segment after a 3-minute gap
			val secondSegment = (0 until 15).map { i ->
				point(
					timeMs = baseTime + 210_000L + i * 1000L,
					lat = 47.002 + i * 0.00005,
					lon = 11.002 + i * 0.00003,
					alt = 1800f - i * 15f,
					hAcc = 8f
				)
			}

			val allPoints = goodPoints + spikePoint + badAccPoint + secondSegment

			val config = GpsCleaningConfig(
				accuracyGateM = 80f,
				maxSpeedMps = 50f,
				positionMedianWindow = 3,
				segmentGapMs = 120_000L,
				minSegmentPoints = 3
			)

			val segments = GpsTrackCleaner.clean(allPoints, config)

			// Should produce 2 segments (gap splits them)
			segments shouldHaveSize 2

			// Spike and bad-accuracy points should be removed
			val allCleaned = segments.flatMap { it.points }
			allCleaned.none { it.latitudeDeg > 47.5 } shouldBe true
			allCleaned.none { it.horizontalAccuracyM != null && it.horizontalAccuracyM!! > 80f } shouldBe true
		}
	}

	@Nested
	inner class InputValidationTests {
		@Test
		fun `filters out points with latitude out of range`() {
			val points = listOf(
				point(1000, lat = 999.0),
				point(2000, lat = -91.0),
				point(3000, lat = 47.0000),
				point(4000, lat = 47.0001),
				point(5000, lat = 47.0002),
			)
			val config = GpsCleaningConfig(minSegmentPoints = 1)
			val result = GpsTrackCleaner.clean(points, config)
			val allPoints = result.flatMap { it.points }

			allPoints.none { it.latitudeDeg > 90.0 || it.latitudeDeg < -90.0 } shouldBe true
			allPoints shouldHaveSize 3
		}

		@Test
		fun `filters out points with longitude out of range`() {
			val points = listOf(
				point(1000, lon = 181.0),
				point(2000, lon = -181.0),
				point(3000, lon = 11.0000),
				point(4000, lon = 11.0001),
				point(5000, lon = 11.0002),
			)
			val config = GpsCleaningConfig(minSegmentPoints = 1)
			val result = GpsTrackCleaner.clean(points, config)
			val allPoints = result.flatMap { it.points }

			allPoints shouldHaveSize 3
		}

		@Test
		fun `filters out NaN and Infinity coordinate values`() {
			val points = listOf(
				point(1000, lat = Double.NaN),
				point(2000, lon = Double.NaN),
				point(3000, lat = Double.POSITIVE_INFINITY),
				point(4000, lon = Double.NEGATIVE_INFINITY),
				point(5000, lat = 47.0000, lon = 11.0),
				point(6000, lat = 47.0001, lon = 11.0),
				point(7000, lat = 47.0002, lon = 11.0),
			)
			val config = GpsCleaningConfig(minSegmentPoints = 1)
			val result = GpsTrackCleaner.clean(points, config)
			val allPoints = result.flatMap { it.points }

			allPoints shouldHaveSize 3
			allPoints.all { it.latitudeDeg.isFinite() && it.longitudeDeg.isFinite() } shouldBe true
		}

		@Test
		fun `filters out points with zero time`() {
			val points = listOf(
				point(0, lat = 47.0, lon = 11.0),
				point(1000, lat = 47.0000, lon = 11.0),
				point(2000, lat = 47.0001, lon = 11.0),
				point(3000, lat = 47.0002, lon = 11.0),
			)
			val config = GpsCleaningConfig(minSegmentPoints = 1)
			val result = GpsTrackCleaner.clean(points, config)
			val allPoints = result.flatMap { it.points }

			allPoints shouldHaveSize 3
			allPoints.none { it.timeMs <= 0 } shouldBe true
		}

		@Test
		fun `returns empty when all points are invalid`() {
			val points = listOf(
				point(0, lat = Double.NaN),
				point(-1, lat = 999.0, lon = 999.0),
				point(0, lat = Double.POSITIVE_INFINITY),
			)
			val result = GpsTrackCleaner.clean(points)

			result shouldHaveSize 0
		}

		@Test
		fun `valid data passes through unchanged`() {
			val points = listOf(
				point(1000, lat = 47.0, lon = 11.0),
				point(2000, lat = 47.0001, lon = 11.0001),
				point(3000, lat = 47.0002, lon = 11.0002),
			)
			val config = GpsCleaningConfig(minSegmentPoints = 1)
			val result = GpsTrackCleaner.clean(points, config)
			val allPoints = result.flatMap { it.points }

			allPoints shouldHaveSize 3
		}
	}

	@Nested
	inner class HaversineTests {
		@Test
		fun `same point returns zero`() {
			val d = GpsTrackCleaner.haversineDistance(47.0, 11.0, 47.0, 11.0)
			d shouldBeLessThan 0.001
		}

		@Test
		fun `one degree latitude is approximately 111km`() {
			val d = GpsTrackCleaner.haversineDistance(47.0, 11.0, 48.0, 11.0)
			d shouldBeGreaterThan 110_000.0
			d shouldBeLessThan 112_000.0
		}
	}
}
