package com.adsamcik.signalcollector.preference.fragment

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.preference.listener.PreferenceListener


class FragmentSettings : PreferenceFragmentCompat() {
	override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
		// Load the Preferences from the XML file
		setPreferencesFromResource(R.xml.app_preferences, rootKey)

		PreferenceListener.initialize(preferenceManager.sharedPreferences)
	}

	companion object {
		const val TAG: String = "FragmentSettings"
	}
}