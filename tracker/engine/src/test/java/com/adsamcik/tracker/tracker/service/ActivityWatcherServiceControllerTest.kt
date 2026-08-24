package com.adsamcik.tracker.tracker.service

import android.content.Context
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ActivityWatcherServiceControllerTest {

	private val context: Context = mockk(relaxed = true)
	private val controller = ActivityWatcherServiceController(context)

	@Test
	fun `legacy pokes never start or bind a watcher service`() {
		controller.poke()
		controller.poke(
			watcherPreference = true,
			updateInterval = 15,
			autoTracking = 1,
			trackerLocked = false,
			trackerRunning = false,
		)
		controller.pauseForDataDeletion()
		controller.resumeAfterDataDeletion()

		verify(exactly = 0) { context.startService(any()) }
		verify(exactly = 0) { context.startForegroundService(any()) }
		verify(exactly = 0) { context.bindService(any(), any(), any<Int>()) }
	}
}
