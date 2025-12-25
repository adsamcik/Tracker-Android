package com.adsamcik.tracker.shared.preferences.settings

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Lightweight singleton accessor that exposes a cached [StateFlow] of [TrackerSettingsState]
 * for legacy call sites still using static helper methods. New code SHOULD inject
 * [TrackerSettingsRepository] directly and observe [TrackerSettingsRepository.data].
 * 
 * Plan 5 migration: Removed runBlocking; uses default values until first emission arrives.
 */
internal object TrackerSettingsAccess {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var stateFlow: StateFlow<TrackerSettingsState>? = null

    private fun ensureFlow(context: Context): StateFlow<TrackerSettingsState> {
        val current = stateFlow
        if (current != null) return current
        synchronized(this) {
            val again = stateFlow
            if (again != null) return again
            val repo = DefaultTrackerSettingsRepository(
                context.applicationContext,
                Dispatchers.IO
            ) { msg -> /* no-op default; app layer may provide structured logger by constructing repo directly */ }
            // Use sensible defaults as initial value; actual settings arrive asynchronously.
            // SharingStarted.Eagerly ensures flow starts collecting immediately.
            val created = repo.data.stateIn(
                scope,
                SharingStarted.Eagerly,
                TrackerSettingsState.DEFAULT
            )
            stateFlow = created
            return created
        }
    }

    fun snapshot(context: Context): TrackerSettingsState = ensureFlow(context).value

    fun flow(context: Context): StateFlow<TrackerSettingsState> = ensureFlow(context)
}
