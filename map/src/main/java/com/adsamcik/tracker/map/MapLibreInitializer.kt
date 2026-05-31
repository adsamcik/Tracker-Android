package com.adsamcik.tracker.map

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * Initializes the MapLibre SDK on the UI thread before the map composable renders.
 *
 * [MapLibre.getInstance] triggers [org.maplibre.android.storage.FileSource]
 * `initializeFileDirsPaths` and `internalSetResourcesCachePath`, which take
 * 1.7 s+ on first call due to bytecode verification and native init.
 *
 * Newer MapLibre builds enforce main-thread access even for SDK bootstrap.
 * Callers can safely invoke this from any coroutine context; initialization is
 * marshaled onto the provided main dispatcher and remains idempotent.
 *
 * This initializer is intentionally used lazily from `MapScreen` so app startup
 * stays responsive while still guaranteeing the SDK is ready before map render.
 *
 * # HTTP wiring through NetworkGateway
 *
 * The app's [com.adsamcik.tracker.network.NetworkGateway] is the single chokepoint
 * for all egress. When MapLibre needs to fetch online style/tile/sprite/glyph
 * resources it MUST use the same OkHttp client that backs the gateway so the
 * kill switch, allowlist, and rate-limit interceptors apply uniformly.
 *
 * [Application.onCreate] calls [setHttpCallFactory] with the gateway's
 * `okHttpCallFactory()` BEFORE the first map renders. The call delegates to
 * [HttpRequestUtil.setOkHttpClient] which is a process-wide static — once set
 * it covers every subsequent MapLibre HTTP request without per-request
 * registration. Setting it is idempotent; passing the same factory twice
 * has no effect.
 */
object MapLibreInitializer {

    private const val TAG = "MapLibreInit"

    private val _isReady = MutableStateFlow(false)

    /** Observe to gate the [org.maplibre.compose.map.MaplibreMap] composable. */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var registeredCallFactory: Call.Factory? = null

    /**
     * Register the [Call.Factory] MapLibre will use for ALL outbound HTTP.
     *
     * Pass the result of
     * `(networkGateway as? OkHttpBackedGateway)?.okHttpCallFactory()` so MapLibre
     * traffic shares the gateway's interceptor chain (kill switch, allowlist,
     * rate limit).
     *
     * Idempotent — safe to call from multiple call sites; a `null` argument
     * is a no-op (does not unregister an already-set factory). Can be called
     * before OR after [initialize]; MapLibre's `HttpRequestUtil.setOkHttpClient`
     * is a process-global setter that takes effect on the next request.
     */
    fun setHttpCallFactory(callFactory: Call.Factory?) {
        if (callFactory == null) return
        if (registeredCallFactory === callFactory) return
        try {
            HttpRequestUtil.setOkHttpClient(callFactory)
            // Only record the factory as registered AFTER the static setter
            // succeeded. If setOkHttpClient throws (LinkageError in
            // Robolectric, or any other failure), leaving registeredCallFactory
            // unset lets a follow-up call with the SAME factory retry the
            // registration -- otherwise the identity short-circuit at the top
            // would silently swallow the second call even though MapLibre's
            // global call factory was never actually replaced (R3 round 7
            // finding: mapinit-factory-record-order).
            registeredCallFactory = callFactory
        } catch (e: LinkageError) {
            // Same JVM-without-native-lib path as initialize(); on Robolectric
            // unit tests both UnsatisfiedLinkError and NoClassDefFoundError can
            // surface when MapLibre's native HTTP impl class can't link. The
            // production path always has the native lib loaded -- silent skip
            // is the right behavior in tests.
            Log.w(TAG, "setOkHttpClient failed (native lib missing in unit test?)", e)
        } catch (e: Exception) {
            Log.w(TAG, "setOkHttpClient failed", e)
        }
    }

    suspend fun initialize(
        context: Context,
        dispatchers: DispatchersProvider = DefaultDispatchersProvider,
    ): Boolean {
        if (initialized) return true

        val mainImmediate = (dispatchers.main as? MainCoroutineDispatcher)?.immediate ?: dispatchers.main
        return withContext(mainImmediate) {
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
                } catch (e: CancellationException) {
                    throw e
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
            registeredCallFactory = null
        }
    }
}

