package com.adsamcik.tracker.sqlite.runtime

import androidx.sqlite.db.SupportSQLiteDatabase
import org.sqlite.database.sqlite.SQLiteDatabase as VendorSQLiteDatabase

/** Small direct-open utility for consumers that need a strictly read-only database handle. */
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
			),
		)
	}
}
