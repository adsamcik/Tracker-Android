package com.adsamcik.tracker.logger.database

import android.content.Context
import androidx.annotation.AnyThread
import androidx.room.Room
import androidx.room.RoomDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration

/**
 * Logger-internal base class for database lifecycle management.
 */
abstract class LoggerObjectDatabase<T : RoomDatabase>(private val type: Class<T>) {
	abstract val databaseName: String

	protected var instance: T? = null
		private set

	protected abstract fun setupDatabase(database: RoomDatabase.Builder<T>)

	private fun createInstance(context: Context): T {
		val configuration = SQLiteDatabaseConfiguration(
			context.getDatabasePath(databaseName).path,
			SQLiteDatabase.OPEN_CREATE or SQLiteDatabase.OPEN_READWRITE
		)
		val options = RequerySQLiteOpenHelperFactory.ConfigurationOptions { configuration }
		val instance = Room.databaseBuilder(
			context.applicationContext,
			type,
			databaseName
		)
			.openHelperFactory(RequerySQLiteOpenHelperFactory(listOf(options)))
			.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
			.apply { setupDatabase(this) }
			.build()
		this.instance = instance
		return instance
	}

	@AnyThread
	@Synchronized
	fun database(context: Context): T {
		return instance ?: createInstance(context)
	}

	fun testDatabase(context: Context): T {
		return Room.inMemoryDatabaseBuilder(
			context.applicationContext,
			type
		)
			.allowMainThreadQueries()
			.build()
	}
}
