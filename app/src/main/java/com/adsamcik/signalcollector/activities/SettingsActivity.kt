package com.adsamcik.signalcollector.activities

import android.os.Bundle
import com.adsamcik.signalcollector.fragments.FragmentNewSettings
import com.adsamcik.signalcollector.utility.transaction


class SettingsActivity : DetailActivity() {
    val fragment: FragmentNewSettings = supportFragmentManager.findFragmentByTag(FragmentNewSettings.TAG) as FragmentNewSettings?
            ?: FragmentNewSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createContentParent(false)

        if (savedInstanceState == null) {
            // Create the fragment only when the activity is created for the first time.
            // ie. not after orientation changes
            /*var fragment = supportFragmentManager.findFragmentByTag(FragmentNewSettings.TAG)
            if (fragment == null) {
                fragment = FragmentNewSettings()
            }*/

            supportFragmentManager.transaction {
                replace(CONTENT_ID, fragment, FragmentNewSettings.TAG)
            }
        }

        setTitle("Settings")
    }

    override fun onBackPressed() {
        if (!fragment.pop())
            super.onBackPressed()
    }
}
