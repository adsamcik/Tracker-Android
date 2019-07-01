package com.adsamcik.signalcollector.common.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.adsamcik.signalcollector.common.data.SessionActivity
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.dao.*
import com.adsamcik.signalcollector.common.database.data.*


@Database(entities = [DatabaseLocation::class,
	TrackerSession::class,
	DatabaseWifiData::class,
	DatabaseCellData::class,
	DatabaseMapMaxHeat::class,
	SessionActivity::class],
		version = 8)
@TypeConverters(CellTypeTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {

	abstract fun locationDao(): LocationDataDao

	abstract fun sessionDao(): SessionDataDao

	abstract fun wifiDao(): WifiDataDao

	abstract fun cellDao(): CellDataDao

	abstract fun mapHeatDao(): MapHeatDao

	abstract fun activityDao(): ActivityDao

	companion object {
		private var instance_: AppDatabase? = null

		private fun createInstance(context: Context): AppDatabase {
			val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "main_database")
					.addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
					.build()

			instance_ = instance
			return instance
		}

		fun getDatabase(context: Context): AppDatabase {
			return instance_ ?: createInstance(context)
		}

		fun closeDatabase() {
			instance_?.close()
			instance_ = null
		}

		fun getTestDatabase(context: Context): AppDatabase {
			return Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java).build()
		}

	}
}