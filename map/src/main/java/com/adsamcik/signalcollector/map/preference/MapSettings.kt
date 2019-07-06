package com.adsamcik.signalcollector.map.preference

import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.adsamcik.signalcollector.common.dialog.ConfirmDialog
import com.adsamcik.signalcollector.common.preference.ModuleSettings
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.map.R
import com.adsamcik.signalcollector.preference.FloatValueSliderPreference
import com.adsamcik.signalcollector.preference.IntValueSliderPreference
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Suppress("unused")
class MapSettings : ModuleSettings {
	override val iconRes: Int = com.adsamcik.signalcollector.common.R.drawable.ic_outline_map_24dp

	override fun onCreatePreferenceScreen(preferenceScreen: PreferenceScreen) {
		val context = preferenceScreen.context
		val resources = context.resources


		FloatValueSliderPreference(context).apply {
			key = resources.getString(R.string.settings_map_quality_key)
			setTitle(R.string.settings_map_quality_title)
			setSummary(R.string.settings_map_quality_summary)
			setDefaultValue(resources.getString(R.string.settings_map_quality_default).toFloat())
			setIcon(com.adsamcik.signalcollector.common.R.drawable.ic_outline_hd_24dp)

			setValuesResource(R.array.settings_map_quality_values)
			setStringFormat("%.2fx")
		}.also { preferenceScreen.addPreference(it) }

		ListPreference(context).apply {
			key = resources.getString(R.string.settings_map_default_layer_key)
			setTitle(R.string.settings_map_default_layer_title)
			setEntries(R.array.settings_map_default_layer_names)
			setEntryValues(R.array.settings_map_default_layer_values)
			summary = "%s"

			setDefaultValue(resources.getString(R.string.settings_map_default_layer_default))
			setIcon(com.adsamcik.signalcollector.common.R.drawable.ic_outline_layers_24dp)
		}.also { preferenceScreen.addPreference(it) }

		IntValueSliderPreference(context).apply {
			key = resources.getString(R.string.settings_map_max_heat_key)
			setTitle(R.string.settings_map_max_heat_title)
			setSummary(R.string.settings_map_max_heat_summary)
			setDefaultValue(resources.getString(R.string.settings_map_max_heat_default).toInt())
			setIcon(R.drawable.ic_oil_temperature)

			setValuesResource(R.array.settings_map_max_heat_values)
			setStringFormat("%d")
		}.also { preferenceScreen.addPreference(it) }
	}
}