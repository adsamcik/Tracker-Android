package com.adsamcik.tracker.shared.base.database.legacy

import android.content.Context
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLiteOpenHelperFactory

/** Runs the one-shot legacy copy inside Room's atomic fresh-database creation transaction. */
class LegacyImportRoomCallback(
	context: Context,
	publicMigrations: Array<Migration>,
	normalizerOpenHelperFactory: SupportSQLiteOpenHelper.Factory =
		SQLiteXSupportSQLiteOpenHelperFactory(),
) : RoomDatabase.Callback() {
	private val importer = LegacyV26Importer(
		context.applicationContext,
		publicMigrations,
		normalizerOpenHelperFactory,
	)

	override fun onCreate(db: SupportSQLiteDatabase) {
		importer.importIfPresent(db)
		db.execSQL(
			"INSERT OR IGNORE INTO source_destination_owner " +
				"(source_kind, destination, owner, owner_generation, updated_at_ms) " +
				"VALUES (3, 'SESSION_STEPS', 'LEGACY_STEP_INTERVAL', 1, 0)",
		)
	}
}
