package com.adsamcik.tracker.tracker.data

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.util.Log
import kotlinx.coroutines.delay

private const val TAG = "DatabaseRetry"

/**
 * Retry wrapper for transient database failures (SQLITE_BUSY, lock contention).
 * Uses exponential backoff with jitter.
 */
internal suspend fun <T> withDatabaseRetry(
	maxAttempts: Int = 3,
	initialDelayMs: Long = 50,
	maxDelayMs: Long = 500,
	block: suspend () -> T
): T {
	require(maxAttempts >= 1) { "maxAttempts must be >= 1" }

	var lastException: Throwable? = null
	var currentDelay = initialDelayMs

	repeat(maxAttempts) { attempt ->
		try {
			return block()
		} catch (e: SQLiteDatabaseLockedException) {
			lastException = e
			if (attempt < maxAttempts - 1) {
				val jitter = (0..currentDelay / 2).random()
				val waitMs = (currentDelay + jitter).coerceAtMost(maxDelayMs)
				Log.w(TAG, "Transient DB lock (attempt ${attempt + 1}/$maxAttempts), retrying in ${waitMs}ms", e)
				delay(waitMs)
				currentDelay = (currentDelay * 2).coerceAtMost(maxDelayMs)
			}
		} catch (e: SQLiteException) {
			if (e.message?.contains("database is locked") == true) {
				lastException = e
				if (attempt < maxAttempts - 1) {
					val jitter = (0..currentDelay / 2).random()
					val waitMs = (currentDelay + jitter).coerceAtMost(maxDelayMs)
					Log.w(TAG, "Transient DB lock (attempt ${attempt + 1}/$maxAttempts), retrying in ${waitMs}ms", e)
					delay(waitMs)
					currentDelay = (currentDelay * 2).coerceAtMost(maxDelayMs)
				}
			} else {
				throw e
			}
		}
	}

	throw lastException!!
}
