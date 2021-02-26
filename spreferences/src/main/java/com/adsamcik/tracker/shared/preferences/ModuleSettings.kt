package com.adsamcik.tracker.shared.preferences

import androidx.preference.PreferenceScreen

/**
 * Defines structure for dynamic module settings.
 */
interface ModuleSettings : SubmoduleSettings {
	/**
	 * Resources to module settings icon.
	 */
	val iconRes: Int
}

/**
 * Utility interface for submodules.
 */
interface SubmoduleSettings {
	/**
	 * Creates module preference screen.
	 */
	fun onCreatePreferenceScreen(preferenceScreen: PreferenceScreen)
}
