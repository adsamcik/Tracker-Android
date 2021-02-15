package com.adsamcik.tracker.logger

import androidx.room.Database
import androidx.room.RoomDatabase
import com.adsamcik.tracker.shared.base.database.ObjectBaseDatabase

/**
 * Database for logging
 */
@Database(
		entities = [LogData::class],
		version = 1
)
abstract class LogDatabase : RoomDatabase() {

	/**
	 * Generic log DAO
	 */
	abstract fun genericLogDao(): GenericLogDao

	companion object : ObjectBaseDatabase<LogDatabase>(LogDatabase::class.java) {
		override val databaseName: String
			get() = "debug_database"

		override fun setupDatabase(database: Builder<LogDatabase>) = Unit
	}
}
