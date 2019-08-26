package com.adsamcik.tracker.preference.pages

import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.tracker.common.style.StyleManager
import com.adsamcik.tracker.common.style.update.RequiredColorData
import com.adsamcik.tracker.preference.ColorPreference

class StylePage : PreferencePage {

	override fun onEnter(caller: PreferenceFragmentCompat) {

		StyleManager.requiredColors.zip(StyleManager.activeColorList) { a, b ->
			RecyclerColorData(b, a)
		}.forEachIndexed { index, value ->
			caller.preferenceScreen.addPreference(
					ColorPreference(caller.requireContext()).apply {
						setColor(index, value)
					}
			)
		}
	}

	override fun onExit(caller: PreferenceFragmentCompat) = Unit

	data class RecyclerColorData(var color: Int, val required: RequiredColorData)
}

