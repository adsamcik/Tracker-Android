package com.adsamcik.signalcollector.activities

import android.os.Bundle
import com.adsamcik.signalcollector.fragments.FragmentNewSettings
import com.adsamcik.signalcollector.utility.transaction

class SettingsActivity : DetailActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createContentParent(false)
        supportFragmentManager.transaction {
            add(CONTENT_ID, FragmentNewSettings())
        }

        setTitle("Settings")
    }
}
