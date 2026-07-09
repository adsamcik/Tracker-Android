package com.adsamcik.tracker.map.presentation.bridge

import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.shared.MapLayerData
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.adsamcik.tracker.map.presentation.udf.MapOverlayState

@DisplayName("LayerEngine interface")
class LayerEngineTest {

	private class StubLayerEngine : LayerEngine {
		var selectedIds: Set<String> = emptySet()
		var lastDateRange: LongRange = LongRange.EMPTY
		var refreshCount = 0
		var cleared = false

		override suspend fun selectLayers(
			ids: Set<String>,
			quality: Float,
			dateRange: LongRange,
			bounds: Bounds?,
			zoom: Float
		) {
			selectedIds = ids
			lastDateRange = dateRange
		}

		override suspend fun refreshLayersInPlace(bounds: Bounds?, zoom: Float, dateRange: LongRange, forceReload: Boolean) {
			refreshCount += 1
			lastDateRange = dateRange
		}

		override fun clear() {
			cleared = true
		}

		override fun activeLegend(): MapLayerData? = null
		override fun activeLayerConfig(): MapLibreLayerConfig? = null
		override fun overlays(): ImmutableList<MapOverlayState> = persistentListOf()
	}

	@Nested
	@DisplayName("selectSingleLayer default implementation")
	inner class SelectSingleLayerTests {

		@Test
		fun `selectSingleLayer with id calls selectLayers with singleton set`() = runTest {
			val engine = StubLayerEngine()
			engine.selectSingleLayer("heatmap", 1.0f, 0L..100L)
			engine.selectedIds shouldBe setOf("heatmap")
		}

		@Test
		fun `selectSingleLayer with null id calls selectLayers with empty set`() = runTest {
			val engine = StubLayerEngine()
			engine.selectSingleLayer(null, 1.0f, 0L..100L)
			engine.selectedIds shouldBe emptySet<String>()
		}
	}

	@Nested
	@DisplayName("destroy default implementation")
	inner class DestroyTests {

		@Test
		fun `destroy has empty default implementation`() {
			val engine = StubLayerEngine()
			// Should not throw
			engine.destroy()
		}
	}

	@Nested
	@DisplayName("clear")
	inner class ClearTests {

		@Test
		fun `clear sets cleared flag`() {
			val engine = StubLayerEngine()
			engine.clear()
			engine.cleared shouldBe true
		}
	}

	@Nested
	@DisplayName("nullable returns")
	inner class NullableReturnTests {

		@Test
		fun `activeLegend returns null from stub`() {
			val engine = StubLayerEngine()
			engine.activeLegend().shouldBeNull()
		}

		@Test
		fun `activeLayerConfig returns null from stub`() {
			val engine = StubLayerEngine()
			engine.activeLayerConfig().shouldBeNull()
		}
	}
}
