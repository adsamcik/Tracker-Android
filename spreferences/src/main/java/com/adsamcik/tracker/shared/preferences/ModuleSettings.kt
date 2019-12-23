package com.adsamcik.tracker.shared.preferences

import androidx.preference.PreferenceScreen

interface ModuleSettings {
	val iconRes: Int

	fun onCreatePreferenceScreen(preferenceScreen: PreferenceScreen)
}
