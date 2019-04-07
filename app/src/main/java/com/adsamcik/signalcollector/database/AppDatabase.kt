package com.adsamcik.signalcollector.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.adsamcik.signalcollector.database.dao.*
import com.adsamcik.signalcollector.database.data.*
import com.adsamcik.signalcollector.tracker.data.TrackerSession


@Database(entities = [DatabaseLocation::class,
	TrackerSession::class,
	DatabaseWifiData::class,
	DatabaseCellData::class,
	DatabaseMapMaxHeat::class],
		version = 6)
@TypeConverters(CellTypeTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {

	abstract fun locationDao(): LocationDataDao

	abstract fun sessionDao(): SessionDataDao

	abstract fun wifiDao(): WifiDataDao

	abstract fun cellDao(): CellDataDao

	abstract fun mapHeatDao(): MapHeatDao

	abstract fun challengeDao(): ChallengeDao

	companion object {
		private var instance_: AppDatabase? = null

		private fun createInstance(context: Context): AppDatabase {
			val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "main_database")
					.addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
					.build()

			instance_ = instance
			return instance
		}

		fun getAppDatabase(context: Context): AppDatabase {
			return instance_ ?: createInstance(context)
		}

		fun getTestDatabase(context: Context): AppDatabase {
			return Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java).build()
		}

	}
}