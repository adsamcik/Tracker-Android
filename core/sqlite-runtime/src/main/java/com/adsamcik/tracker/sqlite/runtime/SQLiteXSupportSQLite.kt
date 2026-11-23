package com.adsamcik.tracker.sqlite.runtime

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.IOException
import org.sqlite.database.DatabaseErrorHandler
import org.sqlite.database.sqlite.SQLiteDatabase as VendorSQLiteDatabase

/** Direct existing-file opens using the same native SQLite engine as Tracker databases. */
public object SQLiteXSupportSQLite {

	/**
	 * Opens [path] with the binding's `OPEN_READONLY` flag and exposes only the
	 * AndroidX SupportSQLiteDatabase interface.
	 */
	@JvmStatic
	public fun openReadOnly(path: String): SupportSQLiteDatabase {
		SQLiteXRuntime.ensureLoaded()
		return SQLiteXSupportSQLiteDatabase(
			VendorSQLiteDatabase.openDatabase(
				path,
				null,
				VendorSQLiteDatabase.OPEN_READONLY,
				preserveFilesOnCorruption,
			),
		)
	}

    /** Opens an initialized existing file with the same engine and persistent journal mode as live Room. */
    public fun openExistingReadWrite(path: String): SupportSQLiteDatabase {
        val file = File(path)
        if (!file.isFile || file.length() == 0L) {
            throw IOException("An initialized database is required")
        }
        val journalMode = openReadOnly(path).use { database ->
            database.query("PRAGMA journal_mode").use { row ->
                check(row.moveToFirst()) { "Database journal mode is unavailable" }
                row.getString(0)
            }
        }
        check(journalMode == "wal" || journalMode == "delete") { "Unsupported database journal mode" }
        val flags = VendorSQLiteDatabase.OPEN_READWRITE or if (journalMode == "wal") {
            VendorSQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        } else {
            0
        }
        return SQLiteXSupportSQLiteDatabase(VendorSQLiteDatabase.openDatabase(
            path, null, flags, preserveFilesOnCorruption))
    }

    private val preserveFilesOnCorruption = DatabaseErrorHandler { /* Caller retains the original files. */ }
}
