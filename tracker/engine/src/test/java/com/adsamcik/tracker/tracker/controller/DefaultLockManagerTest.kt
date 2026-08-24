package com.adsamcik.tracker.tracker.controller

import android.app.AlarmManager
import android.app.PendingIntent
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
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class DefaultLockManagerTest {

	private val context: Context = mockk(relaxed = true)
	private val resources: Resources = mockk(relaxed = true)
	private val alarmManager: AlarmManager = mockk(relaxed = true)
	private val trackerStateReader: TrackerStateReader = mockk(relaxed = true)
	private lateinit var activityWatcherController: ActivityWatcherServiceController
	private lateinit var lockManager: DefaultLockManager

	@BeforeEach
	fun setUp() {
		every { context.applicationContext } returns context
		every { context.resources } returns resources
		every { context.getString(R.string.settings_disabled_time_key) } returns "disabled_time"
		every { context.getString(R.string.settings_disabled_time_default) } returns "0"
		every { context.getSystemService(Context.ALARM_SERVICE) } returns alarmManager
		mockkStatic(PendingIntent::class)
		every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk(relaxed = true)
		every { trackerStateReader.sessionInfoFlow } returns MutableStateFlow(null)
		every { trackerStateReader.isServiceRunning } returns true
		activityWatcherController = spyk(
			ActivityWatcherServiceController(context = context)
		)

		mockkConstructor(Preferences::class)
		every { anyConstructed<Preferences>().edit(any()) } just runs

		lockManager = DefaultLockManager(
			trackerStateReader = trackerStateReader,
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
		// A still-active future time lock must re-arm its AlarmManager unlock, since
		// alarms do not survive a reboot.
		verify(exactly = 1) { alarmManager.set(AlarmManager.RTC_WAKEUP, any(), any()) }
	}

	@Test
	fun `initializeFromPersistence does not re-arm alarm when no future time lock`() = runTest {
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

		verify(exactly = 0) { alarmManager.set(any<Int>(), any<Long>(), any<PendingIntent>()) }
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

	@Test
	fun `unlockTimeLock does not force watcher off via watcherPreference argument`() = runTest {
		// A recharge lock remains active so isLockedRightNow() stays true after the time lock
		// clears. Previously unlockTimeLock called poke(watcherPreference = isLockedRightNow()),
		// which routed the lock state into the watcher-preference slot.
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

		lockManager.unlockTimeLock(context)

		verify(exactly = 0) {
			activityWatcherController.poke(
				watcherPreference = true,
				updateInterval = any(),
				autoTracking = any(),
				trackerLocked = any(),
				trackerRunning = any(),
			)
		}
	}

}
