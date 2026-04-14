package com.adsamcik.tracker.shared.preferences.store

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LegacyPreferenceStoreTest {

	@AfterEach
	fun tearDown() {
		LegacyPreferenceStore.resetForTests()
	}

	@Nested
	inner class `resetForTests` {
		@Test
		fun `resetForTests clears internal state without throwing`() {
			// resetForTests should be idempotent and safe to call even when no StateFlow exists
			LegacyPreferenceStore.resetForTests()
			LegacyPreferenceStore.resetForTests()
			// No exception means success
		}
	}

	@Nested
	inner class `edit with empty operations` {
		@Test
		fun `edit with empty list is a no-op`() {
			val mockContext = io.mockk.mockk<android.content.Context> {
				io.mockk.every { applicationContext } returns this@mockk
			}
			// Should return immediately without crashing
			LegacyPreferenceStore.edit(mockContext, emptyList())
		}
	}

	@Nested
	inner class `editSuspend with empty operations` {
		@Test
		fun `editSuspend with empty list is a no-op`() = kotlinx.coroutines.test.runTest {
			val mockContext = io.mockk.mockk<android.content.Context> {
				io.mockk.every { applicationContext } returns this@mockk
			}
			// Should return immediately without crashing
			LegacyPreferenceStore.editSuspend(mockContext, emptyList())
		}
	}

	@Nested
	inner class `object identity` {
		@Test
		fun `LegacyPreferenceStore is a singleton object`() {
			val ref1 = LegacyPreferenceStore
			val ref2 = LegacyPreferenceStore
			(ref1 === ref2) shouldBe true
		}
	}
}
