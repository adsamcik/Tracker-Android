package com.adsamcik.signalcollector.fragments

import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.preference.PreferenceFragmentCompat
import android.support.v7.preference.PreferenceScreen
import com.adsamcik.signalcollector.R


class FragmentNewSettings : PreferenceFragmentCompat(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    private val backstack = ArrayList<PreferenceScreen>()

    override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean {
        caller.preferenceScreen = pref
        val index = backstack.indexOf(pref)
        if (index >= 0) {
            for (i in (backstack.size - 1) downTo (index + 1))
                backstack.removeAt(i)
        } else
            backstack.add(pref)
        return true
    }

    fun pop(): Boolean {
        return if (backstack.size <= 1)
            false
        else
            onPreferenceStartScreen(this, backstack[backstack.size - 2])
    }

    override fun getCallbackFragment(): Fragment {
        return this
    }

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        // Load the Preferences from the XML file
        setPreferencesFromResource(R.xml.app_preferences, rootKey)
        backstack.add(preferenceScreen)
    }

    companion object {
        const val TAG = "FragmentNewSettings"
    }
}