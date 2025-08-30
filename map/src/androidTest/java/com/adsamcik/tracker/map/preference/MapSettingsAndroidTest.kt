package com.adsamcik.tracker.map.preference

import androidx.preference.PreferenceScreen
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import android.content.Context

class MapSettingsAndroidTest {

    @Test
    fun onCreatePreferenceScreen_adds_expected_preferences() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val manager = androidx.preference.PreferenceManager(ctx)
        val screen: PreferenceScreen = manager.createPreferenceScreen(ctx)

        MapSettings().onCreatePreferenceScreen(screen)

        // Expect 3 preferences added by MapSettings
        assertEquals(3, screen.preferenceCount)
    }
}
