package com.adsamcik.tracker.map.ui

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.shared.MapLayerData
import com.adsamcik.tracker.map.shared.MapLayerInfo
import com.adsamcik.tracker.map.shared.MapLegend
import com.adsamcik.tracker.map.shared.layers.LayerCapabilities
import com.adsamcik.tracker.map.shared.layers.LayerDescriptor
import com.adsamcik.tracker.map.shared.layers.LayerFactory
import com.adsamcik.tracker.map.shared.layers.LayerRecipe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Stub layer for testing controller lifecycle. */
private class StubMapLayer : BaseMapLayer<Unit, Unit>(PerformanceManager()) {
    @Volatile
    var disableCalled = false

    override suspend fun loadData(context: Context, bounds: Bounds?) = Unit
    override fun processData(input: Unit, budgets: PerformanceManager.PerformanceBudgets) = Unit
    override fun produceConfig(processed: Unit): MapLibreLayerConfig? = null
    override fun onDisable() {
        disableCalled = true
    }
}

/** Stub layer that supports date range filtering. */
private class StubDateRangeLayer : BaseMapLayer<Unit, Unit>(PerformanceManager()), SupportsDateRange {
    override var dateRange: LongRange = 0L..Long.MAX_VALUE

    override suspend fun loadData(context: Context, bounds: Bounds?) = Unit
    override fun processData(input: Unit, budgets: PerformanceManager.PerformanceBudgets) = Unit
    override fun produceConfig(processed: Unit): MapLibreLayerConfig? = null
}

private fun stubLegend(name: String = "TestLayer"): MapLayerData = MapLayerData(
    info = MapLayerInfo(name, 0),
    colorList = emptyList(),
    legend = MapLegend()
)

private fun stubDescriptor(
    id: String = "test_layer",
    layer: BaseMapLayer<*, *> = StubMapLayer(),
    legend: MapLayerData = stubLegend()
): LayerDescriptor = LayerDescriptor(
    id = id,
    titleRes = 0,
    iconRes = null,
    capabilities = LayerCapabilities(),
    recipe = LayerRecipe(
        factory = LayerFactory {
            LayerEntry(
                build = { layer },
                legend = legend
            )
        }
    )
)

@DisplayName("LayerController")
class LayerControllerTest {

    private lateinit var controller: LayerController
    private lateinit var context: Context

    @BeforeEach
    fun setup() {
        controller = LayerController()
        context = mockk(relaxed = true)
    }

    @Nested
    @DisplayName("initial state")
    inner class InitialState {

        @Test
        fun `activeLegend is null before any layer is set`() {
            controller.activeLegend().shouldBeNull()
        }

        @Test
        fun `activeLayerConfig is null before any layer is set`() {
            controller.activeLayerConfig().shouldBeNull()
        }
    }

    @Nested
    @DisplayName("setLayer")
    inner class SetLayer {

        @Test
        fun `setting null descriptor clears state`() = runTest {
            controller.setLayer(context, null, 1.0f, 0L..Long.MAX_VALUE)

            controller.activeLegend().shouldBeNull()
            controller.activeLayerConfig().shouldBeNull()
        }

        @Test
        fun `setting a valid descriptor updates legend`() = runTest {
            val legend = stubLegend("LocationHeatmap")
            val descriptor = stubDescriptor(legend = legend)

            controller.setLayer(context, descriptor, 1.0f, 0L..Long.MAX_VALUE)

            controller.activeLegend().shouldNotBeNull()
            controller.activeLegend() shouldBe legend
        }

        @Test
        fun `switching layer clears previous layer`() = runTest {
            val firstLayer = StubMapLayer()
            val firstDescriptor = stubDescriptor(id = "first", layer = firstLayer)
            val secondDescriptor = stubDescriptor(id = "second")

            controller.setLayer(context, firstDescriptor, 1.0f, 0L..Long.MAX_VALUE)
            controller.setLayer(context, secondDescriptor, 1.0f, 0L..Long.MAX_VALUE)

            firstLayer.disableCalled shouldBe true
        }

        @Test
        fun `setting null after a layer clears everything`() = runTest {
            val descriptor = stubDescriptor()
            controller.setLayer(context, descriptor, 1.0f, 0L..Long.MAX_VALUE)

            controller.setLayer(context, null, 1.0f, 0L..Long.MAX_VALUE)

            controller.activeLegend().shouldBeNull()
            controller.activeLayerConfig().shouldBeNull()
        }
    }

    @Nested
    @DisplayName("clear")
    inner class Clear {

        @Test
        fun `clear resets legend to null`() = runTest {
            val descriptor = stubDescriptor()
            controller.setLayer(context, descriptor, 1.0f, 0L..Long.MAX_VALUE)

            controller.clear()

            controller.activeLegend().shouldBeNull()
        }

        @Test
        fun `clear disables the active layer`() = runTest {
            val layer = StubMapLayer()
            val descriptor = stubDescriptor(layer = layer)
            controller.setLayer(context, descriptor, 1.0f, 0L..Long.MAX_VALUE)

            controller.clear()

            layer.disableCalled shouldBe true
        }

        @Test
        fun `clear on empty controller does not throw`() {
            controller.clear()

            controller.activeLegend().shouldBeNull()
        }
    }

    @Nested
    @DisplayName("destroy")
    inner class Destroy {

        @Test
        fun `destroy clears active layer`() = runTest {
            val layer = StubMapLayer()
            val descriptor = stubDescriptor(layer = layer)
            controller.setLayer(context, descriptor, 1.0f, 0L..Long.MAX_VALUE)

            controller.destroy()

            layer.disableCalled shouldBe true
            controller.activeLegend().shouldBeNull()
            controller.activeLayerConfig().shouldBeNull()
        }

        @Test
        fun `destroy on empty controller does not throw`() {
            controller.destroy()

            controller.activeLegend().shouldBeNull()
        }

        @Test
        fun `destroy can be called multiple times safely`() {
            controller.destroy()
            controller.destroy()

            controller.activeLegend().shouldBeNull()
        }
    }

    @Nested
    @DisplayName("error handling")
    inner class ErrorHandling {

        @Test
        fun `factory returning wrong type does not crash`() = runTest {
            val descriptor = LayerDescriptor(
                id = "bad_factory",
                titleRes = 0,
                iconRes = null,
                capabilities = LayerCapabilities(),
                recipe = LayerRecipe(
                    factory = LayerFactory { "not a LayerEntry" }
                )
            )

            controller.setLayer(context, descriptor, 1.0f, 0L..Long.MAX_VALUE)

            // Controller should remain in clean state
            controller.activeLegend().shouldBeNull()
            controller.activeLayerConfig().shouldBeNull()
        }
    }

    @Nested
    @DisplayName("date range injection")
    inner class DateRangeInjection {

        @Test
        fun `injects dateRange into SupportsDateRange layer`() = runTest {
            val dateRangeLayer = StubDateRangeLayer()
            val descriptor = stubDescriptor(layer = dateRangeLayer)
            val range = 1000L..5000L

            controller.setLayer(context, descriptor, 1.0f, range)

            dateRangeLayer.dateRange shouldBe range
        }

        @Test
        fun `does not crash for layer without SupportsDateRange`() = runTest {
            val plainLayer = StubMapLayer()
            val descriptor = stubDescriptor(layer = plainLayer)

            controller.setLayer(context, descriptor, 1.0f, 1000L..5000L)

            // Should complete without error
            controller.activeLegend().shouldNotBeNull()
        }

        @Test
        fun `all-time range is injected correctly`() = runTest {
            val dateRangeLayer = StubDateRangeLayer()
            val descriptor = stubDescriptor(layer = dateRangeLayer)
            val allTime = 0L..Long.MAX_VALUE

            controller.setLayer(context, descriptor, 1.0f, allTime)

            dateRangeLayer.dateRange shouldBe allTime
        }
    }
}
