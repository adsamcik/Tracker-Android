package com.adsamcik.signalcollector.preference.fragment

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.preference.observer.PreferenceObserver


class FragmentSettings : PreferenceFragmentCompat() {
	override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
		// Load the Preferences from the XML file
		setPreferencesFromResource(R.xml.app_preferences, rootKey)

		PreferenceObserver.initialize(preferenceManager.sharedPreferences)
	}

	companion object {
		const val TAG: String = "FragmentSettings"
	}
}