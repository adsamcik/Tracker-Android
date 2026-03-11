package com.adsamcik.tracker.map.layers.base

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private class TestLayer : BaseMapLayer<List<Int>, List<Int>>(PerformanceManager()) {
    @Volatile var before = false
    @Volatile var loaded = false
    @Volatile var processed = false
    @Volatile var configProduced = false
    @Volatile var disabled = false

    override fun beforeEnable(context: Context) {
        before = true
    }

    override suspend fun loadData(context: Context, bounds: Bounds?): List<Int> {
        loaded = true
        return listOf(1, 2, 3)
    }

    override fun processData(input: List<Int>, budgets: PerformanceManager.PerformanceBudgets): List<Int> {
        processed = true
        return input.map { it * 2 }
    }

    override fun produceConfig(processed: List<Int>): MapLibreLayerConfig? {
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

class BaseMapLayerAndroidTest {
    @Test
    fun lifecycle_runs_pipeline_and_disable_calls_onDisable() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val layer = TestLayer()

        layer.enable(context, quality = 1f)
        Thread.sleep(100)
        assertTrue(layer.before)
        assertTrue(layer.loaded)
        assertTrue(layer.processed)
        assertTrue(layer.configProduced)
        assertNotNull(layer.lastConfig)

        layer.disable()
        Thread.sleep(30)
        assertTrue(layer.disabled)
        assertNull(layer.lastConfig)
    }

    @Test
    fun enable_twice_restarts_pipeline_without_crash() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val layer = TestLayer()
        layer.enable(context, quality = 1f)
        Thread.sleep(40)
        layer.enable(context, quality = 0.5f)
        Thread.sleep(100)
        assertTrue(layer.configProduced)
        layer.disable()
    }
}
