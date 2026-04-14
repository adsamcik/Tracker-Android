package com.adsamcik.tracker.shared.preferences

import android.content.Context
import android.content.res.Resources
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DeveloperPreferencesTest {

	private lateinit var mockContext: Context
	private lateinit var mockResources: Resources

	@BeforeEach
	fun setUp() {
		mockkObject(LegacyPreferenceStore)
		mockResources = mockk(relaxed = true)
		mockContext = mockk {
			every { applicationContext } returns this@mockk
			every { resources } returns mockResources
		}
	}

	@AfterEach
	fun tearDown() {
		unmockkObject(LegacyPreferenceStore)
	}

	@Nested
	inner class `isDeveloperModeEnabled` {
		@Test
		fun `returns false when not set`() {
			every { LegacyPreferenceStore.snapshot(any()) } returns emptyPreferences()
			DeveloperPreferences.isDeveloperModeEnabled(mockContext) shouldBe false
		}

		@Test
		fun `returns true when developer mode is enabled`() {
			val prefs = preferencesOf(
				booleanPreferencesKey("developer_mode_enabled") to true
			)
			every { LegacyPreferenceStore.snapshot(any()) } returns prefs
			DeveloperPreferences.isDeveloperModeEnabled(mockContext) shouldBe true
		}

		@Test
		fun `returns false when developer mode is explicitly disabled`() {
			val prefs = preferencesOf(
				booleanPreferencesKey("developer_mode_enabled") to false
			)
			every { LegacyPreferenceStore.snapshot(any()) } returns prefs
			DeveloperPreferences.isDeveloperModeEnabled(mockContext) shouldBe false
		}
	}

	@Nested
	inner class `observeDeveloperMode` {
		@Test
		fun `returns a flow from LegacyPreferenceStore`() {
			val flow = flowOf(true)
			every { LegacyPreferenceStore.booleanFlow(any(), "developer_mode_enabled", false) } returns flow
			val result = DeveloperPreferences.observeDeveloperMode(mockContext)
			result shouldBe flow
		}
	}

	@Nested
	inner class `setDeveloperMode` {
		@Test
		fun `delegates to edit on Preferences`() {
			every { LegacyPreferenceStore.snapshot(any()) } returns emptyPreferences()
			every { LegacyPreferenceStore.edit(any(), any()) } just Runs

			DeveloperPreferences.setDeveloperMode(mockContext, true)

			verify { LegacyPreferenceStore.edit(any(), any()) }
		}
	}

	@Nested
	inner class `object identity` {
		@Test
		fun `DeveloperPreferences is a singleton`() {
			val ref1 = DeveloperPreferences
			val ref2 = DeveloperPreferences
			(ref1 === ref2) shouldBe true
		}
	}
}
