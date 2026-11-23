package com.adsamcik.tracker.sqlite.runtime

import android.database.SQLException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteAbortException
import android.database.sqlite.SQLiteBindOrColumnIndexOutOfRangeException
import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatatypeMismatchException
import android.database.sqlite.SQLiteDoneException
import android.database.sqlite.SQLiteMisuseException
import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.database.sqlite.SQLiteTableLockedException
import org.sqlite.database.SQLException as VendorSQLException
import org.sqlite.database.sqlite.SQLiteAbortException as VendorSQLiteAbortException
import org.sqlite.database.sqlite.SQLiteBindOrColumnIndexOutOfRangeException as VendorSQLiteBindOrColumnIndexOutOfRangeException
import org.sqlite.database.sqlite.SQLiteBlobTooBigException as VendorSQLiteBlobTooBigException
import org.sqlite.database.sqlite.SQLiteConstraintException as VendorSQLiteConstraintException
import org.sqlite.database.sqlite.SQLiteDatatypeMismatchException as VendorSQLiteDatatypeMismatchException
import org.sqlite.database.sqlite.SQLiteDoneException as VendorSQLiteDoneException
import org.sqlite.database.sqlite.SQLiteMisuseException as VendorSQLiteMisuseException
import org.sqlite.database.sqlite.SQLiteAccessPermException as VendorSQLiteAccessPermException
import org.sqlite.database.sqlite.SQLiteCantOpenDatabaseException as VendorSQLiteCantOpenDatabaseException
import org.sqlite.database.sqlite.SQLiteDatabaseCorruptException as VendorSQLiteDatabaseCorruptException
import org.sqlite.database.sqlite.SQLiteDatabaseLockedException as VendorSQLiteDatabaseLockedException
import org.sqlite.database.sqlite.SQLiteDiskIOException as VendorSQLiteDiskIOException
import org.sqlite.database.sqlite.SQLiteFullException as VendorSQLiteFullException
import org.sqlite.database.sqlite.SQLiteOutOfMemoryException as VendorSQLiteOutOfMemoryException
import org.sqlite.database.sqlite.SQLiteReadOnlyDatabaseException as VendorSQLiteReadOnlyDatabaseException
import org.sqlite.database.sqlite.SQLiteTableLockedException as VendorSQLiteTableLockedException

/** Keeps the SupportSQLite failure contract while preserving the original native cause and message. */
internal inline fun <T> sqliteXCall(operation: () -> T): T = try {
	operation()
} catch (failure: VendorSQLException) {
	throw frameworkSQLiteFailure(failure)
}

/** Used only on the failure path of the inline SupportSQLite adapter. */
@PublishedApi
internal fun frameworkSQLiteFailure(failure: VendorSQLException): SQLException {
	val translated = shapeFailure(failure) ?: operationalFailure(failure) ?: SQLiteException(failure.message)
	return translated.apply { initCause(failure) }
}

private fun shapeFailure(failure: VendorSQLException): SQLiteException? = when (failure) {
	is VendorSQLiteAbortException -> SQLiteAbortException(failure.message)
	is VendorSQLiteBindOrColumnIndexOutOfRangeException -> SQLiteBindOrColumnIndexOutOfRangeException(failure.message)
	is VendorSQLiteBlobTooBigException -> SQLiteBlobTooBigException(failure.message)
	is VendorSQLiteConstraintException -> SQLiteConstraintException(failure.message)
	is VendorSQLiteDatatypeMismatchException -> SQLiteDatatypeMismatchException(failure.message)
	is VendorSQLiteDoneException -> SQLiteDoneException(failure.message)
	is VendorSQLiteMisuseException -> SQLiteMisuseException(failure.message)
	else -> null
}

private fun operationalFailure(failure: VendorSQLException): SQLiteException? = when (failure) {
	is VendorSQLiteAccessPermException -> SQLiteAccessPermException(failure.message)
	is VendorSQLiteCantOpenDatabaseException -> SQLiteCantOpenDatabaseException(failure.message)
	is VendorSQLiteDatabaseCorruptException -> SQLiteDatabaseCorruptException(failure.message)
	is VendorSQLiteDatabaseLockedException -> SQLiteDatabaseLockedException(failure.message)
	else -> resourceFailure(failure)
}

private fun resourceFailure(failure: VendorSQLException): SQLiteException? = when (failure) {
	is VendorSQLiteDiskIOException -> SQLiteDiskIOException(failure.message)
	is VendorSQLiteFullException -> SQLiteFullException(failure.message)
	is VendorSQLiteOutOfMemoryException -> SQLiteOutOfMemoryException(failure.message)
	is VendorSQLiteReadOnlyDatabaseException -> SQLiteReadOnlyDatabaseException(failure.message)
	is VendorSQLiteTableLockedException -> SQLiteTableLockedException(failure.message)
	else -> null
}
