package com.adsamcik.tracker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.shared.base.extension.appVersion
import com.adsamcik.tracker.shared.preferences.MutablePreferences
import com.adsamcik.tracker.shared.preferences.Preferences
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OnAppUpdateReceiverTest {

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	@Nested
	inner class NonUpdateIntent {

		@Test
		fun `ignores intents that are not package replaced`() {
			val receiver = spyk(OnAppUpdateReceiver())
			val context = mockk<Context>(relaxed = true)
			val intent = mockk<Intent> {
				every { action } returns "android.intent.action.PACKAGE_ADDED"
			}

			receiver.onReceive(context, intent)

			verify(exactly = 0) { receiver.goAsync() }
		}

		@Test
		fun `ignores intents with null action`() {
			val receiver = spyk(OnAppUpdateReceiver())
			val context = mockk<Context>(relaxed = true)
			val intent = mockk<Intent> {
				every { action } returns null
			}

			receiver.onReceive(context, intent)

			verify(exactly = 0) { receiver.goAsync() }
		}
	}

	@Nested
	inner class AppUpdate {

		private val mockMutable = mockk<MutablePreferences>(relaxed = true)
		private val mockPrefs = mockk<Preferences>(relaxed = true)
		private val pendingResult = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
		private val context = mockk<Context>(relaxed = true)
		private val receiver = spyk(OnAppUpdateReceiver())

		@BeforeEach
		fun setUp() {
			every { receiver.goAsync() } returns pendingResult
			every { context.getString(any<Int>()) } returns "key_last_app_version"

			mockkObject(Preferences)
			every { Preferences.getPref(any()) } returns mockPrefs
			every { mockPrefs.edit(any()) } answers {
				firstArg<MutablePreferences.() -> Unit>().invoke(mockMutable)
			}

			mockkStatic("com.adsamcik.tracker.shared.base.extension.ContextExtensionsKt")
			every { any<Context>().appVersion() } returns 400L
		}

		private fun updateIntent(): Intent = mockk {
			every { action } returns "android.intent.action.MY_PACKAGE_REPLACED"
		}

		@Test
		fun `removes goal preferences when upgrading from pre-359`() {
			coEvery { mockPrefs.fetchLong(any(), any()) } returns 100L

			receiver.onReceive(context, updateIntent())

			verify(timeout = 2000) { mockMutable.remove("goalWeekReached") }
			verify(timeout = 2000) { mockMutable.remove("goalDayReached") }
		}

		@Test
		fun `skips goal removal when upgrading from 359 or later`() {
			coEvery { mockPrefs.fetchLong(any(), any()) } returns 359L

			receiver.onReceive(context, updateIntent())

			// Wait for async processing to finish
			verify(timeout = 2000) { pendingResult.finish() }
			verify(exactly = 0) { mockMutable.remove("goalWeekReached") }
			verify(exactly = 0) { mockMutable.remove("goalDayReached") }
		}

		@Test
		fun `updates stored version after handling`() {
			coEvery { mockPrefs.fetchLong(any(), any()) } returns 400L

			receiver.onReceive(context, updateIntent())

			verify(timeout = 2000) {
				mockMutable.setLong("key_last_app_version", 400L)
			}
		}

		@Test
		fun `finishes pending result after processing`() {
			coEvery { mockPrefs.fetchLong(any(), any()) } returns 100L

			receiver.onReceive(context, updateIntent())

			verify(timeout = 2000) { pendingResult.finish() }
		}
	}
}
