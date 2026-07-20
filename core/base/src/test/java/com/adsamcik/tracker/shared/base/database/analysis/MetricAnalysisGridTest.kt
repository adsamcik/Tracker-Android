package com.adsamcik.tracker.shared.base.database.analysis

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.cos
import org.junit.jupiter.api.Test

class MetricAnalysisGridTest {
	@Test
	fun `production pyramid contains only the locked resolution levels`() {
		MetricAnalysisGrid.RESOLUTIONS_M shouldBe listOf(25, 50, 100, 250, 500, 1_000)
	}

	@Test
	fun `rho gate selects the finest supported level`() {
		MetricAnalysisGrid.finestSupportedResolution(12.5) shouldBe 25
		MetricAnalysisGrid.finestSupportedResolution(12.5001) shouldBe 50
		MetricAnalysisGrid.finestSupportedResolution(500.0) shouldBe 1_000
		MetricAnalysisGrid.finestSupportedResolution(500.1) shouldBe null
	}

	@Test
	fun `row local projection keeps cell width metric at high latitude`() {
		val cell = MetricAnalysisGrid.cell(latDeg = 60.0, lonDeg = 14.0, resolutionM = 25)
		val centerLat = (cell.minLatE7 + cell.maxLatE7) / 2.0 / 1e7
		val widthM = Math.toRadians((cell.maxLonE7 - cell.minLonE7) / 1e7) *
			EARTH_RADIUS_M * cos(Math.toRadians(centerLat))
		val heightM = Math.toRadians((cell.maxLatE7 - cell.minLatE7) / 1e7) * EARTH_RADIUS_M

		abs(widthM - 25.0) shouldBeLessThan 0.1
		abs(heightM - 25.0) shouldBeLessThan 0.1
	}

	@Test
	fun `probabilistic attribution conserves interval mass`() {
		val weighted = MetricAnalysisGrid.weightedCells(
			latDeg = 50.0755,
			lonDeg = 14.4378,
			effectiveR90M = 50.0,
			resolutionM = 100,
		)

		weighted.size shouldBe 9
		weighted.forEach { it.probability shouldBeGreaterThan 0.0 }
		abs(weighted.sumOf { it.probability } - 1.0) shouldBeLessThan 1e-12
	}

	@Test
	fun `integer allocation conserves every millisecond deterministically`() {
		val weighted = MetricAnalysisGrid.weightedCells(
			latDeg = 50.0755,
			lonDeg = 14.4378,
			effectiveR90M = 50.0,
			resolutionM = 100,
		)

		val first = MetricAnalysisGrid.allocateMillis(1_001L, weighted)
		val second = MetricAnalysisGrid.allocateMillis(1_001L, weighted)

		first shouldBe second
		first.sumOf { it.millis } shouldBe 1_001L
		first.all { it.millis >= 0L } shouldBe true
	}

	@Test
	fun `positive antimeridian belongs to a non-empty cell`() {
		val cell = MetricAnalysisGrid.cell(latDeg = 0.0, lonDeg = 180.0, resolutionM = 25)
		(cell.maxLonE7 - cell.minLonE7).toDouble() shouldBeGreaterThan 0.0
	}

	@Test
	fun `posterior wraps across antimeridian without zero-width cells`() {
		val weighted = MetricAnalysisGrid.weightedCells(
			latDeg = 0.0,
			lonDeg = 179.99999,
			effectiveR90M = 50.0,
			resolutionM = 100,
		)

		weighted.any { it.cell.minLonE7 == -1_800_000_000 } shouldBe true
		weighted.all { it.cell.maxLonE7 > it.cell.minLonE7 } shouldBe true
		abs(weighted.sumOf { it.probability } - 1.0) shouldBeLessThan 1e-12
	}

	@Test
	fun `north pole belongs to a non-empty final row`() {
		val cell = MetricAnalysisGrid.cell(latDeg = 90.0, lonDeg = 0.0, resolutionM = 25)
		(cell.maxLatE7 - cell.minLatE7).toDouble() shouldBeGreaterThan 0.0
	}

	private companion object {
		const val EARTH_RADIUS_M = 6_371_008.8
	}
}
