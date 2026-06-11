package com.adsamcik.tracker.tracker.controller

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.alarmManager
import com.adsamcik.tracker.shared.base.extension.stopService
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.locker.DisableTillRechargeWorker
import com.adsamcik.tracker.tracker.receiver.TrackerTimeUnlockReceiver
import com.adsamcik.tracker.tracker.service.ActivityWatcherServiceController
import com.adsamcik.tracker.tracker.service.TrackerService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Default implementation of LockManager.
 * 
 * Contract:
 * - Input: TrackerServiceController for session state monitoring
 * - Output: Reactive StateFlow for lock state + lock/unlock operations
 * - Thread-safety: Synchronized blocks protect mutable state
 * - Lifecycle: Application-scoped singleton (wired in AppGraph)
 * 
 * Manages two lock types:
 * 1. Time lock: Expires after specified duration (AlarmManager-based)
 * 2. Recharge lock: Expires when device charges (WorkManager-based)
 * 
 * Persists lock state to DataStore for cross-session restoration.
 */
class DefaultLockManager(
    private val trackerServiceController: TrackerServiceController,
    private val activityWatcherController: ActivityWatcherServiceController,
) : LockManager {
    private val persistenceInitialized = AtomicBoolean(false)

    
    /**
     * WorkManager tag for recharge lock job.
     */
    private val workDisableTillRechargeTag: String = "disableTillRecharge"
    
    // Locking order: lockedUntilTime, lockedUntilRecharge
    private var lockedUntilTime: Long = 0
    private var lockedUntilRecharge = false
    
    private val _isLockedFlow = MutableStateFlow(false)
    override val isLockedFlow: StateFlow<Boolean> get() = _isLockedFlow
    
    override val isLocked: Boolean
        get() = isLockedRightNow()
    
    override val isTimeLocked: Boolean
        get() = Time.nowMillis < lockedUntilTime
    
    override val isChargeLocked: Boolean
        get() = lockedUntilRecharge
    
    private fun isLockedRightNow(): Boolean {
        synchronized(this) {
            return isTimeLocked.or(isChargeLocked)
        }
    }
    
    override suspend fun initializeFromPersistence(context: Context) {
        if (!persistenceInitialized.compareAndSet(false, true)) return

        try {
            val preferences = Preferences(context)
            val timeKey = context.getString(R.string.settings_disabled_time_key)
            val timeDefault = context.getString(R.string.settings_disabled_time_default).toLong()
            val persistedTime = preferences.fetchLong(timeKey, timeDefault)
            val persistedRecharge = preferences.fetchBooleanRes(
                R.string.settings_disabled_recharge_key,
                R.string.settings_disabled_recharge_default
            )

            synchronized(this) {
                lockedUntilTime = persistedTime
                lockedUntilRecharge = persistedRecharge
            }

            // AlarmManager alarms do not survive a reboot. If a time lock is still
            // in the future, re-register it so it fires its unlock (and re-pokes the
            // watcher to resume auto-tracking) at the correct moment after a restart.
            if (Time.nowMillis < persistedTime) {
                scheduleTimeUnlockAlarm(context, persistedTime)
            }

            refreshLockState(context)
        } catch (t: Throwable) {
            persistenceInitialized.set(false)
            throw t
        }
    }
    
    override fun lockUntilRecharge(context: Context) {
        synchronized(this) {
            val workManager = WorkManager.getInstance(context)
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
            val work = OneTimeWorkRequestBuilder<DisableTillRechargeWorker>()
                .setConstraints(constraints)
                .addTag(workDisableTillRechargeTag)
                .build()
            workManager.enqueue(work)
            setRechargeLock(context, true)
        }
    }
    
    override fun unlockRechargeLock(context: Context) {
        synchronized(this) {
            WorkManager.getInstance(context).cancelAllWorkByTag(workDisableTillRechargeTag)
            setRechargeLock(context, false)
        }
    }
    
    override fun lockTimeLock(context: Context, lockTimeInMillis: Long): LockResult {
        synchronized(this) {
            if (lockTimeInMillis < Time.SECOND_IN_MILLISECONDS) {
                return LockResult.DurationTooShort
            }

            val lockUntilTime = Time.nowMillis + lockTimeInMillis
            if (lockUntilTime <= this.lockedUntilTime) {
                return LockResult.AlreadyLockedLonger
            }

            setTimeLock(context, lockUntilTime)
            scheduleTimeUnlockAlarm(context, lockUntilTime)
            return LockResult.Locked
        }
    }
    
    override fun unlockTimeLock(context: Context) {
        synchronized(this) {
            context.alarmManager.cancel(getTimeUnlockBroadcastIntent(context))
            setTimeLock(context, 0)
            
            activityWatcherController.poke(watcherPreference = isLockedRightNow())
        }
    }
    
    override fun unlock(context: Context) {
        synchronized(this) {
            unlockRechargeLock(context)
            unlockTimeLock(context)
        }
    }
    
    private fun setRechargeLock(context: Context, lock: Boolean) {
        val keyDisabledRecharge = context.getString(R.string.settings_disabled_recharge_key)
        synchronized(this) {
            Preferences(context).edit {
                setBoolean(keyDisabledRecharge, lock)
            }
            
            lockedUntilRecharge = lock
            
            refreshLockState(context)
        }
    }
    
    private fun setTimeLock(context: Context, time: Long) {
        synchronized(this) {
            Preferences(context).edit {
                setLong(R.string.settings_disabled_time_key, time)
            }
            
            lockedUntilTime = time
            
            refreshLockState(context)
        }
    }
    
    private fun refreshLockState(context: Context) {
        val isLockedRightNow = isLockedRightNow()
        if (isLockedRightNow != _isLockedFlow.value) {
            _isLockedFlow.value = isLockedRightNow
        }
        
        pokeWatcherService(context)
        
        // Stop non-user-initiated tracking sessions when locked
        val sessionInfo = trackerServiceController.sessionInfoFlow.value
        if (isLockedRightNow && sessionInfo?.isInitiatedByUser == false) {
            context.stopService<TrackerService>()
        }
    }
    
    private fun pokeWatcherService(context: Context) {
        activityWatcherController.poke(trackerLocked = isLockedRightNow())
    }
    
    private fun getTimeUnlockBroadcastIntent(context: Context): PendingIntent {
        val intent = Intent(context, TrackerTimeUnlockReceiver::class.java)
        return PendingIntent.getBroadcast(context, 0, intent, FLAG_IMMUTABLE)
    }

    private fun scheduleTimeUnlockAlarm(context: Context, lockUntilTime: Long) {
        context.alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            lockUntilTime,
            getTimeUnlockBroadcastIntent(context)
        )
    }
}
