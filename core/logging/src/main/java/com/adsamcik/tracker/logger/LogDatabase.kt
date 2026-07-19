package com.adsamcik.tracker.logger

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.logger.database.LoggerObjectDatabase

/**
 * Database for logging
 */
@Database(
		entities = [LogData::class, CrashData::class],
		version = 2
)
abstract class LogDatabase : RoomDatabase() {

	/**
	 * Generic log DAO
	 */
	abstract fun genericLogDao(): GenericLogDao

	/**
	 * Crash data DAO
	 */
	abstract fun crashDataDao(): CrashDataDao

	companion object : LoggerObjectDatabase<LogDatabase>(LogDatabase::class.java) {
		override val databaseName: String
			get() = "debug_database"

		override fun setupDatabase(database: Builder<LogDatabase>) {
			database.addMigrations(*migrations)
		}

		val MIGRATION_1_2: Migration = object : Migration(1, 2) {
			override fun migrate(db: SupportSQLiteDatabase) {
				db.execSQL(
					"""
					CREATE TABLE IF NOT EXISTS crash_data (
						timeStamp INTEGER NOT NULL,
						exceptionName TEXT NOT NULL,
						exceptionMessage TEXT NOT NULL,
						stackTrace TEXT NOT NULL,
						cause TEXT,
						threadName TEXT NOT NULL,
						appVersion TEXT NOT NULL,
						androidVersion TEXT NOT NULL,
						deviceModel TEXT NOT NULL,
						deviceManufacturer TEXT NOT NULL,
						availableMemory INTEGER NOT NULL,
						totalMemory INTEGER NOT NULL,
						batteryLevel REAL NOT NULL,
						isCharging INTEGER NOT NULL,
						networkType TEXT NOT NULL,
						isInBackground INTEGER NOT NULL,
						id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
					)
					""".trimIndent()
				)
			}
		}

		internal val migrations = arrayOf(MIGRATION_1_2)
	}
}
