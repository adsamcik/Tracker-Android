package com.adsamcik.signalcollector.tracker

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
import com.jraska.livedata.test
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit


class TrackerLockerTest {

	@get:Rule
	val testRule = InstantTaskExecutorRule()

	@Test
	fun timeLockTest() {
		val context = ApplicationProvider.getApplicationContext<Context>()

		TrackerLocker.isLocked.test()
				.assertHasValue()
				.assertValue(false)
				.awaitNextValue(500, TimeUnit.MILLISECONDS)
				.assertValue(true)
				.awaitNextValue(5, TimeUnit.SECONDS)
				.assertValue(false)

		TrackerLocker.lockTimeLock(context, 0)
		Assert.assertTrue(!TrackerLocker.isTimeLocked)

		TrackerLocker.lockTimeLock(context, Constants.SECOND_IN_MILLISECONDS)
		Assert.assertTrue(TrackerLocker.isTimeLocked)
	}

	@Test
	fun stopTillRechargeWOCallbackTest() {
		val context = ApplicationProvider.getApplicationContext<Context>()

		TrackerLocker.isLocked.test()
				.assertHasValue()
				.assertValue(false)
				.awaitNextValue(500, TimeUnit.MILLISECONDS)
				.assertValue(true)
				.awaitNextValue(500, TimeUnit.MILLISECONDS)
				.assertValue(false)

		Assert.assertFalse(TrackerLocker.isChargeLocked)

		TrackerLocker.lockUntilRecharge(context)
		Assert.assertTrue(TrackerLocker.isChargeLocked)

		TrackerLocker.unlockRechargeLock(context)
		Assert.assertFalse(TrackerLocker.isChargeLocked)
	}
}