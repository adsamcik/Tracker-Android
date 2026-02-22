package com.adsamcik.tracker.shared.preferences

import android.content.Context
import android.content.res.Resources
import androidx.datastore.preferences.core.emptyPreferences
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.CopyOnWriteArrayList

class PreferencesSingletonTest {

	private lateinit var mockContext: Context

	@BeforeEach
	fun setUp() {
		mockkObject(LegacyPreferenceStore)
		every { LegacyPreferenceStore.snapshot(any()) } returns emptyPreferences()
		every { LegacyPreferenceStore.edit(any(), any()) } just Runs

		val mockResources = mockk<Resources>(relaxed = true)
		mockContext = mockk {
			every { applicationContext } returns this@mockk
			every { resources } returns mockResources
		}

		// Reset the singleton between tests via reflection
		resetSingleton()
	}

	@AfterEach
	fun tearDown() {
		unmockkObject(LegacyPreferenceStore)
		resetSingleton()
	}

	private fun resetSingleton() {
		val field = Preferences::class.java.getDeclaredField("preferences")
		field.isAccessible = true
		field.set(null, null)
	}

	@Test
	fun `getPref returns same instance across concurrent threads`() {
		val threadCount = 16
		val barrier = CyclicBarrier(threadCount)
		val instances = CopyOnWriteArrayList<Preferences>()

		val threads = (1..threadCount).map {
			Thread {
				barrier.await()
				val pref = Preferences.getPref(mockContext)
				instances.add(pref)
			}
		}

		threads.forEach { it.start() }
		threads.forEach { it.join() }

		instances.size shouldBe threadCount
		val expected = instances.first()
		instances.forEach { it shouldBe expected }
	}

	@Test
	fun `getPref returns same instance on sequential calls`() {
		val first = Preferences.getPref(mockContext)
		val second = Preferences.getPref(mockContext)
		first shouldBe second
	}
}
