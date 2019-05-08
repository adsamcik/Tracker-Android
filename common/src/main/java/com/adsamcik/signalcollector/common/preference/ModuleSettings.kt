package com.adsamcik.signalcollector.common.preference

import androidx.preference.PreferenceScreen


interface ModuleSettings {
	val iconRes: Int

	fun onCreatePreferenceScreen(preferenceScreen: PreferenceScreen)
}