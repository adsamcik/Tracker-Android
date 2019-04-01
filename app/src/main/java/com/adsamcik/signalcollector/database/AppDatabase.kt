package com.adsamcik.signalcollector.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.adsamcik.signalcollector.database.dao.*
import com.adsamcik.signalcollector.database.data.*
import com.adsamcik.signalcollector.tracker.data.TrackerSession


@Database(entities = [DatabaseLocation::class, TrackerSession::class, DatabaseWifiData::class, DatabaseCellData::class, DatabaseMapMaxHeat::class], version = 4)
@TypeConverters(CellTypeTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {

	abstract fun locationDao(): LocationDataDao

	abstract fun sessionDao(): SessionDataDao

	abstract fun wifiDao(): WifiDataDao

	abstract fun cellDao(): CellDataDao

	abstract fun mapHeatDao(): MapHeatDao

	companion object {
		private var instance_: AppDatabase? = null

		fun getAppDatabase(context: Context): AppDatabase {
			if (instance_ == null) {
				instance_ = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "main_database")
						.addMigrations(MIGRATION_2_3, MIGRATION_3_4)
						.build()
			}
			return instance_ as AppDatabase
		}

	}
}