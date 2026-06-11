package com.adsamcik.tracker.map.layers.base

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

private class TestLayer(
    dispatcher: TestDispatcher,
) : BaseMapLayer<List<Int>, List<Int>>(
    PerformanceManager(),
    TestDispatchersProvider(dispatcher),
) {
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

@OptIn(ExperimentalCoroutinesApi::class)
@Disabled("Moved to androidTest; depends on Android Context")
class BaseMapLayerTest {
    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun lifecycle_runs_pipeline_and_disable_calls_onDisable() = runTest(testDispatcher) {
        val context: Context = mockk(relaxed = true)
        val layer = TestLayer(testDispatcher)

        val enableJob = layer.enable(context, quality = 1f)
        advanceUntilIdle()

        enableJob.isCompleted shouldBe true
        layer.before shouldBe true
        layer.loaded shouldBe true
        layer.processed shouldBe true
        layer.configProduced shouldBe true
        layer.lastConfig.shouldNotBeNull()

        layer.disable()
        runCurrent()
        layer.disabled shouldBe true
        layer.lastConfig.shouldBeNull()
    }

    @Test
    fun enable_twice_restarts_pipeline_without_crash() = runTest(testDispatcher) {
        val context: Context = mockk(relaxed = true)
        val layer = TestLayer(testDispatcher)

        layer.enable(context, quality = 1f)
        runCurrent()
        layer.enable(context, quality = 0.5f)
        advanceUntilIdle()

        layer.configProduced shouldBe true
        layer.disable()
    }
}
