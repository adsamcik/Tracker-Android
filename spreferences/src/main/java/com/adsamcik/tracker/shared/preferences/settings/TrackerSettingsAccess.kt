package com.adsamcik.tracker.shared.preferences.settings

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

/**
 * Lightweight singleton accessor that exposes a cached [StateFlow] of [TrackerSettingsState]
 * for legacy call sites still using static helper methods. New code SHOULD inject
 * [TrackerSettingsRepository] directly and observe [TrackerSettingsRepository.data].
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
            // Blocking first emission only once to seed initial value.
            val initial = runBlocking { repo.data.first() }
            val created = repo.data.stateIn(
                scope,
                SharingStarted.Eagerly,
                initial
            )
            stateFlow = created
            return created
        }
    }

    fun snapshot(context: Context): TrackerSettingsState = ensureFlow(context).value

    fun flow(context: Context): StateFlow<TrackerSettingsState> = ensureFlow(context)
}
