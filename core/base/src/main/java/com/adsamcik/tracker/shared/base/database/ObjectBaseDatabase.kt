package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.annotation.AnyThread
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLiteOpenHelperFactory

/**
 * Base class for Database Object.
 */
abstract class ObjectBaseDatabase<T : RoomDatabase>(private val type: Class<T>) {
	abstract val databaseName: String

	protected var instance: T? = null
		private set

	protected abstract fun setupDatabase(database: RoomDatabase.Builder<T>)

	protected open fun openHelperFactory(
		context: Context,
		delegate: SupportSQLiteOpenHelper.Factory,
	): SupportSQLiteOpenHelper.Factory = delegate

	private fun createInstance(context: Context): T {
		val delegateFactory = SQLiteXSupportSQLiteOpenHelperFactory()
		val instance = Room.databaseBuilder(
				context.applicationContext,
				type,
				databaseName
		)
				.openHelperFactory(openHelperFactory(context.applicationContext, delegateFactory))
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
