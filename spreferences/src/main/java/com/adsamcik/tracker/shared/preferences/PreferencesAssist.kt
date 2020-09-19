package com.adsamcik.tracker.shared.preferences

import android.content.Context

/**
 * Utility object that provides methods for common preference operations.
 */
object PreferencesAssist {
	/**
	 * Checks if there is anything to track
	 *
	 * @param context context
	 * @return true if at least one fo location, cell and wifi tracking is enabled
	 */
	fun hasAnythingToTrack(context: Context): Boolean {
		val preferences = Preferences.getPref(context)

		return preferences.getBooleanRes(
				R.string.settings_location_enabled_key,
				R.string.settings_location_enabled_default
		) ||
				preferences.getBooleanRes(
						R.string.settings_cell_enabled_key,
						R.string.settings_cell_enabled_default
				) ||
				preferences.getBooleanRes(
						R.string.settings_wifi_location_count_enabled_key,
						R.string.settings_wifi_location_count_enabled_default
				) ||
				preferences.getBooleanRes(
						R.string.settings_wifi_network_enabled_key,
						R.string.settings_wifi_network_enabled_default
				)
	}
}
