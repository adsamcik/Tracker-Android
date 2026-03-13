package com.adsamcik.tracker.shared.base.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.adsamcik.tracker.shared.base.database.dao.ActivityDebugDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseDebugActivity

@Database(entities = [DatabaseDebugActivity::class], version = 1)
abstract class DebugDatabase : RoomDatabase() {
	abstract fun activityDebugDao(): ActivityDebugDao
}

