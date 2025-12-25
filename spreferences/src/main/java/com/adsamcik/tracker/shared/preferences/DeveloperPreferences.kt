package com.adsamcik.tracker.shared.preferences

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Contract: Manages developer mode state for hiding/showing debug settings
 * Inputs: Context for SharedPreferences access
 * Outputs: Boolean state of developer mode
 * Failure modes: None (defaults to false if unset)
 */
object DeveloperPreferences {
    private const val PREF_DEVELOPER_MODE = "developer_mode_enabled"
    
    /**
     * Check if developer mode is currently enabled.
     * Developer mode allows access to debug settings in release builds via 7-tap gesture.
     */
    fun isDeveloperModeEnabled(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_DEVELOPER_MODE, false)
    }
    
    /**
     * Enable or disable developer mode.
     * When enabled, debug settings menu becomes visible in release builds.
     */
    fun setDeveloperMode(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_DEVELOPER_MODE, enabled)
        }
    }
}
