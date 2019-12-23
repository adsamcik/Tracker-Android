package com.adsamcik.tracker.common.preferences

import androidx.preference.PreferenceScreen

interface ModuleSettings {
	val iconRes: Int

	fun onCreatePreferenceScreen(preferenceScreen: PreferenceScreen)
}
