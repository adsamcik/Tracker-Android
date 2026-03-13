package com.adsamcik.tracker.shared.preferences

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Contract: Manages developer mode state for hiding/showing debug settings.
 * Inputs: Context for DataStore access (via LegacyPreferenceStore).
 * Outputs: Flow<Boolean> for reactive observation; suspend setter for mutation.
 * Failure modes: None (defaults to false if unset).
 *
 * Migration note: Previously backed by PreferenceManager.getDefaultSharedPreferences().
 * Now uses the Preferences DataStore via [LegacyPreferenceStore], which automatically
 * migrated the old SharedPreferences key on first access.
 */
object DeveloperPreferences {
    private const val PREF_DEVELOPER_MODE = "developer_mode_enabled"

    /**
     * Continuous stream of developer mode state.
     * Emits current value immediately and on every change.
     */
    fun observeDeveloperMode(context: Context): Flow<Boolean> {
        return Preferences(context).observeBoolean(PREF_DEVELOPER_MODE, false)
    }

    /**
     * Check if developer mode is currently enabled (snapshot).
     * Prefer [observeDeveloperMode] for reactive UI; this is for one-off checks
     * where a coroutine scope is not available.
     */
    fun isDeveloperModeEnabled(context: Context): Boolean {
        @Suppress("DEPRECATION")
        return Preferences(context).getBoolean(PREF_DEVELOPER_MODE, false)
    }

    /**
     * Enable or disable developer mode.
     * When enabled, debug settings menu becomes visible in release builds.
     */
    fun setDeveloperMode(context: Context, enabled: Boolean) {
        Preferences(context).edit {
            setBoolean(PREF_DEVELOPER_MODE, enabled)
        }
    }
}
