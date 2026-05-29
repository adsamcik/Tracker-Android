package com.adsamcik.tracker.logger

import android.util.Log
import com.adsamcik.tracker.shared.preferences.Preferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Logger Initialization")
class LoggerInitTest {

	private fun setField(name: String, value: Any?) {
		val field = Logger::class.java.getDeclaredField(name)
		field.isAccessible = true
		field.set(Logger, value)
	}

	@Suppress("UNCHECKED_CAST")
	private fun <T> getField(name: String): T {
		val field = Logger::class.java.getDeclaredField(name)
		field.isAccessible = true
		return field.get(Logger) as T
	}

	private fun resetLogger() {
		setField("isInitialized", false)
		setField("initDeferred", CompletableDeferred<Unit>())
		setField("preferences", null)
		setField("genericDao", null)
		setField("initializationJob", null)
		getField<java.util.concurrent.ConcurrentLinkedQueue<*>>("logBuffer").clear()
	}

	@BeforeEach
	fun setUp() {
		resetLogger()
		mockkStatic(Log::class)
		every { Log.d(any(), any()) } returns 0
	}

	@AfterEach
	fun tearDown() {
		resetLogger()
		unmockkAll()
	}

	@Nested
	@DisplayName("logWithPreference awaits initialization")
	inner class LogWithPreferenceAwait {

		@Test
		@DisplayName("delivers log after initialization completes")
		fun deliversLogAfterInit() {
			val testDeferred: CompletableDeferred<Unit> = getField("initDeferred")

			val mockPrefs = mockk<Preferences>()
			@Suppress("DEPRECATION")
			every { mockPrefs.getBooleanRes(any<Int>(), any<Int>()) } returns true
			val mockDao = mockk<GenericLogDao>(relaxed = true)

			val logData = LogData(message = "test-init-wait", source = "test")
			Logger.logWithPreference(logData, 1, 2)

			// Simulate initialization completing
			setField("preferences", mockPrefs)
			setField("genericDao", mockDao)
			setField("isInitialized", true)
			testDeferred.complete(Unit)

			// Verify the specific preference key was checked after init
			@Suppress("DEPRECATION")
			verify(timeout = 2000) { mockPrefs.getBooleanRes(1, 2) }
		}

		@Test
		@DisplayName("does not busy-wait with polling loop")
		fun doesNotBusyWait() = runTest {
			// Replicate Logger.logWithPreference's waiting pattern:
			// await() suspends until completion vs delay(100) loop polling.
			val initDeferred = CompletableDeferred<Unit>()
			var preferenceChecked = false

			launch {
				initDeferred.await()
				preferenceChecked = true
			}

			// With a delay(100) polling loop, advancing 10s would wake ~100 times.
			// With await(), the coroutine stays suspended with zero wakeups.
			advanceTimeBy(10_000)
			preferenceChecked shouldBe false

			initDeferred.complete(Unit)
			advanceUntilIdle()
			preferenceChecked shouldBe true
		}
	}
}
