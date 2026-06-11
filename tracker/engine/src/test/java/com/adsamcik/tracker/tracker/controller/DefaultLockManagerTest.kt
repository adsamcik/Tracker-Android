package com.adsamcik.tracker.tracker.controller

import android.content.Context
import android.content.res.Resources
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.service.ActivityWatcherServiceController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.spyk
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.inject.Provider
import kotlin.test.assertTrue

class DefaultLockManagerTest {

	private val context: Context = mockk(relaxed = true)
	private val resources: Resources = mockk(relaxed = true)
	private val trackerServiceController: TrackerServiceController = mockk(relaxed = true)
	private val watcherLockManager: LockManager = mockk(relaxed = true)

	private lateinit var activityWatcherController: ActivityWatcherServiceController
	private lateinit var lockManager: DefaultLockManager

	@BeforeEach
	fun setUp() {
		every { context.applicationContext } returns context
		every { context.resources } returns resources
		every { context.getString(R.string.settings_disabled_time_key) } returns "disabled_time"
		every { context.getString(R.string.settings_disabled_time_default) } returns "0"
		every { trackerServiceController.sessionInfoFlow } returns MutableStateFlow(null)
		every { trackerServiceController.isServiceRunning } returns true
		every { watcherLockManager.isLocked } returns false

		activityWatcherController = spyk(
			ActivityWatcherServiceController(
				context = context,
				trackerServiceController = trackerServiceController,
				lockManagerProvider = FixedProvider(watcherLockManager),
			)
		)

		mockkConstructor(Preferences::class)
		every { anyConstructed<Preferences>().edit(any()) } just runs

		lockManager = DefaultLockManager(
			trackerServiceController = trackerServiceController,
			activityWatcherController = activityWatcherController,
		)
	}

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	@Test
	fun `initializeFromPersistence restores state without rewriting preferences`() = runTest {
		coEvery {
			anyConstructed<Preferences>().fetchLong("disabled_time", 0L)
		} returns Time.nowMillis + 60_000
		coEvery {
			anyConstructed<Preferences>().fetchBooleanRes(
				R.string.settings_disabled_recharge_key,
				R.string.settings_disabled_recharge_default,
			)
		} returns true

		lockManager.initializeFromPersistence(context)

		assertTrue(lockManager.isLocked)
		assertTrue(lockManager.isChargeLocked)
		verify(exactly = 0) { anyConstructed<Preferences>().edit(any()) }
		verify(exactly = 1) { activityWatcherController.poke(any(), any(), any(), any(), any()) }
	}

	@Test
	fun `initializeFromPersistence only loads persisted values once per process`() = runTest {
		coEvery {
			anyConstructed<Preferences>().fetchLong("disabled_time", 0L)
		} returns 0L
		coEvery {
			anyConstructed<Preferences>().fetchBooleanRes(
				R.string.settings_disabled_recharge_key,
				R.string.settings_disabled_recharge_default,
			)
		} returns false

		lockManager.initializeFromPersistence(context)
		lockManager.initializeFromPersistence(context)

		coVerify(exactly = 1) { anyConstructed<Preferences>().fetchLong("disabled_time", 0L) }
		coVerify(exactly = 1) {
			anyConstructed<Preferences>().fetchBooleanRes(
				R.string.settings_disabled_recharge_key,
				R.string.settings_disabled_recharge_default,
			)
		}
		verify(exactly = 0) { anyConstructed<Preferences>().edit(any()) }
	}

	private class FixedProvider<T>(private val value: T) : Provider<T> {
		override fun get(): T = value
	}
}
