package com.adsamcik.tracker.map

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.MapLibre

/**
 * Initializes the MapLibre SDK on the UI thread before the map composable renders.
 *
 * [MapLibre.getInstance] triggers [org.maplibre.android.storage.FileSource]
 * `initializeFileDirsPaths` and `internalSetResourcesCachePath`, which take
 * 1.7 s+ on first call due to bytecode verification and native init.
 *
 * Newer MapLibre builds enforce main-thread access even for SDK bootstrap.
 * Callers can safely invoke this from any coroutine context; initialization is
 * marshaled onto [Dispatchers.Main.immediate] and remains idempotent.
 *
 * This initializer is intentionally used lazily from `MapScreen` so app startup
 * stays responsive while still guaranteeing the SDK is ready before map render.
 */
object MapLibreInitializer {

    private const val TAG = "MapLibreInit"

    private val _isReady = MutableStateFlow(false)

    /** Observe to gate the [org.maplibre.compose.map.MaplibreMap] composable. */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    @Volatile
    private var initialized = false

    suspend fun initialize(context: Context): Boolean {
        if (initialized) return true

        return withContext(Dispatchers.Main.immediate) {
            synchronized(this@MapLibreInitializer) {
                if (initialized) return@withContext true

                try {
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
