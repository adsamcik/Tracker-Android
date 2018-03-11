package com.adsamcik.signalcollector.fragments

import android.os.Bundle
import android.support.v7.preference.PreferenceFragmentCompat
import com.adsamcik.signalcollector.R


class FragmentNewSettings : PreferenceFragmentCompat() {
    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        // Load the Preferences from the XML file
        setPreferencesFromResource(R.xml.app_preferences, rootKey)
    }

    companion object {
        const val TAG = "FragmentNewSettings"
    }
}