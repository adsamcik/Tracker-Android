package com.adsamcik.tracker.shared.preferences.store

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Central store backing legacy preference APIs with DataStore.
 * Performs one-time migration from default SharedPreferences and exposes
 * both synchronous snapshots (for existing callers) and cold Flows for observers.
 * 
 * Plan 5 migration: Removed runBlocking; uses empty preferences as initial value.
 */
internal object LegacyPreferenceStore {
    private val dispatchers: DispatchersProvider = DefaultDispatchersProvider

    /**
     * Application-scoped CoroutineScope for DataStore operations.
     *
     * This scope is intentionally tied to the application's process lifetime.
     * It lives for the entire duration of the app process and requires no explicit
     * cancellation, as it will be cleaned up when the process terminates.
     * Uses [SupervisorJob] to prevent failure propagation between independent operations.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val stateFlowRef = AtomicReference<StateFlow<Preferences>?>(null)

    private val Context.legacyDataStore by preferencesDataStore(
        name = "legacy_preferences",
        produceMigrations = { ctx ->
            val name = defaultSharedPreferencesName(ctx)
            listOf(SharedPreferencesMigration(ctx, name))
        }
    )

    private fun defaultSharedPreferencesName(context: Context): String =
        "${context.packageName}_preferences"

    private fun ensureStateFlow(context: Context): StateFlow<Preferences> {
        stateFlowRef.get()?.let { return it }
        synchronized(this) {
            stateFlowRef.get()?.let { return it }
            val appContext = context.applicationContext
            // Plan 5 migration: Removed runBlocking; use empty preferences as initial value.
            // Actual data arrives asynchronously via SharingStarted.Eagerly.
            val flow = appContext.legacyDataStore.data.stateIn(
                scope,
                SharingStarted.Eagerly,
                androidx.datastore.preferences.core.emptyPreferences()
            )
            stateFlowRef.set(flow)
            return flow
        }
    }

    fun snapshot(context: Context): Preferences = ensureStateFlow(context).value

    suspend fun freshSnapshot(context: Context): Preferences {
        val appContext = context.applicationContext
        val prefs = appContext.legacyDataStore.data.first()
        val smartGoalNotificationsKey = booleanPreferencesKey(SMART_GOAL_NOTIFICATIONS_KEY)
        if (prefs.contains(smartGoalNotificationsKey)) return prefs
        appContext.legacyDataStore.edit { it[smartGoalNotificationsKey] = false }
        return appContext.legacyDataStore.data.first()
    }

    fun edit(context: Context, operations: List<(androidx.datastore.preferences.core.MutablePreferences) -> Unit>) {
        if (operations.isEmpty()) return
        val appContext = context.applicationContext
        val ops = operations.toList()
        scope.launch {
            appContext.legacyDataStore.edit { prefs ->
                ops.forEach { it(prefs) }
            }
        }
    }

    /**
     * Suspend version of [edit] that awaits completion.
     * Used when caller needs to ensure operations are persisted before continuing.
     */
    suspend fun editSuspend(context: Context, operations: List<(androidx.datastore.preferences.core.MutablePreferences) -> Unit>) {
        if (operations.isEmpty()) return
        val appContext = context.applicationContext
        val ops = operations.toList()
        appContext.legacyDataStore.edit { prefs ->
            ops.forEach { it(prefs) }
        }
    }

    fun booleanFlow(context: Context, key: String, default: Boolean): Flow<Boolean> =
        ensureStateFlow(context)
            .map { prefs -> prefs[booleanPreferencesKey(key)] ?: defaultBooleanFor(key, default) }
            .distinctUntilChanged()

    fun intFlow(context: Context, key: String, default: Int): Flow<Int> =
        ensureStateFlow(context)
            .map { prefs -> prefs[intPreferencesKey(key)] ?: default }
            .distinctUntilChanged()

    fun longFlow(context: Context, key: String, default: Long): Flow<Long> =
        ensureStateFlow(context)
            .map { prefs -> prefs[longPreferencesKey(key)] ?: default }
            .distinctUntilChanged()

    fun floatFlow(context: Context, key: String, default: Float): Flow<Float> =
        ensureStateFlow(context)
            .map { prefs -> prefs[floatPreferencesKey(key)] ?: default }
            .distinctUntilChanged()

    fun stringFlow(context: Context, key: String, default: String): Flow<String> =
        ensureStateFlow(context)
            .map { prefs -> prefs[stringPreferencesKey(key)] ?: default }
            .distinctUntilChanged()

    /**
     * Forces any pending DataStore writes to complete.
     * DataStore serializes all write operations, so an identity [updateData]
     * will only return after every previously-queued edit has been persisted.
     */
    suspend fun flush(context: Context) {
        val appContext = context.applicationContext
        appContext.legacyDataStore.updateData { it }
    }

    /**
     * Flow for values that may be stored as either Int or String (migration compatibility).
     * Returns String representation regardless of underlying storage type.
     */
    fun stringOrIntFlow(context: Context, key: String, default: String): Flow<String> =
        ensureStateFlow(context)
            .map { prefs ->
                // Check if int key exists first to avoid cast exception
                val intKey = intPreferencesKey(key)
                val stringKey = stringPreferencesKey(key)
                
                when {
                    prefs.contains(intKey) -> prefs[intKey]?.toString() ?: default
                    prefs.contains(stringKey) -> prefs[stringKey] ?: default
                    else -> default
                }
            }
            .distinctUntilChanged()

    internal fun resetForTests() {
        stateFlowRef.set(null)
    }

    private fun defaultBooleanFor(key: String, default: Boolean): Boolean = when (key) {
        SMART_GOAL_NOTIFICATIONS_KEY -> false
        else -> default
    }

    private const val SMART_GOAL_NOTIFICATIONS_KEY = "smartGoalNotifications"
}
