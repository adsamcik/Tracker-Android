package com.adsamcik.tracker.statistics.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.adsamcik.tracker.shared.base.database.ObjectBaseDatabase
import com.adsamcik.tracker.statistics.database.dao.StatsCacheDao
import com.adsamcik.tracker.statistics.database.data.CacheStatData

/**
 * Provides access to statistics database.
 */
@Database(version = 1, entities = [CacheStatData::class])
abstract class StatsDatabase : RoomDatabase() {

	/**
	 * Returns dao for statistics caching.
	 */
	abstract fun cacheDao(): StatsCacheDao

	companion object : ObjectBaseDatabase<StatsDatabase>(StatsDatabase::class.java) {
		override val databaseName: String get() = "stats_database"

		override fun setupDatabase(database: Builder<StatsDatabase>) = Unit

	}
}
