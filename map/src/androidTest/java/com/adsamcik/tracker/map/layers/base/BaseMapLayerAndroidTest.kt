package com.adsamcik.tracker.map.layers.base

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.google.android.gms.maps.GoogleMap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

private class TestLayer : BaseMapLayer<List<Int>, List<Int>>(PerformanceManager()) {
    @Volatile var before = false
    @Volatile var loaded = false
    @Volatile var processed = false
    @Volatile var rendered = false
    @Volatile var disabled = false

    override fun beforeEnable(context: Context, map: GoogleMap) {
        before = true
    }

    override fun loadData(context: Context): List<Int> {
        loaded = true
        return listOf(1, 2, 3)
    }

    override fun processData(input: List<Int>, budgets: PerformanceManager.PerformanceBudgets): List<Int> {
        processed = true
        return input.map { it * 2 }
    }

    override fun render(map: GoogleMap, processed: List<Int>) {
        rendered = true
    }

    override fun onDisable(map: GoogleMap) {
        disabled = true
    }
}

class BaseMapLayerAndroidTest {
    @Test
    fun lifecycle_runs_pipeline_and_disable_calls_onDisable() {
        val map: GoogleMap = mock()
        val context: Context = ApplicationProvider.getApplicationContext()
        val layer = TestLayer()

        layer.enable(context, map, quality = 1f)
        Thread.sleep(100)
        assertTrue(layer.before)
        assertTrue(layer.loaded)
        assertTrue(layer.processed)
        assertTrue(layer.rendered)

        layer.disable()
        Thread.sleep(30)
        assertTrue(layer.disabled)
    }

    @Test
    fun enable_twice_restarts_pipeline_without_crash() {
        val map: GoogleMap = mock()
        val context: Context = ApplicationProvider.getApplicationContext()
        val layer = TestLayer()
        layer.enable(context, map, quality = 1f)
        Thread.sleep(40)
        layer.enable(context, map, quality = 0.5f)
        Thread.sleep(100)
        assertTrue(layer.rendered)
        layer.disable()
    }
}
