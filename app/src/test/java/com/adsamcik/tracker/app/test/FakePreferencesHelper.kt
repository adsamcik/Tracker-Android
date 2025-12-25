package com.adsamcik.tracker.app.test

import com.adsamcik.tracker.shared.preferences.MutablePreferences
import com.adsamcik.tracker.shared.preferences.Preferences
import io.mockk.every
import io.mockk.mockk
import java.lang.reflect.Field

object FakePreferencesHelper {
    val data = mutableMapOf<Int, Any>()

    fun setup(): Preferences {
        data.clear()
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

        // Mock edit
        every { mockPrefs.edit(any()) } answers {
            val action = firstArg<MutablePreferences.() -> Unit>()
            action(mockMutablePrefs)
        }

        // Mock setters on MutablePreferences
        every { mockMutablePrefs.setBoolean(any<Int>(), any()) } answers {
            data[firstArg<Int>()] = secondArg<Boolean>()
        }
        every { mockMutablePrefs.setString(any<Int>(), any()) } answers {
            data[firstArg<Int>()] = secondArg<String>()
        }
        every { mockMutablePrefs.setInt(any<Int>(), any()) } answers {
            data[firstArg<Int>()] = secondArg<Int>()
        }

        // Inject into static field
        val field = Preferences::class.java.getDeclaredField("preferences")
        field.isAccessible = true
        field.set(null, mockPrefs)

        return mockPrefs
    }

    fun tearDown() {
        val field = Preferences::class.java.getDeclaredField("preferences")
        field.isAccessible = true
        field.set(null, null)
        data.clear()
    }
}
