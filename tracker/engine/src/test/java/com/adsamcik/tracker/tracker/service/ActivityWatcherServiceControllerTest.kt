package com.adsamcik.tracker.tracker.service

import android.content.Context
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.inject.Provider

class ActivityWatcherServiceControllerTest {

	private val context: Context = mockk(relaxed = true)
	private val trackerStateReader: TrackerStateReader = mockk()
	private val lockManager: LockManager = mockk()

	private lateinit var controller: ActivityWatcherServiceController

	@BeforeEach
	fun setUp() {
		every { context.applicationContext } returns context
		controller = ActivityWatcherServiceController(
			context = context,
			trackerStateReader = trackerStateReader,
			lockManagerProvider = FixedProvider(lockManager),
		)
	}

	@Test
	fun `stops watcher when default tracker lock state comes from injected lock manager`() {
		val service = mockk<ActivityWatcherService>(relaxed = true)
		controller.serviceInstance = service
		every { lockManager.isLocked } returns true
		every { trackerStateReader.isServiceRunning } returns false

		controller.poke(
			watcherPreference = true,
			updateInterval = 15,
			autoTracking = 1,
		)

		verify(exactly = 1) { service.stopSelf() }
	}

	@Test
	fun `stops watcher when default tracker running state comes from injected controller`() {
		val service = mockk<ActivityWatcherService>(relaxed = true)
		controller.serviceInstance = service
		every { lockManager.isLocked } returns false
		every { trackerStateReader.isServiceRunning } returns true

		controller.poke(
			watcherPreference = true,
			updateInterval = 15,
			autoTracking = 1,
		)

		verify(exactly = 1) { service.stopSelf() }
	}

	@Test
	fun `stops watcher when activity recognition permission is revoked`() {
		val service = mockk<ActivityWatcherService>(relaxed = true)
		controller.serviceInstance = service

		controller.poke(
			watcherPreference = true,
			updateInterval = 15,
			autoTracking = 1,
			trackerLocked = false,
			trackerRunning = false,
			hasActivityPermission = false,
		)

		verify(exactly = 1) { service.stopSelf() }
	}

	private class FixedProvider<T>(private val value: T) : Provider<T> {
		override fun get(): T = value
	}
}
