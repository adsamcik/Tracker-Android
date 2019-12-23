package com.adsamcik.tracker.preference.fragment

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.tracker.R
import com.adsamcik.androidcomponents.common_preferences.observer.PreferenceObserver


class FragmentSettings : PreferenceFragmentCompat() {
	override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
		// Load the Preferences from the XML file
		setPreferencesFromResource(R.xml.app_preferences, rootKey)

		com.adsamcik.androidcomponents.common_preferences.observer.PreferenceObserver.initialize(preferenceManager.sharedPreferences)
	}

	companion object {
		const val TAG: String = "FragmentSettings"
	}
}
