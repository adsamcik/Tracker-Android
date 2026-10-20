package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * A finite read-only snapshot on the existing SupportSQLite pool, without Room's serial
 * writer executor. Pin the native transaction and its plain DAO queries to one IO thread.
 * Keep the caller's Job: Room's outer useReaderConnection substitutes the database Job.
 * Callers must not switch dispatchers inside [block] or perform writes. Finalization
 * stays on its owning writer thread and sees that transaction's uncommitted changes.
 */
internal suspend fun <T> AppDatabase.withSavedLocationReadSnapshot(
    io: CoroutineDispatcher,
    block: suspend () -> T,
): T {
    assertNotSuspendingTransaction()
    if (inTransaction()) {
        return block()
    }
    return withContext(io) {
        runBlocking(currentCoroutineContext().minusKey(ContinuationInterceptor)) {
            val sqlite = openHelper.readableDatabase
            sqlite.beginTransactionReadOnly()
            try {
                block().also { sqlite.setTransactionSuccessful() }
            } finally {
                sqlite.endTransaction()
            }
        }
    }
}
