package com.adsamcik.tracker.tracker.data

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.delay


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
	require(initialDelayMs >= 0L) { "initialDelayMs must be >= 0" }
	require(maxDelayMs >= 0L) { "maxDelayMs must be >= 0" }

	var lastException: Throwable? = null
	var currentDelay = initialDelayMs

	repeat(maxAttempts) { attempt ->
		try {
			return block()
		} catch (e: Exception) {
			if (!e.isRetryableDatabaseLock()) throw e
			lastException = e
			if (attempt < maxAttempts - 1) {
				val jitter = (0L..currentDelay / 2L).random()
				val waitMs = (currentDelay + jitter).coerceAtMost(maxDelayMs)
				delay(waitMs)
				currentDelay = (currentDelay * 2L).coerceAtMost(maxDelayMs)
			}
		}
	}

	lastException?.let { throw it } ?: error("Database retry exhausted without capturing an exception")
}

/** Recognizes both framework SQLite and the shipped org.sqlite Android binding. */
internal fun Throwable.isRetryableDatabaseLock(): Boolean {
	var current: Throwable? = this
	while (current != null) {
		if (current is SQLiteDatabaseLockedException) return true
		val className = current.javaClass.name
		if (className in LOCK_EXCEPTION_CLASS_NAMES || className.endsWith("SQLiteDatabaseLockedException")) {
			return true
		}
		val sqliteException = current is SQLiteException ||
			(className.startsWith("org.sqlite.database.sqlite.SQLite") && className.endsWith("Exception"))
		val message = current.message?.lowercase().orEmpty()
		if (sqliteException && LOCK_MESSAGE_MARKERS.any(message::contains)) return true
		current = current.cause
	}
	return false
}

private val LOCK_EXCEPTION_CLASS_NAMES = setOf(
	"android.database.sqlite.SQLiteDatabaseLockedException",
	"org.sqlite.database.sqlite.SQLiteDatabaseLockedException",
	"org.sqlite.database.sqlite.SQLiteBusyException",
)

private val LOCK_MESSAGE_MARKERS = listOf(
	"database is locked",
	"database table is locked",
	"sqlite_busy",
	"sqlite_locked",
)
