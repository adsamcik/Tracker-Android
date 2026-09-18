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

	suspend fun <T> withPermit(
		operation: suspend (RetentionAuthorityOperationPermit) -> T,
	): T = mutex.withLock {
		operation(RetentionAuthorityOperationPermit(this))
	}

	internal fun requireOwned(permit: RetentionAuthorityOperationPermit) {
		require(permit.owner === this) {
			"Retention authority operation permit belongs to another lifecycle boundary"
		}
	}
}

class RetentionAuthorityOperationPermit internal constructor(
	internal val owner: RetentionAuthorityOperationLease,
)
