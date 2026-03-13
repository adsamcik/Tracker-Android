package com.adsamcik.tracker.app.test

import com.adsamcik.tracker.shared.preferences.MutablePreferences
import com.adsamcik.tracker.shared.preferences.Preferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk

/**
 * Test helper for mocking Preferences in Robolectric tests.
 * 
 * Uses two data stores:
 * - [data]: Keyed by resource ID (Int) for synchronous getters (getIntRes, getBooleanRes, etc.)
 * - [stringKeyData]: Keyed by actual string key for suspend fetchers (fetchInt, fetchBoolean, etc.)
 * 
 * When using with components that read via string keys (like PrecisionUpgradeReceiver),
 * register key mappings with [registerKeyMapping] to link resource IDs to their string values.
 */
object FakePreferencesHelper {
    val data = mutableMapOf<Int, Any>()
    val stringKeyData = mutableMapOf<String, Any>()
    private val keyMappings = mutableMapOf<Int, String>()

    /**
     * Register a mapping from resource ID to string key.
     * This enables the mock to properly store values by both resource ID and string key.
     */
    fun registerKeyMapping(resourceId: Int, stringKey: String) {
        keyMappings[resourceId] = stringKey
    }

    fun setup(): Preferences {
        data.clear()
        stringKeyData.clear()
        keyMappings.clear()
        val mockPrefs = mockk<Preferences>(relaxed = true)
        val mockMutablePrefs = mockk<MutablePreferences>(relaxed = true)

        // Mock getters - specify types explicitly for overloaded methods
        every { mockPrefs.getBooleanRes(any<Int>(), any<Int>()) } answers {
            val key = firstArg<Int>()
            data[key] as? Boolean ?: false
        }
        every { mockPrefs.getBooleanRes(any<Int>(), any<Boolean>()) } answers {
            val key = firstArg<Int>()
            val default = secondArg<Boolean>()
            data[key] as? Boolean ?: default
        }

        every { mockPrefs.getStringRes(any<Int>(), any<Int>()) } answers {
            val key = firstArg<Int>()
            data[key] as? String ?: ""
        }

        every { mockPrefs.getIntRes(any<Int>(), any<Int>()) } answers {
            val key = firstArg<Int>()
            val default = secondArg<Int>()
            data[key] as? Int ?: default
        }

        every { mockPrefs.getIntResValue(any<Int>(), any<Int>()) } answers {
            val key = firstArg<Int>()
            val default = secondArg<Int>()
            data[key] as? Int ?: default
        }

        // Mock suspend functions (fetch*) used by PrecisionUpgradeReceiver
        // These use string keys, so look up in stringKeyData
        coEvery { mockPrefs.fetchBoolean(any<String>(), any<Boolean>()) } coAnswers {
            val key = firstArg<String>()
            stringKeyData[key] as? Boolean ?: secondArg<Boolean>()
        }

        coEvery { mockPrefs.fetchInt(any<String>(), any<Int>()) } coAnswers {
            val key = firstArg<String>()
            stringKeyData[key] as? Int ?: secondArg<Int>()
        }

        coEvery { mockPrefs.fetchString(any<String>()) } coAnswers {
            val key = firstArg<String>()
            stringKeyData[key] as? String
        }

        coEvery { mockPrefs.fetchStringRes(any<Int>()) } coAnswers {
            val keyRes = firstArg<Int>()
            data[keyRes] as? String
        }

        // Mock edit
        every { mockPrefs.edit(any()) } answers {
            val action = firstArg<MutablePreferences.() -> Unit>()
            action(mockMutablePrefs)
        }

        // Mock setters on MutablePreferences - store in both data maps
        every { mockMutablePrefs.setBoolean(any<Int>(), any()) } answers {
            val resId = firstArg<Int>()
            val value = secondArg<Boolean>()
            data[resId] = value
            keyMappings[resId]?.let { stringKeyData[it] = value }
        }
        every { mockMutablePrefs.setString(any<Int>(), any()) } answers {
            val resId = firstArg<Int>()
            val value = secondArg<String>()
            data[resId] = value
            keyMappings[resId]?.let { stringKeyData[it] = value }
        }
        every { mockMutablePrefs.setInt(any<Int>(), any()) } answers {
            val resId = firstArg<Int>()
            val value = secondArg<Int>()
            data[resId] = value
            keyMappings[resId]?.let { stringKeyData[it] = value }
        }

        return mockPrefs
    }

    fun tearDown() {
        data.clear()
        stringKeyData.clear()
        keyMappings.clear()
    }
}
