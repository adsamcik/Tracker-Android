package com.adsamcik.tracker.statistics.preference

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.adsamcik.tracker.shared.preferences.ModuleSettings

/**
 * Statistics module settings
 */
@Suppress("unused")
class StatisticsSettings : ModuleSettings {
	override val iconRes: Int = com.adsamcik.tracker.shared.base.R.drawable.ic_pie_chart_black_24dp

	override fun onCreatePreferenceScreen(preferenceScreen: PreferenceScreen) {
		val context = preferenceScreen.context

		val autoUnitSwitchPreference = SwitchPreferenceCompat(context).apply {
			val default = context.resources.getString(com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_default)
				.toBoolean()
			setDefaultValue(default)
			key = context.getString(com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_key)
			setTitle(com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_title)
			setSummary(com.adsamcik.tracker.shared.preferences.R.string.settings_statistics_auto_unit_switch_summary)
			setIcon(com.adsamcik.tracker.shared.base.R.drawable.ic_ruler)
		}

		preferenceScreen.addPreference(autoUnitSwitchPreference)
	}
}
