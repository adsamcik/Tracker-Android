package com.adsamcik.tracker.settings

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.adsamcik.tracker.R
import com.adsamcik.tracker.preference.activity.SettingsActivity
import com.adsamcik.tracker.shared.preferences.Preferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class AutoCleanupPreferenceInstrumentedTest {

    @Test
    fun toggleAutoCleanup_persistsAcrossRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Preferences.getPref(context)

        // Ensure OFF by default
        prefs.edit { setBoolean(R.string.settings_auto_cleanup_old_data_key, false) }
        assertFalse(prefs.getBooleanRes(R.string.settings_auto_cleanup_old_data_key, R.string.settings_auto_cleanup_old_data_default))

        // Launch settings and navigate to Data page
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withText(context.getString(R.string.settings_data_title))).check(matches(isDisplayed())).perform(click())
            // Toggle the auto-cleanup switch by clicking its title row
            onView(withText(context.getString(R.string.settings_auto_cleanup_old_data_title))).check(matches(isDisplayed())).perform(click())
        }

        // Simulate process backgrounding
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()

        // Preference should persist as ON
        val prefs2 = Preferences.getPref(context)
        assertTrue(prefs2.getBooleanRes(R.string.settings_auto_cleanup_old_data_key, R.string.settings_auto_cleanup_old_data_default))
    }
}
