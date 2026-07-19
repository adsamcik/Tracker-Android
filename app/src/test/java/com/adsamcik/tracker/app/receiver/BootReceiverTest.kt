package com.adsamcik.tracker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
import dagger.hilt.android.EntryPointAccessors
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BootReceiverTest {

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	@Nested
	inner class NonBootIntent {

		@Test
		fun `ignores intents that are not boot completed`() {
			val receiver = spyk(BootReceiver())
			val context = mockk<Context>(relaxed = true)
			val intent = mockk<Intent> {
				every { action } returns "android.intent.action.POWER_CONNECTED"
			}

			receiver.onReceive(context, intent)

			verify(exactly = 0) { receiver.goAsync() }
		}
	}

	@Nested
	inner class BootCompleted {

		private val lockManager = mockk<LockManager>(relaxed = true)
		private val startupGuard = mockk<TrackingStartupGuard>()
		private val testScope = CoroutineScope(
			UnconfinedTestDispatcher() + CoroutineExceptionHandler { _, _ -> }
		)
		private val entryPoint = mockk<BootReceiver.BootReceiverEntryPoint>()
		private val context = mockk<Context>(relaxed = true)
		private val pendingResult = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
		private val receiver = spyk(BootReceiver())

		@BeforeEach
		fun setUp() {
			coEvery { lockManager.initializeFromPersistence(any()) } just Runs
			every { entryPoint.lockManager() } returns lockManager
			every { entryPoint.trackingStartupGuard() } returns startupGuard
			every { startupGuard.isAutoRecoverySuppressed(any()) } returns false
			every { entryPoint.appScope() } returns testScope
			every { context.applicationContext } returns context
			mockkStatic(EntryPointAccessors::class)
			every {
				EntryPointAccessors.fromApplication(
					any(),
					BootReceiver.BootReceiverEntryPoint::class.java
				)
			} returns entryPoint
			every { receiver.goAsync() } returns pendingResult
		}

		private fun bootIntent(): Intent = mockk {
			every { action } returns "android.intent.action.BOOT_COMPLETED"
		}

		@Test
		fun `initializes lock manager from persistence`() {
			receiver.onReceive(context, bootIntent())

			coVerify { lockManager.initializeFromPersistence(context) }
		}

		@Test
		fun `force stopped startup does not rearm locks or background tracking`() {
			every { startupGuard.isAutoRecoverySuppressed(any()) } returns true

			receiver.onReceive(context, bootIntent())

			coVerify(exactly = 0) { lockManager.initializeFromPersistence(any()) }
			verify(exactly = 0) { receiver.goAsync() }
		}

		@Test
		fun `finishes pending result after processing`() {
			receiver.onReceive(context, bootIntent())

			verify { pendingResult.finish() }
		}

		@Test
		fun `finishes pending result even when initialization fails`() {
			coEvery {
				lockManager.initializeFromPersistence(any())
			} throws RuntimeException("init failed")

			receiver.onReceive(context, bootIntent())

			verify { pendingResult.finish() }
		}
	}
}
