package com.adsamcik.tracker.testing.fake

import android.content.Context
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.LockResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fake implementation of LockManager for testing.
 *
 * Contract:
 * - Input: Test code calls lock/unlock methods to simulate lock state changes
 * - Output: Reactive StateFlow emits test-controlled values
 * - Thread-safety: MutableStateFlow is thread-safe
 * - Lifecycle: Test-scoped (create new instance per test)
 *
 * Usage:
 * ```kotlin
 * val fakeLockManager = FakeLockManager()
 * val testGraph = TestAppGraphBuilder()
 *     .withLockManager(fakeLockManager)
 *     .build()
 *
 * // Simulate lock engagement
 * fakeLockManager.setTimeLocked(true)
 *
 * // Assert UI state
 * composeTestRule.onNodeWithText("Tracking Locked").assertExists()
 * ```
 */
class FakeLockManager : LockManager {

    private val _isLockedFlow = MutableStateFlow(false)
    override val isLockedFlow: StateFlow<Boolean> get() = _isLockedFlow

    override val isLocked: Boolean
        get() = _isLockedFlow.value

    private var _isTimeLocked = false
    override val isTimeLocked: Boolean
        get() = _isTimeLocked

    private var _isChargeLocked = false
    override val isChargeLocked: Boolean
        get() = _isChargeLocked

    override suspend fun initializeFromPersistence(context: Context) {
        // No-op in fake (tests control state directly)
    }

    override fun lockUntilRecharge(context: Context) {
        _isChargeLocked = true
        updateCombinedLockState()
    }

    override fun unlockRechargeLock(context: Context) {
        _isChargeLocked = false
        updateCombinedLockState()
    }

    override fun lockTimeLock(context: Context, lockTimeInMillis: Long): LockResult {
        _isTimeLocked = true
        updateCombinedLockState()
        return LockResult.Locked
    }

    override fun unlockTimeLock(context: Context) {
        _isTimeLocked = false
        updateCombinedLockState()
    }

    override fun unlock(context: Context) {
        _isTimeLocked = false
        _isChargeLocked = false
        updateCombinedLockState()
    }

    /**
     * Test helper: Directly set time lock state without context.
     */
    fun setTimeLocked(locked: Boolean) {
        _isTimeLocked = locked
        updateCombinedLockState()
    }

    /**
     * Test helper: Directly set charge lock state without context.
     */
    fun setChargeLocked(locked: Boolean) {
        _isChargeLocked = locked
        updateCombinedLockState()
    }

    private fun updateCombinedLockState() {
        _isLockedFlow.value = _isTimeLocked || _isChargeLocked
    }
}
