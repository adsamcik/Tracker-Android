package com.adsamcik.signalcollector.preference.fragment

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.signalcollector.R


class FragmentSettings : PreferenceFragmentCompat() {
	override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
		// Load the Preferences from the XML file
		setPreferencesFromResource(R.xml.app_preferences, rootKey)
	}

	companion object {
		const val TAG: String = "FragmentSettings"
	}
}