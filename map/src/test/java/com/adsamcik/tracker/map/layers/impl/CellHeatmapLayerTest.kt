package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.perf.PerformanceManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CellHeatmapLayerTest {

	private class TestableCellHeatmapLayer(
		repo: GeoRepository,
		perf: PerformanceManager = PerformanceManager(),
	) : CellHeatmapLayer(repo, perf) {
		fun testColorStops() = colorStops()
		suspend fun testLoadData(context: Context, bounds: Bounds? = null) = loadData(context, bounds)
	}

	private val mockRepo: GeoRepository = mockk()
	private val layer = TestableCellHeatmapLayer(mockRepo)

	@Test
	fun `cell color stops use signal-strength ramp`() {
		layer.testColorStops() shouldBe HeatmapColorRamps.CellSignal
	}

	@Test
	fun `loadData normalizes ASU signal strength into heatmap weight range`() = runTest {
		every { mockRepo.queryWeighted(any(), eq("asu")) } returns flowOf(
			listOf(
				WeightedGeoFeature(50.0, 14.0, 1_000L, -10.0),
				WeightedGeoFeature(51.0, 15.0, 2_000L, 48.5),
				WeightedGeoFeature(52.0, 16.0, 3_000L, 120.0),
			)
		)

		val result = layer.testLoadData(mockk())

		result shouldHaveSize 3
		result[0].weight shouldBe 0.0
		result[1].weight shouldBe 0.5
		result[2].weight shouldBe 1.0
	}
}
