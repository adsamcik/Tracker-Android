package com.adsamcik.tracker.map.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.ui.LayerController
import com.adsamcik.tracker.map.ui.LayerEntry
import com.adsamcik.tracker.map.shared.MapLayerData
import com.adsamcik.tracker.map.shared.MapLayerInfo
import com.adsamcik.tracker.map.shared.MapLegend
import com.adsamcik.tracker.map.shared.layers.LayerCapabilities
import com.adsamcik.tracker.map.shared.layers.LayerDescriptor
import com.adsamcik.tracker.map.shared.layers.LayerFactory
import com.adsamcik.tracker.map.shared.layers.LayerRecipe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LayerSwitchIntegrationTest {

    private class TestLayer : BaseMapLayer<Unit, Unit>() {
        @Volatile var configProduced = false
        @Volatile var disabled = false

        override suspend fun loadData(context: Context): Unit = Unit
        override fun processData(input: Unit, budgets: PerformanceManager.PerformanceBudgets): Unit = Unit
        override fun produceConfig(processed: Unit): MapLibreLayerConfig? {
            configProduced = true
            return MapLibreLayerConfig.Line(
                geoJson = """{"type":"FeatureCollection","features":[]}""",
                colorArgb = 0xFF0000FF.toInt(),
                widthDp = 4f,
                opacity = 1f
            )
        }
        override fun onDisable() {
            disabled = true
        }
    }

    private fun descriptorFor(id: String, layerBuilder: (Context) -> BaseMapLayer<*, *>, legend: MapLayerData): LayerDescriptor {
        return LayerDescriptor(
            id = id,
            titleRes = android.R.string.untitled,
            iconRes = null,
            capabilities = LayerCapabilities(supportsDateRange = true, supportsQuality = true),
            recipe = LayerRecipe(
                factory = LayerFactory {
                    LayerEntry(
                        build = layerBuilder,
                        legend = legend
                    )
                },
                defaultParams = emptyMap()
            )
        )
    }

    @Test
    fun layer_switch_updates_overlays_and_legend() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = LayerController()

        val layerA = TestLayer()
        val legendA = MapLayerData(MapLayerInfo("TestLayerA", 0), emptyList(), MapLegend())
        val descA = descriptorFor("A", { _ -> layerA }, legendA)

        // setLayer is now suspend and awaits pipeline completion
        runBlocking { controller.setLayer(context, descA, quality = 1.0f, dateRange = 0L..1L) }
        assertEquals(legendA, controller.activeLegend())

        val layerB = TestLayer()
        val legendB = MapLayerData(MapLayerInfo("TestLayerB", 0), emptyList(), MapLegend())
        val descB = descriptorFor("B", { _ -> layerB }, legendB)

        // Switch to layer B — layer A disabled, B pipeline awaited
        runBlocking { controller.setLayer(context, descB, quality = 1.0f, dateRange = 0L..1L) }
        assertEquals(legendB, controller.activeLegend())

        // Clear everything
        controller.clear()
        assertNull(controller.activeLegend())
    }
}
