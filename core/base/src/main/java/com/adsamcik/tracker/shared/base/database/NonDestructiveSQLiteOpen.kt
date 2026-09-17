package com.adsamcik.tracker.shared.base.database

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Opens an existing SQLite file without Android's default corruption handler, which may delete the
 * database and its sidecars. Callers remain responsible for classifying and surfacing failures.
 */
internal fun openDatabasePreservingFiles(
	file: File,
	flags: Int,
): SQLiteDatabase = SQLiteDatabase.openDatabase(
	file.path,
	null,
	flags,
	NON_DESTRUCTIVE_DATABASE_ERROR_HANDLER,
)

private val NON_DESTRUCTIVE_DATABASE_ERROR_HANDLER = object : DatabaseErrorHandler {
	override fun onCorruption(dbObj: SQLiteDatabase) = Unit
}
