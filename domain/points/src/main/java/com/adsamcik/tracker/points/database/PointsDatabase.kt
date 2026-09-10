package com.adsamcik.tracker.points.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.points.data.PointsDataConverters
import com.adsamcik.tracker.shared.base.database.ObjectBaseDatabase

/**
 * Database for points
 */
@Database(
		version = 2,
		entities = [PointsAwarded::class]
)
@TypeConverters(PointsDataConverters::class)
abstract class PointsDatabase : RoomDatabase() {

	/**
	 * Returns points awarded data access object
	 */
	abstract fun pointsAwardedDao(): PointsAwardedDao

	companion object : ObjectBaseDatabase<PointsDatabase>(PointsDatabase::class.java) {
		override val databaseName: String = "points_database"

		override fun setupDatabase(database: Builder<PointsDatabase>) {
			database.addMigrations(MIGRATION_1_2)
		}

		internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
			override fun migrate(db: SupportSQLiteDatabase) {
				db.execSQL("ALTER TABLE points_awarded ADD COLUMN effect_key TEXT")
				db.execSQL("ALTER TABLE points_awarded ADD COLUMN effect_revision INTEGER")
				db.execSQL("ALTER TABLE points_awarded ADD COLUMN effect_value_micros INTEGER")
				db.execSQL(
					"""
					CREATE UNIQUE INDEX IF NOT EXISTS index_points_awarded_source_effect_key
					ON points_awarded(source, effect_key)
					""".trimIndent(),
				)
			}
		}
	}
}
