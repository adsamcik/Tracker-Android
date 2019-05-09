package com.adsamcik.signalcollector.preference.pages

import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.color.ColorManager
import com.adsamcik.signalcollector.preference.findPreferenceTyped
import com.jaredrummler.android.colorpicker.ColorPreferenceCompat

class StylePage : PreferencePage {
	private lateinit var styleChangeListener: SharedPreferences.OnSharedPreferenceChangeListener

	override fun onEnter(caller: PreferenceFragmentCompat) {
		val resources = caller.resources
		val morningKey = resources.getString(R.string.settings_color_morning_key)
		val morning = caller.findPreferenceTyped<ColorPreferenceCompat>(morningKey)

		val eveningKey = resources.getString(R.string.settings_color_evening_key)
		val evening = caller.findPreferenceTyped<ColorPreferenceCompat>(eveningKey)

		val nightKey = resources.getString(R.string.settings_color_night_key)
		val night = caller.findPreferenceTyped<ColorPreferenceCompat>(nightKey)

		val dayKey = resources.getString(R.string.settings_color_day_key)
		val day = caller.findPreferenceTyped<ColorPreferenceCompat>(dayKey)

		val onStyleChange = Preference.OnPreferenceChangeListener { _, newValue ->
			val newValueInt = (newValue as String).toInt()
			night.isVisible = newValueInt >= 1

			evening.isVisible = newValueInt >= 2
			morning.isVisible = newValueInt >= 2

			true
		}

		val defaultColorKey = resources.getString(R.string.settings_color_default_key)
		val styleKey = resources.getString(R.string.settings_style_mode_key)
		val stylePreference = caller.findPreferenceTyped<ListPreference>(styleKey)
		stylePreference.onPreferenceChangeListener = onStyleChange
		onStyleChange.onPreferenceChange(stylePreference, stylePreference.value)

		caller.findPreferenceTyped<Preference>(defaultColorKey).setOnPreferenceClickListener {
			val sp = it.sharedPreferences
			sp.edit(true) {
				remove(morningKey)
				remove(dayKey)
				remove(eveningKey)
				remove(nightKey)
			}

			val context = it.context

			morning.saveValue(sp.getInt(morningKey, ContextCompat.getColor(context, R.color.settings_color_morning_default)))
			day.saveValue(sp.getInt(dayKey, ContextCompat.getColor(context, R.color.settings_color_day_default)))
			evening.saveValue(sp.getInt(eveningKey, ContextCompat.getColor(context, R.color.settings_color_evening_default)))
			night.saveValue(sp.getInt(nightKey, ContextCompat.getColor(context, R.color.settings_color_night_default)))

			true
		}

		//todo consider using PreferenceObserver
		styleChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
			when (key) {
				styleKey, defaultColorKey -> ColorManager.initializeFromPreferences(caller.requireContext())
				morningKey, dayKey, eveningKey, nightKey -> {
					if (preferences.contains(key)) {
						val stylePrefVal = stylePreference.value.toInt()

						//-1 indexes are meant to crash the application so an issue is found. They should never happen.

						val index = when (key) {
							morningKey -> {
								if (stylePrefVal < 2) return@OnSharedPreferenceChangeListener else 2
							}
							dayKey -> {
								if (stylePrefVal < 2) 0 else 1
							}
							eveningKey -> {
								if (stylePrefVal < 2) return@OnSharedPreferenceChangeListener else 2
							}
							nightKey -> {
								when (stylePrefVal) {
									0 -> return@OnSharedPreferenceChangeListener
									1 -> 1
									2 -> 3
									else -> -1
								}
							}
							else -> -1
						}

						ColorManager.updateColorAt(index, preferences.getInt(key, 0))
					}
				}
			}
		}

		stylePreference.sharedPreferences.registerOnSharedPreferenceChangeListener(styleChangeListener)
	}

	override fun onExit(caller: PreferenceFragmentCompat) {
		val resources = caller.resources
		val styleKey = resources.getString(R.string.settings_style_mode_key)
		val stylePreference = caller.findPreferenceTyped<ListPreference>(styleKey)
		//Even though listener is stored as weak reference, this is safer and there is no reason not to do it
		stylePreference.sharedPreferences.unregisterOnSharedPreferenceChangeListener(styleChangeListener)
	}

}