package com.adsamcik.tracker.shared.preferences

import android.content.Context

/**
 * Utility object that provides methods for common preference operations.
 */
object PreferencesAssist {
	/**
	 * Checks if there is anything to track (async version).
	 *
	 * @param context context
	 * @return true if at least one of location, cell and wifi tracking is enabled
	 */
	suspend fun hasAnythingToTrackAsync(context: Context): Boolean =
		hasAnythingToTrackAsync(Preferences(context))

	/**
	 * Overload for direct Preferences injection (test-friendly; avoids static factory).
	 */
	suspend fun hasAnythingToTrackAsync(preferences: Preferences): Boolean {
		return preferences.fetchBooleanRes(
				R.string.settings_location_enabled_key,
				R.string.settings_location_enabled_default
		) ||
				preferences.fetchBooleanRes(
						R.string.settings_cell_enabled_key,
						R.string.settings_cell_enabled_default
				) ||
				preferences.fetchBooleanRes(
						R.string.settings_wifi_location_count_enabled_key,
						R.string.settings_wifi_location_count_enabled_default
				) ||
				preferences.fetchBooleanRes(
						R.string.settings_wifi_network_enabled_key,
						R.string.settings_wifi_network_enabled_default
				)
	}

}
