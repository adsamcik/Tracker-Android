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
		return preferences.fetchBoolean(
				PreferenceKeys.LOCATION_ENABLED,
				PreferenceKeys.LOCATION_ENABLED_DEFAULT
		) ||
				preferences.fetchBoolean(
						PreferenceKeys.CELL_ENABLED,
						PreferenceKeys.CELL_ENABLED_DEFAULT
				) ||
				preferences.fetchBoolean(
						PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED,
						PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED_DEFAULT
				) ||
				preferences.fetchBoolean(
						PreferenceKeys.WIFI_NETWORK_ENABLED,
						PreferenceKeys.WIFI_NETWORK_ENABLED_DEFAULT
				)
	}

}
