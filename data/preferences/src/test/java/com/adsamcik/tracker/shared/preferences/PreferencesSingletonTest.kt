package com.adsamcik.tracker.shared.preferences

import android.content.Context
import android.content.res.Resources
import androidx.datastore.preferences.core.emptyPreferences
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PreferencesSingletonTest {

	private lateinit var mockContext: Context

	@BeforeEach
	fun setUp() {
		mockkObject(LegacyPreferenceStore)
		every { LegacyPreferenceStore.snapshot(any()) } returns emptyPreferences()

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
	fun `constructor returns a Preferences instance`() {
		val pref = Preferences(mockContext)
		pref.shouldBeInstanceOf<Preferences>()
	}

	@Test
	fun `constructor returns a new instance on each call`() {
		val first = Preferences(mockContext)
		val second = Preferences(mockContext)
		// Each call produces a fresh lightweight wrapper; callers must not rely on identity
		first shouldNotBe second
	}
}
