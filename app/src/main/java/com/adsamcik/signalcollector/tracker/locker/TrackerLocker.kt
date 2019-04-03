package com.adsamcik.signalcollector.tracker.locker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.AnyThread
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.service.ActivityWatcherService
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.misc.NonNullLiveMutableData
import com.adsamcik.signalcollector.misc.extension.alarmManager
import com.adsamcik.signalcollector.misc.extension.stopService
import com.adsamcik.signalcollector.preference.Preferences
import com.adsamcik.signalcollector.tracker.receiver.TrackerUnlockReceiver
import com.adsamcik.signalcollector.tracker.service.TrackerService

/**
 * Singleton that takes care of tracking locks.
 */
@AnyThread
object TrackerLocker {
	/**
	 * Id of the job that disables the recharge lock when device is connected to a charger
	 */
	const val JOB_DISABLE_TILL_RECHARGE_TAG: String = "disableTillRecharge"

	//Locking order is in the order of variable definitions

	private var lockedUntil: Long = 0
		set(value) {
			field = value
			refreshLockState()
		}

	private var lockedUntilRecharge = false
		set(value) {
			field = value
			refreshLockState()
		}

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
	val isTimeLocked: Boolean get() = System.currentTimeMillis() < lockedUntil

	/**
	 * Returns true if tracking is locked until recharge
	 */
	val isChargeLocked: Boolean get() = lockedUntilRecharge

	private fun isLockedRightNow(): Boolean {
		synchronized(this) {
			return isTimeLocked || isChargeLocked
		}
	}

	private fun refreshLockState() = isLocked.postValue(isLockedRightNow())

	/**
	 * Initializes locks from SharedPreferences (persistence)
	 */
	fun initializeFromPersistence(context: Context) {
		val preferences = Preferences.getPref(context)

		val resources = context.resources

		val keyDisabledTime = resources.getString(R.string.settings_disabled_time_key)
		val defaultDisabledTime = resources.getString(R.string.settings_disabled_time_default).toLong()

		val keyDisabledRecharge = resources.getString(R.string.settings_disabled_recharge_key)
		val defaultDisabledRecharge = resources.getString(R.string.settings_disabled_recharge_default).toBoolean()

		synchronized(this) {
			lockedUntil = preferences.getLong(keyDisabledTime, defaultDisabledTime)
			lockedUntilRecharge = preferences.getBoolean(keyDisabledRecharge, defaultDisabledRecharge)
		}

		isLocked.postValue(isLockedRightNow())
	}

	private fun setRechargeLock(context: Context, lock: Boolean) {
		val keyDisabledRecharge = context.getString(R.string.settings_disabled_recharge_key)
		synchronized(this) {
			Preferences.getPref(context).edit {
				putBoolean(keyDisabledRecharge, lock)
			}

			lockedUntilRecharge = lock

			pokeWatcherService(context)
		}
	}

	private fun setTimeLock(context: Context, time: Long) {
		val keyDisabledTime = context.getString(R.string.settings_disabled_time_key)
		synchronized(this) {
			Preferences.getPref(context).edit {
				putLong(keyDisabledTime, time)
			}

			lockedUntil = time

			pokeWatcherService(context)
		}
	}

	/**
	 * This method ensures the watcher is in proper state
	 * It cannot be handled with observe because poke method requires context
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
			val workManager = WorkManager.getInstance()
			val constraints = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()
			val work = OneTimeWorkRequestBuilder<DisableTillRechargeWorker>().setConstraints(constraints).addTag(JOB_DISABLE_TILL_RECHARGE_TAG).build()
			workManager.enqueue(work)
			setRechargeLock(context, true)
		}
	}

	/**
	 * Removed recharge lockTimeLock
	 */
	fun unlockRechargeLock(context: Context) {
		WorkManager.getInstance().cancelAllWorkByTag(JOB_DISABLE_TILL_RECHARGE_TAG)
		setRechargeLock(context, false)
	}

	/**
	 * Sets auto lockTimeLock with time passed in variable.
	 * Cannot be locked for less than a second
	 */
	fun lockTimeLock(context: Context, lockTimeInMillis: Long) {
		if (lockTimeInMillis <= Constants.SECOND_IN_MILLISECONDS)
			return

		synchronized(this) {
			lockedUntil = System.currentTimeMillis() + lockTimeInMillis

			ActivityWatcherService.poke(context, trackerLocked = true)

			if (TrackerService.isServiceRunning.value && TrackerService.isBackgroundActivated)
				context.stopService<TrackerService>()

			context.alarmManager.set(AlarmManager.RTC_WAKEUP,
					lockedUntil, getIntent(context))
		}
	}

	/**
	 * Unlocks active time lock
	 * Thread safe
	 */
	fun unlockTimeLock(context: Context) {
		synchronized(this) {
			context.alarmManager.cancel(getIntent(context))
			setTimeLock(context, 0)

			ActivityWatcherService.poke(context, isLockedRightNow())
		}
	}

	/**
	 * Pokes the locks and tries if they are not supposed to be unlocked
	 */
	fun poke(context: Context) {
		synchronized(this) {
			if (lockedUntil < System.currentTimeMillis()) {
				val lockedRightNow = isLockedRightNow()
				isLocked.postValue(lockedRightNow)
				ActivityWatcherService.poke(context, trackerLocked = lockedRightNow)
			}
		}
	}

	private fun getIntent(context: Context): PendingIntent {
		val intent = Intent(context, TrackerUnlockReceiver::class.java)
		return PendingIntent.getBroadcast(context, 0, intent, 0)
	}
}