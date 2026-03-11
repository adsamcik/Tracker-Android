package com.adsamcik.tracker.map

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.MapLibre

/**
 * Pre-initializes the MapLibre SDK on a background thread to prevent ANR.
 *
 * [MapLibre.getInstance] triggers [org.maplibre.android.storage.FileSource]
 * `initializeFileDirsPaths` and `internalSetResourcesCachePath`, which take
 * 1.7 s+ on first call due to bytecode verification and native init.
 * Pre-calling on a background thread makes the subsequent main-thread call
 * (from the [org.maplibre.compose.map.MaplibreMap] composable) a cached no-op.
 *
 * Two call sites ensure reliability:
 * 1. [com.adsamcik.tracker.app.Application.startBackgroundStartup] — opportunistic early init.
 * 2. `MapScreen` `LaunchedEffect` — guaranteed fallback before rendering the map.
 */
object MapLibreInitializer {

    private const val TAG = "MapLibreInit"

    private val _isReady = MutableStateFlow(false)

    /** Observe to gate the [org.maplibre.compose.map.MaplibreMap] composable. */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    @Volatile
    private var initialized = false

    /**
     * Pre-initialize the MapLibre SDK. Thread-safe, idempotent.
     * Must be called with an application [Context].
     * Catches all exceptions so a native-library failure does not crash the app;
     * the composable will retry initialization itself on render.
     *
     * Only marks the SDK ready after a successful background init so the first
     * map render never falls back to a heavy synchronous main-thread load.
     */
    @WorkerThread
    fun initialize(context: Context): Boolean {
        if (initialized) return true
        synchronized(this) {
            if (initialized) return true
            return try {
                MapLibre.getInstance(context.applicationContext)
                initialized = true
                _isReady.value = true
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native library not loaded; map will init on render", e)
                false
            } catch (e: Exception) {
                Log.w(TAG, "Pre-initialization failed; map will init on render", e)
                false
            }
        }
    }

    /** Reset state for unit tests. */
    @VisibleForTesting
    internal fun reset() {
        synchronized(this) {
            initialized = false
            _isReady.value = false
        }
    }
}
