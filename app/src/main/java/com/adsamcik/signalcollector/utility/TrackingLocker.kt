package com.adsamcik.signalcollector.utility

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import com.adsamcik.signalcollector.extensions.alarmManager
import com.adsamcik.signalcollector.extensions.stopService
import com.adsamcik.signalcollector.receivers.TrackingUnlockReceiver
import com.adsamcik.signalcollector.services.ActivityWakerService
import com.adsamcik.signalcollector.services.TrackerService
import javax.annotation.concurrent.ThreadSafe

/**
 * Singleton that takes care of tracking locks.
 */
@ThreadSafe
object TrackingLocker {
    /**
     * Id of the job that disables the recharge lock when device is connected to a charger
     */
    const val JOB_DISABLE_TILL_RECHARGE_TAG = "disableTillRecharge"

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
    val isTimeLocked get() = System.currentTimeMillis() < lockedUntil

    /**
     * Returns true if tracking is locked until recharge
     */
    val isChargeLocked get() = lockedUntilRecharge

    private fun isLockedRightNow(): Boolean {
        synchronized(lockedUntil) {
            synchronized(lockedUntilRecharge) {
                return isTimeLocked || isChargeLocked
            }
        }
    }

    private fun refreshLockState() = isLocked.postValue(isLockedRightNow())

    /**
     * Initializes locks from SharedPreferences (persistence)
     */
    fun initializeFromPersistence(context: Context) {
        val preferences = Preferences.getPref(context)
        synchronized(lockedUntil) {
            lockedUntil = preferences.getLong(Preferences.PREF_STOP_UNTIL_TIME, 0)
        }

        synchronized(lockedUntilRecharge) {
            lockedUntilRecharge = preferences.getBoolean(Preferences.PREF_STOP_UNTIL_RECHARGE, false)
        }

        isLocked.postValue(isLockedRightNow())
    }

    private fun setRechargeLock(context: Context, lock: Boolean) {
        synchronized(lockedUntilRecharge) {
            Preferences.getPref(context).edit {
                putBoolean(Preferences.PREF_STOP_UNTIL_RECHARGE, lock)
            }

            lockedUntilRecharge = lock

            pokeWakerService(context)
        }
    }

    private fun setTimeLock(context: Context, time: Long) {
        synchronized(lockedUntil) {
            Preferences.getPref(context).edit {
                putLong(Preferences.PREF_STOP_UNTIL_TIME, time)
            }

            lockedUntil = time

            pokeWakerService(context)
        }
    }

    /**
     * This method ensures the waker is in proper state
     * It cannot be handled with observe because pokeWithCheck method requires context
     */
    private fun pokeWakerService(context: Context) {
        //Desired state is checked from other sources because it might not be ready yet in LiveData
        ActivityWakerService.poke(context, ActivityWakerService.getServicePreference(context) && !isLockedRightNow())
    }

    /**
     * Locks tracking until phone is connected to a charger
     */
    fun lockUntilRecharge(context: Context) {
        synchronized(lockedUntilRecharge) {
            val workManager = WorkManager.getInstance()
            val constraints = Constraints.Builder().setRequiresCharging(true).build()
            val work = OneTimeWorkRequestBuilder<DisableTillRechargeWorker>().setConstraints(constraints).build()
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
     */
    fun lockTimeLock(context: Context, lockTimeInMillis: Long) {
        synchronized(lockedUntil) {
            synchronized(lockedUntilRecharge) {
                lockedUntil = System.currentTimeMillis() + lockTimeInMillis

                ActivityWakerService.pokeWithCheck(context)

                if (TrackerService.isServiceRunning.value && TrackerService.isBackgroundActivated)
                    context.stopService<TrackerService>()

                context.alarmManager.set(AlarmManager.RTC_WAKEUP,
                        lockedUntil, getIntent(context))
            }
        }
    }

    /**
     * Unlocks active time lock
     * Thread safe
     */
    fun unlockTimeLock(context: Context) {
        synchronized(lockedUntil) {
            context.alarmManager.cancel(getIntent(context))
            setTimeLock(context, 0)

            ActivityWakerService.pokeWithCheck(context)
        }
    }

    /**
     * Pokes the locks and tries if they are not supposed to be unlocked
     */
    fun poke(context: Context) {
        synchronized(lockedUntil) {
            if (lockedUntil < System.currentTimeMillis()) {
                isLocked.postValue(false)
                ActivityWakerService.pokeWithCheck(context)
            }
        }
    }

    private fun getIntent(context: Context): PendingIntent {
        val intent = Intent(context, TrackingUnlockReceiver::class.java)
        return PendingIntent.getBroadcast(context, 0, intent, 0)
    }

    /**
     * JobService used for job that waits until device is connected to a charger to remove recharge lockTimeLock
     */
    class DisableTillRechargeWorker : Worker() {
        override fun doWork(): Result {
            unlockRechargeLock(applicationContext)
            return Result.SUCCESS
        }
    }
}