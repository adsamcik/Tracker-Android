package com.adsamcik.signalcollector.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.adsamcik.signalcollector.database.dao.LocationDataDao
import com.adsamcik.signalcollector.database.dao.SessionDataDao
import com.adsamcik.signalcollector.data.TrackingSession
import com.adsamcik.signalcollector.database.data.DatabaseLocation


@Database(entities = [DatabaseLocation::class,TrackingSession::class], version = 2)
abstract class AppDatabase : RoomDatabase() {

	abstract fun locationDao(): LocationDataDao

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