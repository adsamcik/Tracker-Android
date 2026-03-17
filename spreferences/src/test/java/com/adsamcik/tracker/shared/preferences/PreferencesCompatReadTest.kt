package com.adsamcik.tracker.shared.preferences

import android.content.Context
import android.content.res.Resources
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PreferencesCompatReadTest {

    private lateinit var mockContext: Context

    @BeforeEach
    fun setUp() {
        mockkObject(LegacyPreferenceStore)

        val mockResources = mockk<Resources>(relaxed = true)
        mockContext = mockk {
            every { applicationContext } returns this@mockk
            every { resources } returns mockResources
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(LegacyPreferenceStore)
    }

    @Test
    fun `getIntStringKey reads int-backed value`() {
        every { LegacyPreferenceStore.snapshot(any()) } returns preferencesOf(
            intPreferencesKey("goal") to 42,
        )

        val result = Preferences(mockContext).getIntStringKey("goal", 7)

        result shouldBe 42
    }

    @Test
    fun `getIntStringKey reads string-backed value`() {
        every { LegacyPreferenceStore.snapshot(any()) } returns preferencesOf(
            stringPreferencesKey("goal") to "42",
        )

        val result = Preferences(mockContext).getIntStringKey("goal", 7)

        result shouldBe 42
    }

    @Test
    fun `getFloatStringKey reads float-backed value`() {
        every { LegacyPreferenceStore.snapshot(any()) } returns preferencesOf(
            floatPreferencesKey("portion") to 0.4f,
        )

        val result = Preferences(mockContext).getFloatStringKey("portion", 0.2f)

        result.shouldBeExactly(0.4f)
    }

    @Test
    fun `getFloatStringKey reads string-backed value`() {
        every { LegacyPreferenceStore.snapshot(any()) } returns preferencesOf(
            stringPreferencesKey("portion") to "0.4",
        )

        val result = Preferences(mockContext).getFloatStringKey("portion", 0.2f)

        result.shouldBeExactly(0.4f)
    }

    @Test
    fun `compat helpers fall back to defaults for invalid or missing values`() {
        every { LegacyPreferenceStore.snapshot(any()) } returns preferencesOf(
            stringPreferencesKey("goal") to "not-a-number",
            stringPreferencesKey("portion") to "still-not-a-number",
        )

        val prefs = Preferences(mockContext)

        prefs.getIntStringKey("goal", 7) shouldBe 7
        prefs.getFloatStringKey("portion", 0.2f).shouldBeExactly(0.2f)

        every { LegacyPreferenceStore.snapshot(any()) } returns emptyPreferences()

        prefs.getIntStringKey("missing-goal", 9) shouldBe 9
        prefs.getFloatStringKey("missing-portion", 0.6f).shouldBeExactly(0.6f)
    }
}
