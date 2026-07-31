package com.adsamcik.tracker.map

import android.content.Context
import android.os.StrictMode
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

    private val _isReady = MutableStateFlow(false)

    /** Observe to gate the [org.maplibre.compose.map.MaplibreMap] composable. */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var registeredCallFactory: Call.Factory? = null

    @Volatile
    private var pendingCallFactory: Call.Factory? = null

    /**
     * Register the [Call.Factory] MapLibre will use for ALL outbound HTTP.
     *
     * Pass the result of
     * `(networkGateway as? OkHttpBackedGateway)?.okHttpCallFactory()` so MapLibre
     * traffic shares the gateway's interceptor chain (kill switch, allowlist,
     * rate limit).
     *
     * Idempotent — safe to call from multiple call sites; a `null` argument
     * is a no-op (does not unregister an already-set factory).
     *
     * # Ordering relative to [initialize]
     *
     * `HttpRequestUtil.setOkHttpClient` triggers the static initializer of
     * `org.maplibre.android.module.http.HttpRequestImpl`, which calls
     * `MapLibre.getApplicationContext()` — that throws
     * `MapLibreConfigurationException` if [MapLibre.getInstance] has never
     * been called. So calling this method *before* [initialize] cannot
     * actually register the factory.
     *
     * To keep [com.adsamcik.tracker.app.Application.onCreate] simple — it must
     * wire the gateway at process start, long before any [MapLibre] feature
     * triggers init — this method **stashes the factory** when MapLibre is
     * not yet initialized, and [initialize] applies it as soon as
     * `MapLibre.getInstance(...)` succeeds. The result is that the call
     * factory is *always* in place before MapLibre issues its first request.
     * Without this stash, the NetworkPolicyAggregator (kill switch /
     * allowlist / rate limit) is silently bypassed for the entire map
     * subsystem (R7 emulator finding: maplibre-init-order).
     */
    fun setHttpCallFactory(callFactory: Call.Factory?) {
        if (callFactory == null) return
        synchronized(this) {
            if (registeredCallFactory === callFactory) return
            if (!initialized) {
                // MapLibre.getInstance() hasn't run yet. Eager
                // HttpRequestUtil.setOkHttpClient would touch
                // HttpRequestImpl.<clinit> -> MapLibre.validateMapLibre() ->
                // MapLibreConfigurationException. Defer until initialize().
                pendingCallFactory = callFactory
                return
            }
            applyCallFactoryLocked(callFactory)
        }
    }

    private fun applyCallFactoryLocked(callFactory: Call.Factory) {
        try {
            HttpRequestUtil.setOkHttpClient(callFactory)
            // Only record the factory as registered AFTER the static setter
            // succeeded. If setOkHttpClient throws (LinkageError in
            // Robolectric, or any other failure), leaving registeredCallFactory
            // unset lets a follow-up call with the SAME factory retry the
            // registration -- otherwise the identity short-circuit at the top
            // of setHttpCallFactory would silently swallow the second call
            // even though MapLibre's global call factory was never actually
            // replaced (R3 round 7 finding: mapinit-factory-record-order).
            registeredCallFactory = callFactory
            if (pendingCallFactory === callFactory) pendingCallFactory = null
        } catch (_: LinkageError) {
            // Same JVM-without-native-lib path as initialize(); on Robolectric
            // unit tests both UnsatisfiedLinkError and NoClassDefFoundError can
            // surface when MapLibre's native HTTP impl class can't link. The
            // production path always has the native lib loaded -- silent skip
            // is the right behavior in tests.
            return
        } catch (_: Exception) {
            return
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
                    // MapLibre mandates main-thread SDK bootstrap (see class kdoc). getInstance()
                    // loads the native library and initializes FileSource cache paths — both are
                    // disk reads that, by MapLibre's threading contract, MUST run on this main
                    // thread and cannot be deferred to a background dispatcher. Scope a StrictMode
                    // disk-read permit around just this call so the known, one-time, non-actionable
                    // library bootstrap does not surface as a DiskReadViolation. (App-owned reads —
                    // basemap path / style JSON — are already deferred off-main in MapScreen.)
                    val previousPolicy = StrictMode.allowThreadDiskReads()
                    try {
                        MapLibre.getInstance(context.applicationContext)
                    } finally {
                        StrictMode.setThreadPolicy(previousPolicy)
                    }
                    initialized = true
                    _isReady.value = true
                    // Apply any factory that was stashed by setHttpCallFactory
                    // calls that ran before MapLibre was bootstrapped.
                    // Without this, the NetworkPolicyAggregator wired up in
                    // Application.onCreate is silently bypassed by every
                    // MapLibre HTTP request (kill switch / allowlist /
                    // rate-limit all dead). Apply once initialize succeeds.
                    val pending = pendingCallFactory
                    if (pending != null && registeredCallFactory !== pending) {
                        applyCallFactoryLocked(pending)
                    }
                    true
                } catch (_: UnsatisfiedLinkError) {
                    false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
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
            pendingCallFactory = null
        }
    }
}
