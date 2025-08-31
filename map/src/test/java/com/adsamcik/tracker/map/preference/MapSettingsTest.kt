package com.adsamcik.tracker.map.preference

import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Ignore
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

@Ignore("Moved to androidTest; relies on AndroidX Preference")
class MapSettingsTest {

    @Test
    fun onCreatePreferenceScreen_adds_expected_preferences() {
        // Minimal fake screen that records added preferences
        val added = mutableListOf<Preference>()
        val screen = mock<PreferenceScreen> {
            on { addPreference(any()) } doAnswer {
                added.add(it.getArgument(0))
                true
            }
        }

        MapSettings().onCreatePreferenceScreen(screen)

        assertEquals(3, added.size)
    }
}
