package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.annotation.AnyThread
import androidx.room.Room
import androidx.room.RoomDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration

/**
 * Base class for Database Object.
 */
abstract class ObjectBaseDatabase<T : RoomDatabase>(private val type: Class<T>) {
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
				// Enable WAL for improved concurrent read/write performance and reduced writer stalls
				.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
				.apply { setupDatabase(this) }
				.build()
		this.instance = instance
		return instance
	}

	/**
	 * Returns database instance
	 */
	@AnyThread
	@Synchronized
	fun database(context: Context): T {
		return instance ?: createInstance(context)
	}

	/**
	 * Returns database instance for testing.
	 */
	fun testDatabase(context: Context): T {
		return Room.inMemoryDatabaseBuilder(
				context.applicationContext,
				type
		)
				// Tests run on JVM / Robolectric main thread; allow main thread queries to simplify
				// synchronous DAO access. Production database keeps default threading safeguards.
				.allowMainThreadQueries()
				.build()
	}
}
