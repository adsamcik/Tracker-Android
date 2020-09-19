package com.adsamcik.tracker.map.preference

import androidx.preference.PreferenceScreen
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.preference.sliders.DurationValueSliderPreference
import com.adsamcik.tracker.preference.sliders.FloatValueSliderPreference
import com.adsamcik.tracker.preference.sliders.IntValueSliderPreference
import com.adsamcik.tracker.shared.preferences.ModuleSettings

/**
 * Module settings for map
 */
@Suppress("unused")
class MapSettings : ModuleSettings {
	override val iconRes: Int = com.adsamcik.tracker.shared.base.R.drawable.ic_outline_map_24dp

	override fun onCreatePreferenceScreen(preferenceScreen: PreferenceScreen) {
		val context = preferenceScreen.context
		val resources = context.resources


		FloatValueSliderPreference(context).apply {
			key = resources.getString(R.string.settings_map_quality_key)
			setTitle(R.string.settings_map_quality_title)
			setSummary(R.string.settings_map_quality_summary)
			setDefaultValue(resources.getString(R.string.settings_map_quality_default).toFloat())
			setIcon(R.drawable.ic_outline_hd)

			setValuesResource(R.array.settings_map_quality_values)
			setStringFormat(resources.getString(R.string.settings_map_quality_format))
		}.also { preferenceScreen.addPreference(it) }

		IntValueSliderPreference(context).apply {
			key = resources.getString(R.string.settings_map_max_heat_key)
			setTitle(R.string.settings_map_max_heat_title)
			setSummary(R.string.settings_map_max_heat_summary)
			setDefaultValue(resources.getString(R.string.settings_map_max_heat_default).toInt())
			setIcon(R.drawable.ic_oil_temperature)

			valuesResource = R.array.settings_map_max_heat_values
			setStringFormat("%d")
		}.also { preferenceScreen.addPreference(it) }


		DurationValueSliderPreference(context).apply {
			key = resources.getString(R.string.settings_map_visit_threshold_key)
			setTitle(R.string.settings_map_visit_threshold_title)
			setSummary(R.string.settings_map_visit_threshold_summary)
			setDefaultValue(
					resources
							.getString(R.string.settings_map_visit_threshold_default)
							.toInt()
			)
			setIcon(R.drawable.ic_clock_outline)

			valuesResource = R.array.settings_map_visit_threshold_values
		}.also { preferenceScreen.addPreference(it) }
	}
}
