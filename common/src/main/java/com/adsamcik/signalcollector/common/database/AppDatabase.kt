package com.adsamcik.signalcollector.common.database

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.adsamcik.signalcollector.common.data.DetectedActivityTypeConverter
import com.adsamcik.signalcollector.common.data.NativeSessionActivity
import com.adsamcik.signalcollector.common.data.SessionActivity
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.dao.*
import com.adsamcik.signalcollector.common.database.data.CellTypeTypeConverter
import com.adsamcik.signalcollector.common.database.data.DatabaseCellData
import com.adsamcik.signalcollector.common.database.data.DatabaseLocation
import com.adsamcik.signalcollector.common.database.data.DatabaseWifiData


@Database(entities = [DatabaseLocation::class,
	TrackerSession::class,
	DatabaseWifiData::class,
	DatabaseCellData::class,
	SessionActivity::class],
		version = 9)
@TypeConverters(CellTypeTypeConverter::class, DetectedActivityTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {

	abstract fun locationDao(): LocationDataDao

	abstract fun sessionDao(): SessionDataDao

	abstract fun wifiDao(): WifiDataDao

	abstract fun cellDao(): CellDataDao

	abstract fun activityDao(): ActivityDao

	companion object {
		private var instance_: AppDatabase? = null

		private fun createInstance(context: Context): AppDatabase {
			val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "main_database")
					.addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
					.build()
			initialize(context, instance)

			instance_ = instance
			return instance
		}

		private fun initialize(context: Context, database: AppDatabase) {
			val sessionActivity = NativeSessionActivity.values().map {
				it.getSessionActivity(context)
			}

			database.activityDao().insert(sessionActivity)
		}

		@WorkerThread
		fun getDatabase(context: Context): AppDatabase {
			return instance_ ?: createInstance(context)
		}

		fun closeDatabase() {
			instance_?.close()
			instance_ = null
		}

		@WorkerThread
		fun deleteAllCollectedData(context: Context) {
			val database = getDatabase(context)

			database.runInTransaction {
				database.sessionDao().deleteAll()
				database.cellDao().deleteAll()
				database.locationDao().deleteAll()
				database.wifiDao().deleteAll()
			}
		}

		fun getTestDatabase(context: Context): AppDatabase {
			return Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java).build()
		}

	}
}