package com.adsamcik.tracker.tracker.pipeline.persistence

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import javax.inject.Inject

/**
 * Runs a block of suspending database work inside a single Room transaction.
 *
 * The persistence pipeline uses this to make destination-table writes and the
 * acknowledgement (deletion) of the exact `pending_signal` WAL rows atomic: if
 * [inTransaction]'s block throws — whether an insert fails or the coroutine is
 * cancelled — Room rolls the whole transaction back, so neither the destination
 * rows nor the WAL deletion are committed and the signals remain replayable.
 *
 * Extracted as an interface so tests can supply an in-memory implementation
 * without a real database.
 */
interface TrackingPersistenceTransactor {
	/**
	 * Execute [block] inside a database transaction, returning its result.
	 * Any exception thrown by [block] propagates and rolls the transaction back.
	 */
	suspend fun <R> inTransaction(block: suspend () -> R): R
}

/**
 * Default [TrackingPersistenceTransactor] backed by [AppDatabase.withTransaction].
 */
class RoomPersistenceTransactor @Inject constructor(
	private val database: AppDatabase,
) : TrackingPersistenceTransactor {
	override suspend fun <R> inTransaction(block: suspend () -> R): R =
		database.withTransaction { block() }
}
