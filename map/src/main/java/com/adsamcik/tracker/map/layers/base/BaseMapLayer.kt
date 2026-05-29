package com.adsamcik.tracker.map.layers.base

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Base template for map layers.
 *
 * enable(context, quality) runs the pipeline:
 *  beforeEnable -> loadData -> processData(with budgets) -> produce config
 *
 * Subclasses implement the abstract steps. All heavy work is off the main thread.
 * Layers produce [MapLibreLayerConfig] data rather than imperatively mutating a map.
 */
abstract class BaseMapLayer<I, P>(
    private val performanceManager: PerformanceManager = PerformanceManager(),
    private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) {

    private val layerJob = SupervisorJob()
    private val layerScope = CoroutineScope(dispatchers.default + layerJob)

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    protected var quality: Float = 1.0f
        private set

    @Volatile
    protected var zoom: Float = 10f

    private var runningTask: Job? = null

    /** The last produced layer config, available for the engine to read. */
    @Volatile
    var lastConfig: MapLibreLayerConfig? = null
        private set

    /**
     * Start the layer. If already enabled, the running work is cancelled and the layer restarts.
     * @param bounds Optional viewport bounds for spatial filtering. Null loads all data.
     */
    fun enable(context: Context, quality: Float, bounds: Bounds? = null, zoom: Float = 10f): Job {
        val startTime = System.currentTimeMillis()

        synchronized(this@BaseMapLayer) {
            if (enabled) {
                disable()
            }
            this.quality = quality
            this.zoom = zoom
            enabled = true
        }

        val job = layerScope.launch {
            try {
                beforeEnable(context)

                val loadStartTime = System.currentTimeMillis()
                val input = loadData(context, bounds)
                val loadDuration = System.currentTimeMillis() - loadStartTime

                val processStartTime = System.currentTimeMillis()
                val budgets = performanceManager.budgets(quality, zoom)
                val processed = processData(input, budgets)
                val processDuration = System.currentTimeMillis() - processStartTime

                if (enabled) {
                    val renderStartTime = System.currentTimeMillis()
                    val config = produceConfig(processed)
                    lastConfig = config
                    val renderDuration = System.currentTimeMillis() - renderStartTime

                    withContext(dispatchers.main) {
                        if (enabled) {
                            afterEnable()
                            val totalDuration = System.currentTimeMillis() - startTime
                            onPerformanceMetrics(loadDuration, processDuration, renderDuration, totalDuration)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Structured concurrency: cancellation MUST propagate up. Catching
                // Throwable below would otherwise eat it, leaving supervisors thinking
                // the layer completed normally when in fact it was cancelled.
                throw e
            } catch (e: Exception) {
                // Recoverable rendering/loading failure. OOM and other Errors are
                // intentionally NOT caught here — they should crash and report.
                onPipelineError(e)
            }
        }
        synchronized(this@BaseMapLayer) {
            runningTask = job
        }
        return job
    }

    /**
     * Refresh data for an already-enabled layer without running enable/disable hooks.
     * Used for viewport-only camera changes where MapLibre sources/layers should stay stable.
     */
    suspend fun reloadData(context: Context, bounds: Bounds? = null, zoom: Float = this.zoom): MapLibreLayerConfig? =
        withContext(dispatchers.default) {
            val startTime = System.currentTimeMillis()
            val currentQuality = synchronized(this@BaseMapLayer) {
                if (!enabled) return@withContext lastConfig
                this.zoom = zoom
                quality
            }

            try {
                val loadStartTime = System.currentTimeMillis()
                val input = loadData(context, bounds)
                val loadDuration = System.currentTimeMillis() - loadStartTime

                val processStartTime = System.currentTimeMillis()
                val budgets = performanceManager.budgets(currentQuality, zoom)
                val processed = processData(input, budgets)
                val processDuration = System.currentTimeMillis() - processStartTime

                val renderStartTime = System.currentTimeMillis()
                val config = produceConfig(processed)
                val renderDuration = System.currentTimeMillis() - renderStartTime

                synchronized(this@BaseMapLayer) {
                    if (enabled) {
                        lastConfig = config
                    }
                }
                val totalDuration = System.currentTimeMillis() - startTime
                onPerformanceMetrics(loadDuration, processDuration, renderDuration, totalDuration)
                config
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onPipelineError(e)
                lastConfig
            }
        }

    /** Cancel work and reset config. */
    fun disable() {
        synchronized(this@BaseMapLayer) {
            if (!enabled) return
            enabled = false
            runningTask?.cancel()
            runningTask = null
            lastConfig = null
        }
        onDisable()
    }

    /** Hook: called on background thread before loading data. */
    protected open fun beforeEnable(context: Context) {}

    /** Implement: load domain data (I) from repositories/DAOs. Heavy work allowed. Suspend allowed.
     *  @param bounds Optional viewport bounds for spatial filtering. Null means load all data.
     */
    protected abstract suspend fun loadData(context: Context, bounds: Bounds?): I

    /** Implement/override: transform/filter data using performance budgets. Heavy work allowed. */
    protected abstract fun processData(
        input: I,
        budgets: PerformanceManager.PerformanceBudgets
    ): P

    /** Implement: produce a [MapLibreLayerConfig] from processed data. */
    protected abstract fun produceConfig(processed: P): MapLibreLayerConfig?

    /** Hook: on Main after config is produced. */
    protected open fun afterEnable() {}

    /** Hook: when disabling; cleanup resources. */
    protected open fun onDisable() {}

    /** Hook: background error reporting for the pipeline. */
    protected open fun onPipelineError(error: Throwable) { /* no-op by default */ }

    /** Hook: performance metrics reporting. All durations in milliseconds. */
    protected open fun onPerformanceMetrics(
        loadDuration: Long,
        processDuration: Long,
        renderDuration: Long,
        totalDuration: Long
    ) {
        // no-op by default; subclasses can override for monitoring
    }
}
