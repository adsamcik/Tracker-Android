package com.adsamcik.tracker.shared.preferences.store

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreferenceFlushLifecycleObserverTest {

	private lateinit var mockContext: Context
	private lateinit var testScope: TestScope
	private lateinit var observer: PreferenceFlushLifecycleObserver

	@BeforeEach
	fun setUp() {
		mockkObject(LegacyPreferenceStore)
		coEvery { LegacyPreferenceStore.flush(any()) } just Runs

		mockContext = mockk {
			every { applicationContext } returns this@mockk
		}

		testScope = TestScope(UnconfinedTestDispatcher())
		observer = PreferenceFlushLifecycleObserver(mockContext, testScope)
	}

	@AfterEach
	fun tearDown() {
		unmockkObject(LegacyPreferenceStore)
	}

	@Nested
	inner class `onStop behavior` {
		@Test
		fun `onStop triggers flush on LegacyPreferenceStore`() = runTest {
			val owner = mockk<LifecycleOwner>()
			observer.onStop(owner)

			coVerify(exactly = 1) { LegacyPreferenceStore.flush(mockContext) }
		}

		@Test
		fun `onStop can be called multiple times`() = runTest {
			val owner = mockk<LifecycleOwner>()
			observer.onStop(owner)
			observer.onStop(owner)

			coVerify(exactly = 2) { LegacyPreferenceStore.flush(mockContext) }
		}
	}
}
