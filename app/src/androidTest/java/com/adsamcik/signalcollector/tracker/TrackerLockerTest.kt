package com.adsamcik.signalcollector.tracker

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
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

		Assert.assertFalse(TrackerLocker.isLocked.value)

		TrackerLocker.lockTimeLock(context, 0)
		Assert.assertTrue(!TrackerLocker.isTimeLocked)

		val testObserver = TrackerLocker.isLocked.test()

		TrackerLocker.lockTimeLock(context, Constants.SECOND_IN_MILLISECONDS)
		Assert.assertTrue(TrackerLocker.isTimeLocked)

		testObserver
				.awaitValue(500, TimeUnit.MILLISECONDS)
				.assertValue(true)
				.awaitNextValue(10, TimeUnit.SECONDS)
				.assertValue(false)
	}

	//Hard to run since charging causes immediate unlock
	/*@Test
	fun stopTillRechargeWOCallbackTest() {
		val context = ApplicationProvider.getApplicationContext<Context>()

		val testObserver = TrackerLocker.isLocked.test()

		Assert.assertFalse(TrackerLocker.isLocked.value)
		Assert.assertFalse(TrackerLocker.isChargeLocked)

		TrackerLocker.lockUntilRecharge(context)
		Assert.assertTrue(TrackerLocker.isChargeLocked)
		testObserver.awaitNextValue(500, TimeUnit.MILLISECONDS)
				.assertValue(true)

		TrackerLocker.unlockRechargeLock(context)
		Assert.assertFalse(TrackerLocker.isChargeLocked)
		testObserver.awaitNextValue(500, TimeUnit.MILLISECONDS)
				.assertValue(false)
	}*/
}