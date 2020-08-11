package com.adsamcik.tracker.preference.pages

import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.utils.style.ActiveColorData
import com.adsamcik.tracker.shared.utils.style.StyleManager
import com.adsamcik.tracker.shared.utils.style.update.data.DefaultColorData
import com.adsamcik.tracker.preference.ColorPreference
import com.adsamcik.tracker.preference.findPreference
import com.adsamcik.tracker.preference.findPreferenceTyped

/**
 * Preference page for style.
 */
class StylePage : PreferencePage {

	private val colorPreferenceList = mutableListOf<ColorPreference>()

	private lateinit var parent: PreferenceGroup

	override fun onEnter(caller: PreferenceFragmentCompat) {
		val context = caller.requireContext()

		val enabledUpdateInfoList = StyleManager.enabledUpdateInfo

		this.parent = caller.findPreferenceTyped(R.string.settings_style_color_category_key)

		caller.findPreferenceTyped<ListPreference>(R.string.settings_style_mode_key).apply {
			entries = enabledUpdateInfoList.map { context.getText(it.nameRes) }.toTypedArray()
			entryValues = enabledUpdateInfoList.map { it.id }.toTypedArray()
			val selectedIndex = enabledUpdateInfoList.indexOf(StyleManager.activeUpdateInfo)
			setValueIndex(selectedIndex)

			setOnPreferenceChangeListener { preference, newValue ->
				val id = newValue.toString()
				val newMode = enabledUpdateInfoList.first { it.id == id }
				val currentMode = StyleManager.activeUpdateInfo

				if (newMode != currentMode) {
					clearColorPreferences()
					StyleManager.setMode(preference.context, newMode)
					updateColorPreferences(StyleManager.activeColorList)
				}

				//Always return true because current mode could have been default
				true
			}
		}

		updateColorPreferences(StyleManager.activeColorList)


		caller.findPreference(R.string.settings_color_default_key).setOnPreferenceClickListener {
			clearColorPreferences()
			false
		}
	}

	private fun clearColorPreferences() {
		colorPreferenceList.forEach { it.restoreDefault() }
	}

	private fun updateColorPreferences(colorList: List<ActiveColorData>) {
		if (colorPreferenceList.size > colorList.size) {
			(colorPreferenceList.size - 1 downTo colorList.size - 1).forEach { index ->
				removeColorPreference(index)
			}
		}

		colorPreferenceList.forEachIndexed { index, preference ->
			preference.setColor(index, colorList[index])
		}

		if (colorPreferenceList.size < colorList.size) {
			(colorPreferenceList.size until colorList.size).forEach { index ->
				addColorPreference(parent, index, colorList[index])
			}
		}


	}

	private fun removeColorPreference(index: Int) {
		colorPreferenceList.removeAt(index).also {
			requireNotNull(it.parent).removePreference(it)
		}
	}

	private fun addColorPreference(
			group: PreferenceGroup,
			index: Int,
			colorData: ActiveColorData
	) {
		ColorPreference(group.context).apply {
			setColor(index, colorData)
		}.also { preference ->
			group.addPreference(preference)
			colorPreferenceList.add(preference)
		}

	}

	override fun onExit(caller: PreferenceFragmentCompat) = Unit

	data class RecyclerColorData(var color: Int, val default: DefaultColorData) {
		constructor(activeColorData: ActiveColorData) : this(
				activeColorData.active,
				activeColorData.default
		)
	}
}

