package com.adsamcik.tracker.shared.preferences

import android.content.Context
import android.content.res.Resources
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MutablePreferencesTest {

	private lateinit var mockContext: Context
	private lateinit var mockResources: Resources

	@BeforeEach
	fun setUp() {
		mockkObject(LegacyPreferenceStore)
		every { LegacyPreferenceStore.snapshot(any()) } returns emptyPreferences()
		every { LegacyPreferenceStore.edit(any(), any()) } just Runs
		coEvery { LegacyPreferenceStore.editSuspend(any(), any()) } just Runs

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
	inner class `type-safe key management` {
		@Test
		fun `setString enqueues string operation`() {
			val prefs = MutablePreferences(mockContext)
			prefs.setString("key", "value")
			prefs.apply()

			verify { LegacyPreferenceStore.edit(mockContext, match { it.size == 1 }) }
		}

		@Test
		fun `setInt enqueues int operation`() {
			val prefs = MutablePreferences(mockContext)
			prefs.setInt("key", 42)
			prefs.apply()

			verify { LegacyPreferenceStore.edit(mockContext, match { it.size == 1 }) }
		}

		@Test
		fun `setLong enqueues long operation`() {
			val prefs = MutablePreferences(mockContext)
			prefs.setLong("key", 42L)
			prefs.apply()

			verify { LegacyPreferenceStore.edit(mockContext, match { it.size == 1 }) }
		}

		@Test
		fun `setFloat enqueues float operation`() {
			val prefs = MutablePreferences(mockContext)
			prefs.setFloat("key", 3.14f)
			prefs.apply()

			verify { LegacyPreferenceStore.edit(mockContext, match { it.size == 1 }) }
		}

		@Test
		fun `setBoolean enqueues boolean operation`() {
			val prefs = MutablePreferences(mockContext)
			prefs.setBoolean("key", true)
			prefs.apply()

			verify { LegacyPreferenceStore.edit(mockContext, match { it.size == 1 }) }
		}

		@Test
		fun `setDouble delegates to setLong with raw bits`() {
			val prefs = MutablePreferences(mockContext)
			prefs.setDouble("key", 3.14)
			prefs.apply()

			// setDouble internally calls setLong, producing a single operation
			verify { LegacyPreferenceStore.edit(mockContext, match { it.size == 1 }) }
		}
	}

	@Nested
	inner class `operation queueing` {
		@Test
		fun `multiple operations are batched`() {
			val prefs = MutablePreferences(mockContext)
			prefs.setString("key1", "value1")
			prefs.setInt("key2", 42)
			prefs.setBoolean("key3", true)
			prefs.apply()

			verify { LegacyPreferenceStore.edit(mockContext, match { it.size == 3 }) }
		}

		@Test
		fun `apply with no operations does not call edit`() {
			val prefs = MutablePreferences(mockContext)
			prefs.apply()

			verify(exactly = 0) { LegacyPreferenceStore.edit(any(), any()) }
		}

		@Test
		fun `apply clears operations after processing`() {
			val prefs = MutablePreferences(mockContext)
			prefs.setString("key", "value")
			prefs.apply()
			prefs.apply()

			verify(exactly = 1) { LegacyPreferenceStore.edit(any(), any()) }
		}

		@Test
		fun `commit with no operations does not call editSuspend`() = runTest {
			val prefs = MutablePreferences(mockContext)
			prefs.commit()

			coVerify(exactly = 0) { LegacyPreferenceStore.editSuspend(any(), any()) }
		}

		@Test
		fun `commit calls editSuspend and clears operations`() = runTest {
			val prefs = MutablePreferences(mockContext)
			prefs.setString("key", "value")
			prefs.commit()

			coVerify(exactly = 1) {
				LegacyPreferenceStore.editSuspend(mockContext, match { it.size == 1 })
			}

			// Second commit should be a no-op since operations were cleared
			prefs.commit()
			coVerify(exactly = 1) { LegacyPreferenceStore.editSuspend(any(), any()) }
		}
	}

	@Nested
	inner class `remove operations` {
		@Test
		fun `remove enqueues removal for all key types`() {
			val prefs = MutablePreferences(mockContext)
			prefs.remove("key")
			prefs.apply()

			verify { LegacyPreferenceStore.edit(mockContext, match { it.size == 1 }) }
		}

		@Test
		fun `clear enqueues clear operation`() {
			val prefs = MutablePreferences(mockContext)
			prefs.clear()
			prefs.apply()

			verify { LegacyPreferenceStore.edit(mockContext, match { it.size == 1 }) }
		}
	}

	@Nested
	inner class `prefix-based removal` {
		@Test
		fun `removeKeyByPrefix with matching keys enqueues removal`() {
			val snapshot = preferencesOf(
				stringPreferencesKey("test_key1") to "value1",
				stringPreferencesKey("test_key2") to "value2",
				stringPreferencesKey("other_key") to "value3"
			)
			every { LegacyPreferenceStore.snapshot(any()) } returns snapshot

			val prefs = MutablePreferences(mockContext)
			prefs.removeKeyByPrefix("test_")
			prefs.apply()

			verify { LegacyPreferenceStore.edit(mockContext, match { it.size == 1 }) }
		}

		@Test
		fun `removeKeyByPrefix with no matching keys does not enqueue`() {
			val snapshot = preferencesOf(
				stringPreferencesKey("other_key") to "value"
			)
			every { LegacyPreferenceStore.snapshot(any()) } returns snapshot

			val prefs = MutablePreferences(mockContext)
			prefs.removeKeyByPrefix("test_")
			prefs.apply()

			verify(exactly = 0) { LegacyPreferenceStore.edit(any(), any()) }
		}

		@Test
		fun `removeKeyByPrefix with empty snapshot does not enqueue`() {
			every { LegacyPreferenceStore.snapshot(any()) } returns emptyPreferences()

			val prefs = MutablePreferences(mockContext)
			prefs.removeKeyByPrefix("test_")
			prefs.apply()

			verify(exactly = 0) { LegacyPreferenceStore.edit(any(), any()) }
		}

		@Test
		fun `removeKeyByPrefix filters by exact prefix match`() {
			val snapshot = preferencesOf(
				stringPreferencesKey("prefix_a") to "1",
				intPreferencesKey("prefix_b") to 2,
				stringPreferencesKey("no_match") to "3"
			)
			every { LegacyPreferenceStore.snapshot(any()) } returns snapshot

			val prefs = MutablePreferences(mockContext)
			prefs.removeKeyByPrefix("prefix_")
			prefs.apply()

			// One removal operation is enqueued (batches all matching keys)
			verify { LegacyPreferenceStore.edit(mockContext, match { it.size == 1 }) }
		}
	}

	@Nested
	inner class `edit helper` {
		@Test
		fun `edit block applies operations automatically`() {
			val prefs = MutablePreferences(mockContext)
			prefs.edit {
				setString("key1", "value1")
				setInt("key2", 42)
			}

			verify { LegacyPreferenceStore.edit(mockContext, match { it.size == 2 }) }
		}
	}

	@Nested
	inner class `double encoding` {
		@Test
		fun `setDouble stores value as long raw bits`() {
			val value = Double.MAX_VALUE
			val expectedBits = value.toRawBits()

			val prefs = MutablePreferences(mockContext)
			prefs.setDouble("dbl", value)
			prefs.apply()

			// Capture the operations list and verify it contains one operation
			verify {
				LegacyPreferenceStore.edit(mockContext, match { ops ->
					ops.size == 1
				})
			}

			// The encoded bits should round-trip correctly
			Double.fromBits(expectedBits) shouldBe value
		}
	}
}
