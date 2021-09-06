package com.adsamcik.tracker.tracker.locker

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.Context
import android.content.Intent
import androidx.annotation.AnyThread
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.alarmManager
import com.adsamcik.tracker.shared.base.extension.stopService
import com.adsamcik.tracker.shared.base.misc.NonNullLiveMutableData
import com.adsamcik.tracker.shared.preferences.Preferences

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.receiver.TrackerTimeUnlockReceiver
import com.adsamcik.tracker.tracker.service.ActivityWatcherService
import com.adsamcik.tracker.tracker.service.TrackerService

/**
 * Singleton that takes care of tracking locks.
 */
@Suppress("TooManyFunctions")
@AnyThread
object TrackerLocker {
	/**
	 * Id of the job that disables the recharge lock when device is connected to a charger
	 */
	const val WORK_DISABLE_TILL_RECHARGE_TAG: String = "disableTillRecharge"

	//Locking order is in the order of variable definitions

	private var lockedUntilTime: Long = 0
	private var lockedUntilRecharge = false

	/**
	 * Live object that can be observed
	 * returns true if any lockTimeLock is currently engaged
	 */
	val isLocked: NonNullLiveMutableData<Boolean> by lazy {
		NonNullLiveMutableData(isLockedRightNow())
	}

	/**
	 * Returns true if time lock is active
	 */
	val isTimeLocked: Boolean get() = Time.nowMillis < lockedUntilTime

	/**
	 * Returns true if tracking is locked until recharge
	 */
	val isChargeLocked: Boolean get() = lockedUntilRecharge

	private fun isLockedRightNow(): Boolean {
		synchronized(this) {
			return isTimeLocked.or(isChargeLocked)
		}
	}

	/**
	 * Initializes locks from SharedPreferences (persistence)
	 */
	fun initializeFromPersistence(context: Context) {
		val preferences = Preferences.getPref(context)

		setTimeLock(
				context,
				preferences.getLongResString(
						R.string.settings_disabled_time_key,
						R.string.settings_disabled_time_default
				)
		)
		setRechargeLock(
				context,
				preferences.getBooleanRes(
						R.string.settings_disabled_recharge_key,
						R.string.settings_disabled_recharge_default
				)
		)
	}

	private fun setRechargeLock(context: Context, lock: Boolean) {
		val keyDisabledRecharge = context.getString(R.string.settings_disabled_recharge_key)
		synchronized(this) {
			Preferences.getPref(context).edit {
				setBoolean(keyDisabledRecharge, lock)
			}

			lockedUntilRecharge = lock

			refreshLockState(context)
		}
	}

	private fun setTimeLock(context: Context, time: Long) {
		synchronized(this) {
			Preferences.getPref(context).edit {
				setLong(R.string.settings_disabled_time_key, time)
			}

			lockedUntilTime = time

			refreshLockState(context)
		}
	}

	private fun refreshLockState(context: Context) {
		val isLockedRightNow = isLockedRightNow()
		if (isLockedRightNow != isLocked.value) {
			isLocked.postValue(isLockedRightNow)
		}

		pokeWatcherService(context)

		if (isLockedRightNow && TrackerService.sessionInfo.value?.isInitiatedByUser == false) {
			context.stopService<TrackerService>()
		}
	}

	/**
	 * This method ensures the watcher is in proper state
	 * It cannot be handled with observe because checkLockTime method requires context
	 */
	private fun pokeWatcherService(context: Context) {
		//Desired state is checked from other sources because it might not be ready yet in LiveData
		ActivityWatcherService.poke(context, trackerLocked = isLockedRightNow())
	}

	/**
	 * Locks tracking until phone is connected to a charger
	 */
	fun lockUntilRecharge(context: Context) {
		synchronized(this) {
			val workManager = WorkManager.getInstance(context)
			val constraints = Constraints.Builder().setRequiresCharging(true)
					.setRequiresBatteryNotLow(true).build()
			val work = OneTimeWorkRequestBuilder<DisableTillRechargeWorker>().setConstraints(
					constraints
			)
					.addTag(WORK_DISABLE_TILL_RECHARGE_TAG).build()
			workManager.enqueue(work)
			setRechargeLock(context, true)
		}
	}

	/**
	 * Removed recharge lockTimeLock
	 */
	fun unlockRechargeLock(context: Context) {
		synchronized(this) {
			WorkManager.getInstance(context).cancelAllWorkByTag(WORK_DISABLE_TILL_RECHARGE_TAG)
			setRechargeLock(context, false)
		}
	}

	/**
	 * Sets auto lockTimeLock with time passed in variable.
	 * Cannot be locked for less than a second
	 */
	fun lockTimeLock(context: Context, lockTimeInMillis: Long) {
		synchronized(this) {
			val lockUntilTime = Time.nowMillis + lockTimeInMillis
			if (lockTimeInMillis < Time.SECOND_IN_MILLISECONDS || lockUntilTime <= this.lockedUntilTime) {
				return
			}

			setTimeLock(context, lockUntilTime)
			context.alarmManager.set(
					AlarmManager.RTC_WAKEUP,
					lockUntilTime, getTimeUnlockBroadcastIntent(context)
			)
		}
	}

	/**
	 * Unlocks active time lock
	 */
	fun unlockTimeLock(context: Context) {
		synchronized(this) {
			context.alarmManager.cancel(getTimeUnlockBroadcastIntent(context))
			setTimeLock(context, 0)

			ActivityWatcherService.poke(context, isLockedRightNow())
		}
	}

	/**
	 * Unlocks all locks
	 */
	fun unlock(context: Context) {
		synchronized(this) {
			unlockRechargeLock(context)
			unlockTimeLock(context)
		}
	}

	private fun getTimeUnlockBroadcastIntent(context: Context): PendingIntent {
		val intent = Intent(context, TrackerTimeUnlockReceiver::class.java)
		return PendingIntent.getBroadcast(context, 0, intent, FLAG_IMMUTABLE)
	}
}

