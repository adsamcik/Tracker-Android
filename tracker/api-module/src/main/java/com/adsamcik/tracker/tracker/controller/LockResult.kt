package com.adsamcik.tracker.tracker.controller

/**
 * Sealed result type for lock operations.
 *
 * Per coding standards Section 9: cross-module operations return sealed Result types.
 * Currently applies to [LockManager.lockTimeLock] which has meaningful rejection modes
 * that callers may want to handle (e.g. logging, user feedback).
 */
sealed class LockResult {
	/**
	 * Lock was successfully engaged.
	 */
	data object Locked : LockResult()

	/**
	 * Lock request was rejected because the requested duration is too short.
	 * Minimum duration is 1 second (1000 ms).
	 */
	data object DurationTooShort : LockResult()

	/**
	 * Lock request was rejected because the existing time lock already extends
	 * beyond the requested lock time. The current lock remains unchanged.
	 */
	data object AlreadyLockedLonger : LockResult()
}
