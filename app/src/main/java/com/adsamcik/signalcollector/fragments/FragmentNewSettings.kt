package com.adsamcik.signalcollector.fragments

import android.os.Bundle
import android.support.v7.preference.PreferenceFragmentCompat
import com.adsamcik.signalcollector.R


class FragmentNewSettings : PreferenceFragmentCompat() {
    override fun onCreatePreferences(bundle: Bundle?, s: String?) {
        // Load the Preferences from the XML file
        addPreferencesFromResource(R.xml.app_preferences)
    }
}