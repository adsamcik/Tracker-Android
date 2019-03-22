package com.adsamcik.signalcollector.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.adsamcik.signalcollector.dao.ActivityDataDao
import com.adsamcik.signalcollector.dao.LocationDataDao
import com.adsamcik.signalcollector.dao.SessionDataDao
import com.adsamcik.signalcollector.data.DatabaseActivity
import com.adsamcik.signalcollector.data.DatabaseLocation
import com.adsamcik.signalcollector.data.TrackingSession


@Database(entities = [DatabaseLocation::class, DatabaseActivity::class, TrackingSession::class], version = 2)
abstract class AppDatabase : RoomDatabase() {

	abstract fun locationDao(): LocationDataDao

	abstract fun activityDao(): ActivityDataDao

	abstract fun sessionDao(): SessionDataDao

	companion object {
		private var instance_: AppDatabase? = null

		fun getAppDatabase(context: Context): AppDatabase {
			if (instance_ == null) {
				instance_ = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "main_database")
						.fallbackToDestructiveMigration()
						.build()
			}
			return instance_ as AppDatabase
		}

		fun destroyInstance() {
			instance_ = null
		}
	}
}