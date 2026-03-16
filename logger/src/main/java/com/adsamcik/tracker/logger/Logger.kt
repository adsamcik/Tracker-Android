package com.adsamcik.tracker.logger

import android.content.Context
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.StringRes
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Logs important information.
 * Follows user preferences about logging.
 */
object Logger : CoroutineScope {
    private val job = SupervisorJob()
    private val dispatchers = DefaultDispatchersProvider

    override val coroutineContext: CoroutineContext
        get() = dispatchers.default + job

    private var genericDao: GenericLogDao? = null

    private var initDeferred = CompletableDeferred<Unit>()

    @Volatile
    private var isInitialized = false

    private var preferences: Preferences? = null
    
    // Buffer for logs that come in before initialization completes
    private val logBuffer = java.util.concurrent.ConcurrentLinkedQueue<LogData>()

    fun initialize(context: Context) {
        if (isInitialized) return
        
        launch(dispatchers.io) {
            preferences = Preferences(context)
            genericDao = LogDatabase.database(context).genericLogDao()
            isInitialized = true
            initDeferred.complete(Unit)
            
            // Flush buffer
            var log: LogData? = logBuffer.poll()
            while (log != null) {
                logInternal(log)
                log = logBuffer.poll()
            }
        }
    }

    @AnyThread
    fun log(data: LogData) {
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
        val prefs = preferences ?: return
        // Sync read acceptable: called after async initialization; latency not critical for log gating
        @Suppress("DEPRECATION")
        if (prefs.getBooleanRes(
                R.string.settings_log_enabled_key,
                R.string.settings_log_enabled_default
            )
        ) {
            launch {
                genericDao?.insert(data)
            }
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
             // However, 'log' checks 'settings_log_enabled_key'.
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
    inline fun <R> measureTimeMillis(name: String, method: () -> R): R {
        val result: R
        val time = kotlin.system.measureTimeMillis {
            result = method()
        }
        val message = "Measured time of $name is $time"
        logWithPreference(
            LogData(message = message, source = "performance"),
            com.adsamcik.tracker.logger.R.string.settings_log_performance_enabled_key,
            com.adsamcik.tracker.logger.R.string.settings_log_performance_enabled_default
        )
        if (BuildConfig.DEBUG) {
            Log.d("TrackerPerf", message)
        }

        return result
    }
}
