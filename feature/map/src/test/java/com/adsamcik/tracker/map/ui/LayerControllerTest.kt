package com.adsamcik.tracker.map.ui

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.LayerRefreshResult
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Stub layer for testing controller lifecycle. */
private class StubMapLayer(
    private val configFactory: (loadCount: Int, bounds: Bounds?) -> MapLibreLayerConfig? = { _, _ -> null },
) : BaseMapLayer<Unit, Unit>(PerformanceManager()) {
    @Volatile
    var disableCalled = false

    @Volatile
    var loadCount = 0

    @Volatile
    var lastBounds: Bounds? = null

    override suspend fun loadData(context: Context, bounds: Bounds?) {
        loadCount += 1
        lastBounds = bounds
    }

    override fun processData(input: Unit, budgets: PerformanceManager.PerformanceBudgets) = Unit
    override fun produceConfig(processed: Unit): MapLibreLayerConfig? = configFactory(loadCount, lastBounds)
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

private class GatedReloadLayer : BaseMapLayer<String, String>(PerformanceManager()) {
    val reloadStarted = CompletableDeferred<Unit>()
    val reloadGate = CompletableDeferred<Unit>()

    @Volatile
    var gateReload = false

    override suspend fun loadData(context: Context, bounds: Bounds?): String {
        if (gateReload) {
            reloadStarted.complete(Unit)
            reloadGate.await()
        }
        return bounds?.north?.toString() ?: "all"
    }

    override fun processData(
        input: String,
        budgets: PerformanceManager.PerformanceBudgets,
    ): String = input

    override fun produceConfig(processed: String): MapLibreLayerConfig =
        MapLibreLayerConfig.Line(
            geoJson = processed,
            colorArgb = 0xFF0000FF.toInt(),
        )
}

private fun stubLegend(name: String = "TestLayer"): MapLayerData = MapLayerData(
    info = MapLayerInfo(name, 0),
    colorList = emptyList(),
    legend = MapLegend()
)

private fun stubDescriptor(
    id: String = "test_layer",
    layer: BaseMapLayer<*, *> = StubMapLayer(),
    legend: MapLayerData = stubLegend(),
    onFactoryCreate: () -> Unit = {},
): LayerDescriptor = LayerDescriptor(
    id = id,
    titleRes = 0,
    iconRes = null,
    capabilities = LayerCapabilities(),
    recipe = LayerRecipe(
        factory = LayerFactory {
            onFactoryCreate()
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
    @DisplayName("in-place refresh")
    inner class InPlaceRefresh {

        @Test
        fun `refreshLayersInPlace reloads existing layer without disabling or recreating`() = runTest {
            var factoryCreateCount = 0
            val layer = StubMapLayer { loadCount, _ ->
                MapLibreLayerConfig.Line(
                    geoJson = "load-$loadCount",
                    colorArgb = 0xFF0000FF.toInt(),
                )
            }
            val descriptor = stubDescriptor(
                id = "location_heatmap",
                layer = layer,
                onFactoryCreate = { factoryCreateCount += 1 },
            )
            val initialBounds = Bounds(north = 2.0, east = 2.0, south = 1.0, west = 1.0)
            val refreshedBounds = Bounds(north = 3.0, east = 3.0, south = 2.0, west = 2.0)

            controller.setLayer(context, descriptor, 1.0f, 0L..Long.MAX_VALUE, initialBounds, zoom = 10f)
            controller.refreshLayersInPlace(context, refreshedBounds, zoom = 11f, dateRange = 0L..Long.MAX_VALUE)

            factoryCreateCount shouldBe 1
            layer.disableCalled shouldBe false
            layer.loadCount shouldBe 2
            layer.lastBounds shouldBe refreshedBounds
            (controller.activeLayerConfig() as MapLibreLayerConfig.Line).geoJson shouldBe "load-2"
        }

        @Test
        fun `refreshLayersInPlace skips reload when viewport bucket is cached`() = runTest {
            val layer = StubMapLayer { loadCount, _ ->
                MapLibreLayerConfig.Line(
                    geoJson = "load-$loadCount",
                    colorArgb = 0xFF0000FF.toInt(),
                )
            }
            val descriptor = stubDescriptor(id = "location_heatmap", layer = layer)
            val initialBounds = Bounds(north = 2.0, east = 2.0, south = 1.0, west = 1.0)
            val refreshedBounds = Bounds(north = 3.0, east = 3.0, south = 2.0, west = 2.0)

            controller.setLayer(context, descriptor, 1.0f, 0L..Long.MAX_VALUE, initialBounds, zoom = 10f)
            controller.refreshLayersInPlace(context, refreshedBounds, zoom = 11f, dateRange = 0L..Long.MAX_VALUE)
            controller.refreshLayersInPlace(context, refreshedBounds, zoom = 11f, dateRange = 0L..Long.MAX_VALUE)

            layer.loadCount shouldBe 2
            (controller.activeLayerConfig() as MapLibreLayerConfig.Line).geoJson shouldBe "load-2"
        }

        @Test
        fun `refreshLayersInPlace with forceReload bypasses the viewport cache`() = runTest {
            // Same viewport twice: without forceReload the second refresh would hit the cache; with
            // forceReload it must re-query (live data changed even though the viewport did not).
            val layer = StubMapLayer { loadCount, _ ->
                MapLibreLayerConfig.Line(
                    geoJson = "load-$loadCount",
                    colorArgb = 0xFF0000FF.toInt(),
                )
            }
            val descriptor = stubDescriptor(id = "location_heatmap", layer = layer)
            val bounds = Bounds(north = 2.0, east = 2.0, south = 1.0, west = 1.0)

            controller.setLayer(context, descriptor, 1.0f, 0L..Long.MAX_VALUE, bounds, zoom = 10f)
            // Cached refresh at a new viewport, then a forced refresh at the SAME viewport.
            controller.refreshLayersInPlace(context, bounds, zoom = 11f, dateRange = 0L..Long.MAX_VALUE)
            controller.refreshLayersInPlace(context, bounds, zoom = 11f, dateRange = 0L..Long.MAX_VALUE, forceReload = true)

            // setLayer=1, first refresh (new zoom key)=2, forced refresh re-queries despite the cache=3.
            layer.loadCount shouldBe 3
            (controller.activeLayerConfig() as MapLibreLayerConfig.Line).geoJson shouldBe "load-3"
        }

        @Test
        fun `failed refresh does not poison cache and successful retry publishes requested config`() = runTest {
            val failure = IllegalStateException("injected reload failure")
            val layer = StubMapLayer { loadCount, bounds ->
                if (loadCount == 2) throw failure
                MapLibreLayerConfig.Line(
                    geoJson = "load-$loadCount-${bounds?.north}",
                    colorArgb = 0xFF0000FF.toInt(),
                )
            }
            val descriptor = stubDescriptor(id = "location_heatmap", layer = layer)
            val boundsA = Bounds(north = 2.0, east = 2.0, south = 1.0, west = 1.0)
            val boundsB = Bounds(north = 4.0, east = 4.0, south = 3.0, west = 3.0)

            controller.setLayer(context, descriptor, 1.0f, 0L..Long.MAX_VALUE, boundsA, zoom = 10f)
            val failedResult = controller.refreshLayersInPlace(
                context,
                boundsB,
                zoom = 11f,
                dateRange = 0L..Long.MAX_VALUE,
            )

            (failedResult is LayerRefreshResult.Failure) shouldBe true
            (failedResult as LayerRefreshResult.Failure).cause shouldBe failure
            (controller.activeLayerConfig() as MapLibreLayerConfig.Line).geoJson shouldBe "load-1-2.0"

            controller.refreshLayersInPlace(
                context,
                boundsB,
                zoom = 11f,
                dateRange = 0L..Long.MAX_VALUE,
            ) shouldBe LayerRefreshResult.Success
            (controller.activeLayerConfig() as MapLibreLayerConfig.Line).geoJson shouldBe "load-3-4.0"

            controller.refreshLayersInPlace(
                context,
                boundsB,
                zoom = 11f,
                dateRange = 0L..Long.MAX_VALUE,
            ) shouldBe LayerRefreshResult.Success
            layer.loadCount shouldBe 3
        }

        @Test
        fun `late refresh cannot overwrite a newer layer selection`() = runTest {
            val layerA = GatedReloadLayer()
            val layerB = StubMapLayer { _, _ ->
                MapLibreLayerConfig.Line(
                    geoJson = "layer-b",
                    colorArgb = 0xFF00FF00.toInt(),
                )
            }
            val descriptorA = stubDescriptor(id = "layer-a", layer = layerA)
            val descriptorB = stubDescriptor(id = "layer-b", layer = layerB)
            val initialBounds = Bounds(north = 1.0, east = 1.0, south = 0.0, west = 0.0)
            val refreshBounds = Bounds(north = 2.0, east = 2.0, south = 1.0, west = 1.0)

            controller.setLayer(context, descriptorA, 1.0f, 0L..Long.MAX_VALUE, initialBounds)
            layerA.gateReload = true
            val staleRefresh = async {
                controller.refreshLayersInPlace(
                    context,
                    refreshBounds,
                    zoom = 11f,
                    dateRange = 0L..Long.MAX_VALUE,
                )
            }
            layerA.reloadStarted.await()

            controller.setLayer(context, descriptorB, 1.0f, 0L..Long.MAX_VALUE, initialBounds)
            layerA.reloadGate.complete(Unit)

            staleRefresh.await() shouldBe LayerRefreshResult.Superseded
            controller.activeLegend() shouldBe stubLegend()
            (controller.activeLayerConfig() as MapLibreLayerConfig.Line).geoJson shouldBe "layer-b"
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
