package com.adsamcik.tracker.map.layers.base

import android.content.Context
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.mockk
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

private class TestLayer : BaseMapLayer<List<Int>, List<Int>>(PerformanceManager()) {
    @Volatile var before = false
    @Volatile var loaded = false
    @Volatile var processed = false
    @Volatile var configProduced = false
    @Volatile var disabled = false

    override fun beforeEnable(context: Context) {
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

@Disabled("Moved to androidTest; depends on Android Context")
class BaseMapLayerTest {
    @Test
    fun lifecycle_runs_pipeline_and_disable_calls_onDisable() {
        val context: Context = mockk(relaxed = true)
        val layer = TestLayer()

        layer.enable(context, quality = 1f)
        // Allow background work to complete
        Thread.sleep(50)
        layer.before shouldBe true
        layer.loaded shouldBe true
        layer.processed shouldBe true
        layer.configProduced shouldBe true
        layer.lastConfig.shouldNotBeNull()

        layer.disable()
        Thread.sleep(10)
        layer.disabled shouldBe true
        layer.lastConfig.shouldBeNull()
    }

    @Test
    fun enable_twice_restarts_pipeline_without_crash() {
        val context: Context = mockk(relaxed = true)
        val layer = TestLayer()
        layer.enable(context, quality = 1f)
        Thread.sleep(20)
        layer.enable(context, quality = 0.5f)
        Thread.sleep(50)
        layer.configProduced shouldBe true
        layer.disable()
    }
}
