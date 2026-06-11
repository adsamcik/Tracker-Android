package com.adsamcik.tracker.shared.base.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.dao.ActivityDebugDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseDebugActivity

@Database(entities = [DatabaseDebugActivity::class], version = 2)
abstract class DebugDatabase : RoomDatabase() {
	abstract fun activityDebugDao(): ActivityDebugDao

	companion object {
		val MIGRATION_1_2: Migration = object : Migration(1, 2) {
			override fun migrate(db: SupportSQLiteDatabase) {
				db.execSQL("CREATE INDEX IF NOT EXISTS index_debug_activity_time ON debug_activity(time)")
			}
		}
	}
}
