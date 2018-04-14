package com.adsamcik.signalcollector.utility

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.adsamcik.signalcollector.extensions.getSystemServiceTyped
import com.adsamcik.signalcollector.extensions.stopService
import com.adsamcik.signalcollector.jobs.scheduler
import com.adsamcik.signalcollector.receivers.TrackingUnlockReceiver
import com.adsamcik.signalcollector.services.ActivityWakerService
import com.adsamcik.signalcollector.services.TrackerService
import com.crashlytics.android.Crashlytics
import javax.annotation.concurrent.ThreadSafe

/**
 * Singleton that takes care of tracking locks.
 */
@ThreadSafe
object TrackingLocker {
    private const val JOB_DISABLE_TILL_RECHARGE_ID = 58946

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

    private fun isLockedRightNow(): Boolean {
        synchronized(lockedUntil) {
            synchronized(lockedUntilRecharge) {
                return System.currentTimeMillis() < lockedUntil || lockedUntilRecharge
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
    fun lockUntilRecharge(context: Context): Boolean {
        synchronized(lockedUntilRecharge) {
            val scheduler = scheduler(context)
            val jobBuilder = JobInfo.Builder(JOB_DISABLE_TILL_RECHARGE_ID, ComponentName(context, DisableTillRechargeJobService::class.java))
            jobBuilder.setPersisted(true).setRequiresCharging(true)
            if (scheduler.schedule(jobBuilder.build()) == JobScheduler.RESULT_SUCCESS) {
                setRechargeLock(context, true)
                return true
            } else
                Crashlytics.logException(Throwable("failed to schedule job"))
            return false
        }
    }

    /**
     * Removed recharge lockTimeLock
     */
    fun unlockRechargeLock(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.cancel(JOB_DISABLE_TILL_RECHARGE_ID)
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

                if (TrackerService.isRunning && TrackerService.isBackgroundActivated)
                    context.stopService<TrackerService>()

                getAlarmManager(context).set(AlarmManager.RTC_WAKEUP,
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
            getAlarmManager(context).cancel(getIntent(context))
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

    private fun getAlarmManager(context: Context) = context.getSystemServiceTyped<AlarmManager>(Context.ALARM_SERVICE)

    /**
     * JobService used for job that waits until device is connected to a charger to remove recharge lockTimeLock
     */
    class DisableTillRechargeJobService : JobService() {
        override fun onStartJob(jobParameters: JobParameters): Boolean {
            unlockRechargeLock(this)
            return false
        }

        override fun onStopJob(jobParameters: JobParameters): Boolean = true
    }
}