package com.adsamcik.tracker.shared.base.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupException
import java.io.IOException

internal fun Exception.classifiedActiveDatabaseOpenFailure(state: ActiveDatabasePreflightResult): Exception =
	if (this is ActiveDatabaseOpenBlockedException || this is ActiveDatabaseOpenRetryableException) {
		this
	} else {
		classifiedUnrecognizedActiveDatabaseOpenFailure(state)
	}

private fun Exception.classifiedUnrecognizedActiveDatabaseOpenFailure(state: ActiveDatabasePreflightResult): Exception {
	if (isActiveDatabaseCorruption()) {
		return ActiveDatabaseOpenBlockedException(
			ActiveDatabaseBlockReason.UNREADABLE_DATABASE,
			this,
		)
	}
	activeDatabaseRetryableReason()?.let { reason ->
		return ActiveDatabaseOpenRetryableException(reason, this)
	}
	if (this is DatabaseMigrationBackupException) {
		return this
	}
	val reason = when (state) {
		ActiveDatabasePreflightResult.KnownDevelopmentV27 ->
			ActiveDatabaseBlockReason.DEVELOPMENT_V27_MIGRATION_VALIDATION_FAILED
		ActiveDatabasePreflightResult.FinalV28 ->
			ActiveDatabaseBlockReason.FINAL_V28_OPEN_VALIDATION_FAILED
		ActiveDatabasePreflightResult.FinalV29 ->
			ActiveDatabaseBlockReason.FINAL_V29_OPEN_VALIDATION_FAILED
		else -> null
	}
	return if (reason != null) {
		ActiveDatabaseOpenBlockedException(reason, this)
	} else {
		this
	}
}

internal fun Throwable.activeDatabaseRetryableReason(): ActiveDatabaseRetryableReason? {
	var current: Throwable? = this
	while (current != null) {
		current.directActiveDatabaseRetryableReason()?.let { return it }
		current = current.cause
	}
	return null
}

private fun Throwable.directActiveDatabaseRetryableReason(): ActiveDatabaseRetryableReason? {
	if (this is SQLiteDatabaseLockedException) {
		return ActiveDatabaseRetryableReason.CONTENDED
	}
	val className = javaClass.name
	if (className in LOCK_EXCEPTION_CLASS_NAMES || className.endsWith("SQLiteDatabaseLockedException")) {
		return ActiveDatabaseRetryableReason.CONTENDED
	}
	val message = this.message?.lowercase().orEmpty()
	val sqliteFailure = isActiveDatabaseSqliteFailure(className)
	return when {
		sqliteFailure && LOCK_MESSAGE_MARKERS.any(message::contains) -> ActiveDatabaseRetryableReason.CONTENDED
		isActiveDatabaseOperationalFailure(className, sqliteFailure, message) ->
			ActiveDatabaseRetryableReason.OPERATIONALLY_UNAVAILABLE
		else -> null
	}
}

private fun Throwable.isActiveDatabaseOperationalFailure(
	className: String, sqliteFailure: Boolean, message: String,
): Boolean = this is SQLiteCantOpenDatabaseException || this is SQLiteDiskIOException ||
	this is IOException || this is SecurityException || className in OPERATIONAL_EXCEPTION_CLASS_NAMES ||
	(sqliteFailure && OPERATIONAL_MESSAGE_MARKERS.any(message::contains))

private fun Throwable.isActiveDatabaseSqliteFailure(className: String): Boolean = this is SQLiteException ||
	(className.startsWith("org.sqlite.database.sqlite.SQLite") && className.endsWith("Exception"))

internal fun Throwable.isActiveDatabaseCorruption(): Boolean {
	var current: Throwable? = this
	while (current != null) {
		val className = current.javaClass.name
		if (className in CORRUPTION_EXCEPTION_CLASS_NAMES ||
			className.endsWith("SQLiteDatabaseCorruptException")
		) {
			return true
		}
		val message = current.message?.lowercase().orEmpty()
		val sqliteFailure = current is SQLiteException ||
			(className.startsWith("org.sqlite.database.sqlite.SQLite") &&
				className.endsWith("Exception"))
		if (sqliteFailure && CORRUPTION_MESSAGE_MARKERS.any(message::contains)) {
			return true
		}
		current = current.cause
	}
	return false
}

private val LOCK_EXCEPTION_CLASS_NAMES = setOf(
	"android.database.sqlite.SQLiteBusyException",
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
private val OPERATIONAL_EXCEPTION_CLASS_NAMES = setOf(
	"android.database.sqlite.SQLiteCantOpenDatabaseException",
	"android.database.sqlite.SQLiteDiskIOException",
	"android.database.sqlite.SQLiteFullException",
	"android.database.sqlite.SQLiteReadOnlyDatabaseException",
	"org.sqlite.database.sqlite.SQLiteCantOpenDatabaseException",
	"org.sqlite.database.sqlite.SQLiteDiskIOException",
	"org.sqlite.database.sqlite.SQLiteFullException",
	"org.sqlite.database.sqlite.SQLiteReadOnlyDatabaseException",
)
private val OPERATIONAL_MESSAGE_MARKERS = listOf(
	"unable to open database file",
	"disk i/o error",
	"permission denied",
	"readonly database",
	"read-only database",
	"temporarily unavailable",
)
private val CORRUPTION_EXCEPTION_CLASS_NAMES = setOf(
	"android.database.sqlite.SQLiteDatabaseCorruptException",
	"org.sqlite.database.sqlite.SQLiteDatabaseCorruptException",
)
private val CORRUPTION_MESSAGE_MARKERS = listOf(
	"database disk image is malformed",
	"file is not a database",
	"not a database",
	"malformed database schema",
	"database corrupt",
	"database corruption",
)

