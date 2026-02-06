package com.adsamcik.tracker.tracker.controller

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Controller interface for tracking lock state management.
 * 
 * Provides reactive access to lock state and operations without static dependencies.
 * Injected via AppGraph for testability and proper dependency management.
 * 
 * Per copilot-instructions Section 16A:
 * - Replaces TrackerLocker static object state
 * - Enables test injection (fake manager for UI tests)
 * - Maintains clean module boundaries
 */
interface LockManager {
    /**
     * Observable combined lock state (time lock OR charge lock).
     * Emits true if any lock is currently engaged.
     */
    val isLockedFlow: StateFlow<Boolean>
    
    /**
     * Current combined lock state (snapshot).
     * True if any lock is currently engaged.
     */
    val isLocked: Boolean
    
    /**
     * True if time lock is active (current time < unlock time).
     */
    val isTimeLocked: Boolean
    
    /**
     * True if charge lock is active (locked until device recharge).
     */
    val isChargeLocked: Boolean
    
    /**
     * Initialize lock state from persistent storage.
     * Called during app startup to restore locks across sessions.
     */
    suspend fun initializeFromPersistence(context: Context)
    
    /**
     * Locks tracking until device is connected to charger.
     * Schedules WorkManager job that auto-unlocks when charging + battery not low.
     */
    fun lockUntilRecharge(context: Context)
    
    /**
     * Removes recharge lock.
     * Cancels pending WorkManager unlock job.
     */
    fun unlockRechargeLock(context: Context)
    
    /**
     * Sets time-based lock for specified duration.
     * Cannot be locked for less than 1 second.
     * Schedules AlarmManager broadcast to auto-unlock.
     *
     * @param lockTimeInMillis Duration to lock (must be >= 1000ms)
     * @return [LockResult.Locked] on success, or a specific rejection reason
     */
    fun lockTimeLock(context: Context, lockTimeInMillis: Long): LockResult
    
    /**
     * Unlocks active time lock.
     * Cancels pending AlarmManager broadcast.
     */
    fun unlockTimeLock(context: Context)
    
    /**
     * Unlocks all locks (time + recharge).
     * Convenience method for batch unlock.
     */
    fun unlock(context: Context)
}
