package com.adsamcik.tracker.shared.preferences

import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen

/**
 * Defines structure for dynamic module settings.
 * 
 * **LEGACY:** This interface and its implementations are retained for preference tests only.
 * Production UI uses Compose-based settings in app/.../settings/SettingsRoute.kt.
 * 
 * Active implementations: MapSettings, GameSettings, StatisticsSettings (test-only)
 * 
 * @see com.adsamcik.tracker.app.settings.SettingsRoute
 */
interface ModuleSettings {
	/**
	 * Resources to module settings icon.
	 */
	val iconRes: Int

	/**
	 * Creates module preference screen.
	 */
	fun onCreatePreferenceScreen(preferenceScreen: PreferenceScreen)


	/**
	 * Creates submodule
	 */
	fun createSubmodule(preferenceScreen: PreferenceScreen, submodule: SubmoduleSettings) {
		val category = PreferenceCategory(preferenceScreen.context).apply {
			setTitle(submodule.categoryTitleRes)
		}.also { preferenceScreen.addPreference(it) }

		submodule.onCreatePreferenceCategory(category)
	}
}

/**
 * Utility interface for submodules.
 */
interface SubmoduleSettings {
	/**
	 * Category title resource
	 */
	val categoryTitleRes: Int

	/**
	 * Creates module preference screen.
	 */
	fun onCreatePreferenceCategory(preferenceCategory: PreferenceCategory)
}
