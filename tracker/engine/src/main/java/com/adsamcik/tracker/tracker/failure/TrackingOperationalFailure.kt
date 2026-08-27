package com.adsamcik.tracker.tracker.failure

import android.database.sqlite.SQLiteBindOrColumnIndexOutOfRangeException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatatypeMismatchException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteMisuseException
import java.io.IOException
import java.io.UncheckedIOException

/**
 * True only for storage failures that tracking can legitimately expose as retry/recovery outcomes.
 *
 * Room delegates database work to framework SQLite or the shipped org.sqlite Android binding. Both
 * report environmental storage failures through their SQLite exception families. Known contract,
 * query-shape, and constraint exceptions remain programmer/data-integrity failures and propagate.
 */
internal fun Throwable.isTrackingOperationalFailure(): Boolean {
	val visited = mutableSetOf<Throwable>()
	var current: Throwable? = this
	while (current != null && visited.add(current)) {
		when (current) {
			is IOException,
			is UncheckedIOException,
			-> return true
			is SQLiteException -> return !current.isProgrammerSQLiteFailure()
		}
		val className = current.javaClass.name
		if (className.startsWith(VENDOR_SQLITE_PREFIX) && className.endsWith("Exception")) {
			return PROGRAMMER_SQLITE_CLASS_MARKERS.none(className::contains)
		}
		current = current.cause
	}
	return false
}

private fun SQLiteException.isProgrammerSQLiteFailure(): Boolean =
	this is SQLiteBindOrColumnIndexOutOfRangeException ||
		this is SQLiteConstraintException ||
		this is SQLiteDatatypeMismatchException ||
		this is SQLiteMisuseException

private const val VENDOR_SQLITE_PREFIX = "org.sqlite.database.sqlite.SQLite"

private val PROGRAMMER_SQLITE_CLASS_MARKERS = listOf(
	"BindOrColumnIndexOutOfRange",
	"Constraint",
	"DatatypeMismatch",
	"Misuse",
)
