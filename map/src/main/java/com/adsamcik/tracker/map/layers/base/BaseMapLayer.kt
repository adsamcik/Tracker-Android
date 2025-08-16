package com.adsamcik.tracker.map.layers.base

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.google.android.gms.maps.GoogleMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Base template for v2 map layers.
 *
 * enable(context, map, quality) runs the pipeline:
 *  beforeEnable -> loadData -> processData(with budgets) -> render (on Main) -> afterEnable (on Main)
 *
 * Subclasses implement the abstract steps. All heavy work is off the main thread,
 * while map mutations (render/afterEnable/onDisable) are executed on Main.
 */
abstract class BaseMapLayer<I, P>(
    private val performanceManager: PerformanceManager = PerformanceManager(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var enabled: Boolean = false

    protected var quality: Float = 1.0f
        private set

    protected lateinit var map: GoogleMap
        private set

    private var runningTask: Future<*>? = null

    /**
     * Start the layer. If already enabled, the running work is cancelled and the layer restarts.
     */
    fun enable(context: Context, map: GoogleMap, quality: Float) {
        // Restart behavior: cancel any existing work and mark disabled before starting anew
        if (enabled) {
            disable()
        }
        this.map = map
        this.quality = quality
        enabled = true

        runningTask = executor.submit(Callable {
            try {
                beforeEnable(context, map)
                val input = loadData(context)
                val budgets = performanceManager.budgets(quality)
                val processed = processData(input, budgets)
                if (enabled) {
                    mainHandler.post {
                        if (enabled) {
                            try {
                                render(map, processed)
                                afterEnable(map)
                            } catch (t: Throwable) {
                                onPipelineError(t)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                // Swallow to keep app stable; subclasses may override to report
                onPipelineError(t)
            }
        })
    }

    /** Cancel work and teardown any map artifacts on the main thread. */
    fun disable() {
        if (!enabled) return
        enabled = false
        runningTask?.cancel(true)
        runningTask = null
        if (this::map.isInitialized) {
            mainHandler.post {
                try {
                    onDisable(map)
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** Hook: called on background thread before loading data. */
    protected open fun beforeEnable(context: Context, map: GoogleMap) {}

    /** Implement: load domain data (I) from repositories/DAOs. Heavy work allowed. */
    protected abstract fun loadData(context: Context): I

    /** Implement/override: transform/filter data using performance budgets. Heavy work allowed. */
    protected abstract fun processData(
        input: I,
        budgets: PerformanceManager.PerformanceBudgets
    ): P

    /** Implement: draw/update map with processed data on Main thread. */
    protected abstract fun render(map: GoogleMap, processed: P)

    /** Hook: on Main after render completes. */
    protected open fun afterEnable(map: GoogleMap) {}

    /** Hook: on Main when disabling; remove overlays, listeners, etc. */
    protected open fun onDisable(map: GoogleMap) {}

    /** Hook: background error reporting for the pipeline. */
    protected open fun onPipelineError(error: Throwable) { /* no-op by default */ }
}
