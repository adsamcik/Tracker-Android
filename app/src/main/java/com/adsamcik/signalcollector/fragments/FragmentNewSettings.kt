package com.adsamcik.signalcollector.fragments

import android.os.Bundle
import android.support.annotation.StringRes
import android.support.v7.preference.PreferenceFragmentCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activities.FileSharingActivity
import com.adsamcik.signalcollector.activities.LicenseActivity
import com.adsamcik.signalcollector.utility.startActivity


class FragmentNewSettings : PreferenceFragmentCompat() {
    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        // Load the Preferences from the XML file
        setPreferencesFromResource(R.xml.app_preferences, rootKey)
    }

    companion object {
        const val TAG = "FragmentNewSettings"
    }
}