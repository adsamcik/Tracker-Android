package com.adsamcik.tracker.points.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.shared.base.database.ObjectBaseDatabase

/**
 * Database for points
 */
@Database(
		version = 1,
		entities = [PointsAwarded::class]
)
abstract class PointsDatabase : RoomDatabase() {

	/**
	 * Returns points awarded data access object
	 */
	abstract fun pointsAwardedDao(): PointsAwardedDao

	companion object : ObjectBaseDatabase<PointsDatabase>(PointsDatabase::class.java) {
		override val databaseName: String = "points_database"

		override fun setupDatabase(database: Builder<PointsDatabase>) = Unit
	}
}
