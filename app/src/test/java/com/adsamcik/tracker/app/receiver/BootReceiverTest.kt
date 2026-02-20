package com.adsamcik.tracker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.app.AppGraph
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.tracker.controller.LockManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
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
		private val testScope = CoroutineScope(
			UnconfinedTestDispatcher() + CoroutineExceptionHandler { _, _ -> }
		)
		private val appGraph = mockk<AppGraph>(relaxed = true)
		private val app = mockk<Application>(relaxed = true)
		private val context = mockk<Context>(relaxed = true)
		private val pendingResult = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
		private val receiver = spyk(BootReceiver())

		@BeforeEach
		fun setUp() {
			coEvery { lockManager.initializeFromPersistence(any()) } just Runs
			every { appGraph.appScope } returns testScope
			every { appGraph.lockManager } returns lockManager
			every { app.appGraph } returns appGraph
			every { context.applicationContext } returns app
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
