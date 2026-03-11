package com.adsamcik.tracker.tracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.controller.LockManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert
import org.junit.Before
import org.junit.Test


class TrackerLockerTest {

	@EntryPoint
	@InstallIn(SingletonComponent::class)
	interface LockManagerEntryPoint {
		fun lockManager(): LockManager
	}

	private lateinit var context: Context
	private lateinit var lockManager: LockManager

	@Before
	fun setup() {
		context = ApplicationProvider.getApplicationContext<Context>()
		lockManager = EntryPointAccessors.fromApplication(
			context,
			LockManagerEntryPoint::class.java
		).lockManager()
	}

	@Test
	fun timeLockTest() = runBlocking {
		// Verify initial unlocked state
		Assert.assertFalse(lockManager.isLocked)

		// Lock for 0ms should not engage (minimum 1 second)
		lockManager.lockTimeLock(context, 0)
		Assert.assertFalse(lockManager.isTimeLocked)

		// Lock for 1 second should engage
		lockManager.lockTimeLock(context, Time.SECOND_IN_MILLISECONDS)
		Assert.assertTrue(lockManager.isTimeLocked)
		
		// Verify combined lock state reflects time lock
		Assert.assertTrue(lockManager.isLocked)
		
		// Wait for lock to expire (with timeout to prevent test hanging)
		withTimeout(12000) {
			// Collect until we get false (unlocked)
			lockManager.isLockedFlow.first { !it }
		}
		
		// Verify lock expired
		Assert.assertFalse(lockManager.isTimeLocked)
		Assert.assertFalse(lockManager.isLocked)
	}

	// Note: Recharge lock test is challenging to run in CI since charging state 
	// causes immediate unlock. Kept as commented reference for manual testing.
	/*
	@Test
	fun rechargeLockTest() = runBlocking {
		// Verify initial unlocked state
		Assert.assertFalse(lockManager.isLocked)
		Assert.assertFalse(lockManager.isChargeLocked)

		// Lock until recharge
		lockManager.lockUntilRecharge(context)
		Assert.assertTrue(lockManager.isChargeLocked)
		Assert.assertTrue(lockManager.isLocked)
		
		// Wait for lock state to update
		withTimeout(1000) {
			lockManager.isLockedFlow.first { it }
		}

		// Unlock recharge lock
		lockManager.unlockRechargeLock(context)
		Assert.assertFalse(lockManager.isChargeLocked)
		
		// Wait for unlock
		withTimeout(1000) {
			lockManager.isLockedFlow.first { !it }
		}
		Assert.assertFalse(lockManager.isLocked)
	}
	*/
}
