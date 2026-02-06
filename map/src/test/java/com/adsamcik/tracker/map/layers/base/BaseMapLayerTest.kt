package com.adsamcik.tracker.map.layers.base

import android.content.Context
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.google.android.gms.maps.GoogleMap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Ignore
import org.mockito.kotlin.mock
import org.junit.runner.RunWith

private class TestLayer : BaseMapLayer<List<Int>, List<Int>>(PerformanceManager()) {
    @Volatile var before = false
    @Volatile var loaded = false
    @Volatile var processed = false
    @Volatile var rendered = false
    @Volatile var disabled = false

    override fun beforeEnable(context: Context, map: GoogleMap) {
        before = true
    }

    override suspend fun loadData(context: Context): List<Int> {
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

@Ignore("Moved to androidTest; depends on Android/GoogleMap")
class BaseMapLayerTest {
    @Test
    fun lifecycle_runs_pipeline_and_disable_calls_onDisable() {
    val map: GoogleMap = mock()
    val context: Context = mock()
        val layer = TestLayer()

    layer.enable(context, map, quality = 1f)
    // Allow background work to complete; render posts to main but our test only checks flags
    Thread.sleep(50)
        assertTrue(layer.before)
        assertTrue(layer.loaded)
        assertTrue(layer.processed)
        assertTrue(layer.rendered)

        layer.disable()
    Thread.sleep(10)
        assertTrue(layer.disabled)
    }

    @Test
    fun enable_twice_restarts_pipeline_without_crash() {
    val map: GoogleMap = mock()
    val context: Context = mock()
        val layer = TestLayer()
    layer.enable(context, map, quality = 1f)
    Thread.sleep(20)
    layer.enable(context, map, quality = 0.5f)
    Thread.sleep(50)
        assertTrue(layer.rendered)
        layer.disable()
    }
}
