package com.adsamcik.tracker.map.layers.base

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Regression test for the [BaseMapLayer.reloadData] reorder race documented in
 * R1 round 5 (Phase 13): rapid pan/zoom can leave two reloadData calls in flight
 * for the same layer, and the synchronous compute step (processData + produceConfig)
 * has no suspension point at which cooperative cancellation could preempt the
 * `lastConfig = config` write. Without a generation guard, whichever finishes LAST
 * wins, even if that call was issued first against a now-stale viewport.
 *
 * This test deterministically orders completion by gating each [loadData] call on
 * a per-call [CompletableDeferred] so we can release the NEWER (B) call before the
 * OLDER (A) call. The assertion proves that A's stale finish does not clobber B's
 * fresh state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("BaseMapLayer reload race")
class BaseMapLayerReloadRaceTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Test layer whose [loadData] returns the requested bounds' north value as a
     * payload string and (optionally) blocks on a per-call gate so callers can
     * release completions in any order.
     */
    private class GatedLayer(
        dispatcher: TestDispatcher,
    ) : BaseMapLayer<String, String>(
        PerformanceManager(),
        TestDispatchersProvider(dispatcher),
    ) {
        /** FIFO queue of per-call gates. The Nth gated loadData call awaits gates.toList()[N]. */
        val gates = ConcurrentLinkedQueue<CompletableDeferred<Unit>>()

        @Volatile
        var gateLoads: Boolean = false

        override suspend fun loadData(context: Context, bounds: Bounds?): String {
            val payload = bounds?.north?.toString() ?: "all"
            if (gateLoads) {
                val gate = CompletableDeferred<Unit>()
                gates.add(gate)
                gate.await()
            }
            return payload
        }

        override fun processData(
            input: String,
            budgets: PerformanceManager.PerformanceBudgets,
        ): String = input

        override fun produceConfig(processed: String): MapLibreLayerConfig =
            MapLibreLayerConfig.Heatmap(
                geoJson = """{"bounds":"$processed"}""",
                colorStops = emptyList(),
            )
    }

    @Test
    fun `stale reload finishing last does not overwrite fresh lastConfig`() = runTest(testDispatcher) {
        val context: Context = mockk(relaxed = true)
        val layer = GatedLayer(testDispatcher)

        // Enable with an initial viewport; loadData is not gated so enable completes
        // synchronously when the dispatcher is advanced.
        layer.enable(
            context,
            quality = 1f,
            bounds = Bounds(north = 1.0, east = 1.0, south = 0.0, west = 0.0),
            zoom = 10f,
        )
        advanceUntilIdle()
        (layer.lastConfig as MapLibreLayerConfig.Heatmap).geoJson shouldBe """{"bounds":"1.0"}"""

        // From now on, every loadData call blocks until we release it.
        layer.gateLoads = true
        val boundsA = Bounds(north = 2.0, east = 2.0, south = 1.0, west = 1.0)
        val boundsB = Bounds(north = 3.0, east = 3.0, south = 2.0, west = 2.0)

        // Fire reload A (generation = 1).
        val jobA = async { layer.reloadData(context, boundsA, zoom = 10f) }
        advanceUntilIdle()
        // Fire reload B (generation = 2) AFTER A is parked at its gate.
        val jobB = async { layer.reloadData(context, boundsB, zoom = 11f) }
        advanceUntilIdle()

        val pendingGates = layer.gates.toList()
        pendingGates.size shouldBe 2

        // Release B FIRST → it is the latest in-flight reload and MUST publish.
        pendingGates[1].complete(Unit)
        advanceUntilIdle()
        val configB = jobB.await() as MapLibreLayerConfig.Heatmap
        configB.geoJson shouldBe """{"bounds":"3.0"}"""
        (layer.lastConfig as MapLibreLayerConfig.Heatmap).geoJson shouldBe """{"bounds":"3.0"}"""

        // Release A SECOND → its compute completes and produces a valid-for-A config,
        // but the generation guard MUST reject the lastConfig write because
        // reloadGeneration has already advanced past A's captured value.
        pendingGates[0].complete(Unit)
        advanceUntilIdle()
        val configA = jobA.await() as MapLibreLayerConfig.Heatmap
        // The returned-from-call value is still A's data (so a caller can populate a
        // per-bounds cache correctly); the layer's published state is the key
        // invariant under test.
        configA.geoJson shouldBe """{"bounds":"2.0"}"""

        val finalConfig = layer.lastConfig
        finalConfig.shouldNotBeNull()
        (finalConfig as MapLibreLayerConfig.Heatmap).geoJson shouldBe """{"bounds":"3.0"}"""
    }
}
