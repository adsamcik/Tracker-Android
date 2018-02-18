package com.adsamcik.signalcollector.activities

import android.os.Bundle
import android.support.annotation.StringRes
import android.support.v7.preference.PreferenceFragmentCompat
import android.support.v7.preference.PreferenceScreen
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.fragments.FragmentNewSettings
import com.adsamcik.signalcollector.utility.startActivity
import com.adsamcik.signalcollector.utility.transaction


class SettingsActivity : DetailActivity(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    val fragment: FragmentNewSettings = supportFragmentManager.findFragmentByTag(FragmentNewSettings.TAG) as FragmentNewSettings?
            ?: FragmentNewSettings()

    private val backstack = ArrayList<PreferenceScreen>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createContentParent(false)

        if (savedInstanceState == null) {
            supportFragmentManager.transaction {
                replace(CONTENT_ID, fragment, FragmentNewSettings.TAG)
                runOnCommit { initializeRoot() }
            }
        }


        title = "Settings"
    }

    private fun initializeRoot() {
        addOnClickListener(R.string.settings_export_key) {
            startActivity<FileSharingActivity> {}
        }

        addOnClickListener(R.string.settings_licenses_key) {
            startActivity<LicenseActivity> { }
        }
    }

    private fun addOnClickListener(@StringRes key: Int, listener: () -> Unit) {
        fragment.findPreference(getString(key)).setOnPreferenceClickListener {
            listener.invoke()
            false
        }
    }

    override fun onBackPressed() {
        if (!pop())
            super.onBackPressed()
    }

    private fun pop(): Boolean {
        return when {
            backstack.isEmpty() -> false
            backstack.size == 1 -> {
                fragment.setPreferencesFromResource(R.xml.app_preferences, null)
                backstack.clear()
                title = "Settings"
                initializeRoot()
                true
            }
            else -> onPreferenceStartScreen(fragment, backstack[backstack.size - 2])
        }
    }

    fun initializeStartScreen(key: String) {
        when (key) {

        }
    }


    override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean {
        caller.preferenceScreen = pref
        val index = backstack.indexOf(pref)
        if (index >= 0) {
            for (i in (backstack.size - 1) downTo (index + 1))
                backstack.removeAt(i)
        } else
            backstack.add(pref)

        title = pref.title


        return true
    }
}
