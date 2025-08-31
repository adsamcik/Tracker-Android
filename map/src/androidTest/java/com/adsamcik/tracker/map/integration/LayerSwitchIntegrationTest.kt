package com.adsamcik.tracker.map.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.ui.LayerController
import com.adsamcik.tracker.map.ui.LayerEntry
import com.adsamcik.tracker.shared.map.MapLayerData
import com.adsamcik.tracker.shared.map.MapLayerInfo
import com.adsamcik.tracker.shared.map.MapLegend
import com.adsamcik.tracker.shared.map.layers.LayerCapabilities
import com.adsamcik.tracker.shared.map.layers.LayerDescriptor
import com.adsamcik.tracker.shared.map.layers.LayerFactory
import com.adsamcik.tracker.shared.map.layers.LayerRecipe
import com.google.android.gms.maps.GoogleMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LayerSwitchIntegrationTest {

    private class TestLayer(
        private val onRender: () -> Unit,
        private val onDisableHook: () -> Unit
    ) : BaseMapLayer<Unit, Unit>() {
        override fun loadData(context: Context): Unit = Unit
        override fun processData(input: Unit, budgets: PerformanceManager.PerformanceBudgets): Unit = Unit
        override fun render(map: GoogleMap, processed: Unit) {
            onRender()
        }
        override fun onDisable(map: GoogleMap) {
            onDisableHook()
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
        val map: GoogleMap = mock()
        val controller = LayerController()

        val renderLatchA = CountDownLatch(1)
        val disableLatchA = CountDownLatch(1)
        val layerA = TestLayer(onRender = { renderLatchA.countDown() }, onDisableHook = { disableLatchA.countDown() })
        val legendA = MapLayerData(MapLayerInfo("TestLayerA", 0), emptyList(), MapLegend())
        val descA = descriptorFor("A", { _ -> layerA }, legendA)

        controller.setLayer(context, map, descA, quality = 1.0f, dateRange = 0L..1L)
        // Wait for render to run on main
        renderLatchA.await(3, TimeUnit.SECONDS)
        assertEquals(legendA, controller.activeLegend())

        val renderLatchB = CountDownLatch(1)
        val disableLatchB = CountDownLatch(1)
        val layerB = TestLayer(onRender = { renderLatchB.countDown() }, onDisableHook = { disableLatchB.countDown() })
        val legendB = MapLayerData(MapLayerInfo("TestLayerB", 0), emptyList(), MapLegend())
        val descB = descriptorFor("B", { _ -> layerB }, legendB)

        // Switch to layer B
        controller.setLayer(context, map, descB, quality = 1.0f, dateRange = 0L..1L)
        // Previous layer should be disabled, new one should render
        disableLatchA.await(3, TimeUnit.SECONDS)
        renderLatchB.await(3, TimeUnit.SECONDS)
        assertEquals(legendB, controller.activeLegend())

        // Clear everything
        controller.clear(map)
        disableLatchB.await(3, TimeUnit.SECONDS)
        assertNull(controller.activeLegend())
    }
}
