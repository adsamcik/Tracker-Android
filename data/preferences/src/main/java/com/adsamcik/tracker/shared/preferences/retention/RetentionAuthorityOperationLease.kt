package com.adsamcik.tracker.shared.preferences.retention

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes collected-data lifecycle transitions with retention bootstrap, reads, and writes.
 *
 * Lock order is: any already-owned startup/configuration authority, this lease, DataStore, then
 * Room. Room transactions must use only Room state and CAS; they must never call back into
 * DataStore or acquire this lease.
 */
class RetentionAuthorityOperationLease {
	private val mutex = Mutex()

	suspend fun <T> withOperation(operation: suspend () -> T): T =
		mutex.withLock { operation() }
}
