package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.adsamcik.tracker.shared.base.database.dao.ActivityDebugDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseDebugActivity

@Database(entities = [DatabaseDebugActivity::class], version = 1)
abstract class DebugDatabase : RoomDatabase() {
	abstract fun activityDebugDao(): ActivityDebugDao

	companion object {
		private var instance_: DebugDatabase? = null

		fun getAppDatabase(context: Context): DebugDatabase {
			if (instance_ == null) {
				instance_ = Room.databaseBuilder(
						context.applicationContext, DebugDatabase::class.java,
						"debug_database"
				)
						.fallbackToDestructiveMigration()
						.build()
			}
			return instance_ as DebugDatabase
		}

		fun destroyInstance() {
			instance_ = null
		}
	}
}

