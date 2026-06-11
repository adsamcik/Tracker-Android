package com.adsamcik.tracker.logger

import android.content.Context
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.StringRes
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.logger.concurrency.LoggerDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * Logs important information.
 * Follows user preferences about logging.
 */
object Logger : CoroutineScope {
    @PublishedApi
    internal const val GLOBAL_LOG_ENABLED_KEY = "log_enabled"

    @PublishedApi
    internal const val GLOBAL_LOG_ENABLED_DEFAULT = true

    @PublishedApi
    internal const val PERFORMANCE_LOG_ENABLED_KEY = "log_performance_enable"

    @PublishedApi
    internal const val PERFORMANCE_LOG_ENABLED_DEFAULT = false

    private val job = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = LoggerDispatchers.default + job

    private var genericDao: GenericLogDao? = null

    private var initDeferred = CompletableDeferred<Unit>()

    @Volatile
    private var isInitialized = false

    private var preferences: Preferences? = null
    
    // Buffer for logs that come in before initialization completes
    private val logBuffer = java.util.concurrent.ConcurrentLinkedQueue<LogData>()

    // Cached gate so log() can return early without paying for PiiRedactor on every call
    // when logging is disabled. Updated atomically by [updateEnabledState] (called once at
    // init + on preference change). Volatile read is single-load fast path.
    @Volatile
    private var logEnabled = GLOBAL_LOG_ENABLED_DEFAULT

    // Job for the long-running preference-observation coroutine. Stored so it can be
    // cancelled on [shutdown] (test isolation) or before re-launching on re-init.
    private var preferenceCollectionJob: Job? = null

    // Tracks the wrapper coroutine spawned by [initialize]. Stored so [shutdown]
    // can cancel a still-running init job before resetting globals, preventing
    // the wrapper from repopulating state AFTER the reset (R3 round-5 race #1).
    @Volatile
    private var initializationJob: Job? = null

    // Serialises the init body with shutdown's reset so they can never interleave.
    // Concurrent [initialize] calls also queue on this mutex; the in-mutex
    // [isInitialized] re-check makes the body idempotent (R3 round-5 race #2).
    private val lifecycleMutex = Mutex()

    fun initialize(context: Context) {
        // Cheap best-effort dedup: if a recent init wrapper is still running, drop
        // this call without paying for a coroutine launch. Mutex re-check below
        // catches anything that slips through this non-synchronised read.
        if (isInitialized) return
        if (initializationJob?.isActive == true) return

        initializationJob = launch(LoggerDispatchers.io) {
            lifecycleMutex.withLock {
                // Re-check inside the mutex: a parallel initialize() may have
                // already populated state while we were queued on the lock.
                if (isInitialized) return@withLock

                val prefs = Preferences(context)
                preferences = prefs
                genericDao = LogDatabase.database(context).genericLogDao()
                // Hydrate the gate from preferences BEFORE flushing the buffer so the
                // flushed calls observe the same enabled flag as live calls.
                @Suppress("DEPRECATION")
                logEnabled = prefs.getBoolean(
                    GLOBAL_LOG_ENABLED_KEY, GLOBAL_LOG_ENABLED_DEFAULT,
                )
                isInitialized = true
                initDeferred.complete(Unit)

                // Flush buffer
                var log: LogData? = logBuffer.poll()
                while (log != null) {
                    logInternal(log)
                    log = logBuffer.poll()
                }

                // Subscribe to preference changes so the cached gate updates when the user
                // toggles the setting. Without this the cache stays stale until process
                // restart; manual refreshEnabledState() calls aren't required.
                preferenceCollectionJob?.cancel()
                preferenceCollectionJob = launch(LoggerDispatchers.io) {
                    prefs.observeBoolean(GLOBAL_LOG_ENABLED_KEY, GLOBAL_LOG_ENABLED_DEFAULT)
                        .collect { newValue -> logEnabled = newValue }
                }
            }
        }
    }

    /**
     * Cancel the preference-watching coroutine and reset all Logger state.
     *
     * **Test-only / cleanup.** Logger is initialized once per process in production.
     * In Robolectric or same-JVM test suites, call this in `@After` so each test's
     * `@Before` call to [initialize] starts from a clean slate with no leaked collectors.
     *
     * Suspends to deterministically join the in-flight init wrapper (cancelling it
     * if still running) BEFORE resetting globals, so a late wrapper cannot
     * repopulate `isInitialized`/`preferences`/`genericDao` after this returns.
     */
    suspend fun shutdown() {
        // Snapshot + null + cancel the init wrapper outside the mutex so any wrapper
        // parked at `mutex.lock()` is cancelled before it ever runs its body.
        val pendingInit = initializationJob
        initializationJob = null
        pendingInit?.cancel()

        lifecycleMutex.withLock {
            pendingInit?.join()
            preferenceCollectionJob?.cancelAndJoin()
            preferenceCollectionJob = null
            isInitialized = false
            initDeferred = CompletableDeferred()
            preferences = null
            genericDao = null
            logBuffer.clear()
            logEnabled = GLOBAL_LOG_ENABLED_DEFAULT
        }
    }

    /**
     * Force-refresh the cached enabled flag. Normally not needed: the Logger
     * subscribes to the preference Flow during [initialize] and auto-updates on
     * toggle. Exposed for tests or paths that bypass the Flow.
     */
    @AnyThread
    fun refreshEnabledState() {
        val prefs = preferences ?: return
        @Suppress("DEPRECATION")
        logEnabled = prefs.getBoolean(GLOBAL_LOG_ENABLED_KEY, GLOBAL_LOG_ENABLED_DEFAULT)
    }

    @AnyThread
    fun log(data: LogData) {
        // Battery-fast gate: skip PiiRedactor allocation + regex scan when logging is
        // off in release. We still log to debug logcat for developer visibility.
        if (!BuildConfig.DEBUG && !logEnabled) return

        val sanitized = data.copy(
            message = PiiRedactor.redact(data.message),
            data = PiiRedactor.redact(data.data)
        )

        if (BuildConfig.DEBUG) {
            Log.d("com.adsamcik.tracker.debug.${sanitized.source}", sanitized.toString())
        }

        if (!isInitialized) {
            logBuffer.add(sanitized)
            return
        }

        logInternal(sanitized)
    }
    
    private fun logInternal(data: LogData) {
        if (!logEnabled) return
        launch {
            genericDao?.insert(data)
        }
    }

    @AnyThread
    fun logWithPreference(data: LogData, @StringRes key: Int, @StringRes default: Int) {
        if (isInitialized) {
            preferences?.let { prefs ->
               // Sync read acceptable: called after async initialization; latency not critical for log gating
               @Suppress("DEPRECATION")
               if (prefs.getBooleanRes(key, default)) {
                   log(data)
               }
            }
        } else {
             // For now, if not initialized, we can't check pref, so we might miss it 
             // or we have to buffer it with the pref key. 
             // Making a design decision to just try logging it, logic inside will check generic pref.
             // But valid point: if we don't know the specific pref, we can't check it.
             // Simplification: Wait for init or drop? 
             // Better approach: Launch a coroutine to wait for init if needed?
             // Given this is performance tracing usually, and 'log' just checks global enable,
             // let's pass it through to 'log' which buffers.
              // However, 'log' still checks the global logging preference.
              // 'logWithPreference' checks a SPECIFIC key.
             
             launch {
                 initDeferred.await()
                 // Sync read acceptable: called after async initialization completes
                 @Suppress("DEPRECATION")
                 if (preferences?.getBooleanRes(key, default) == true) {
                     log(data)
                 }
             }
        }
    }

    @AnyThread
    fun logWithStringPreference(data: LogData, key: String, default: Boolean) {
        if (isInitialized) {
            preferences?.let { prefs ->
                // Sync read acceptable: called after async initialization; latency not critical for log gating
                @Suppress("DEPRECATION")
                if (prefs.getBoolean(key, default)) {
                    log(data)
                }
            }
        } else {
            launch {
                initDeferred.await()
                // Sync read acceptable: called after async initialization completes
                @Suppress("DEPRECATION")
                if (preferences?.getBoolean(key, default) == true) {
                    log(data)
                }
            }
        }
    }


    @AnyThread
    inline fun <R> measureTimeMillis(name: String, method: () -> R): R {
        val result: R
        val time = kotlin.system.measureTimeMillis {
            result = method()
        }
        val message = "Measured time of $name is $time"
        logWithStringPreference(
            LogData(message = message, source = "performance"),
            PERFORMANCE_LOG_ENABLED_KEY,
            PERFORMANCE_LOG_ENABLED_DEFAULT
        )
        if (BuildConfig.DEBUG) {
            Log.d("TrackerPerf", message)
        }

        return result
    }
}
