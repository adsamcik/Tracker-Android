package com.adsamcik.tracker.common.database

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.adsamcik.tracker.common.data.NativeSessionActivity
import com.adsamcik.tracker.common.data.NetworkOperator
import com.adsamcik.tracker.common.data.SessionActivity
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.converter.CellTypeConverter
import com.adsamcik.tracker.common.database.converter.DetectedActivityTypeConverter
import com.adsamcik.tracker.common.database.dao.ActivityDao
import com.adsamcik.tracker.common.database.dao.CellLocationDao
import com.adsamcik.tracker.common.database.dao.CellOperatorDao
import com.adsamcik.tracker.common.database.dao.GeneralDao
import com.adsamcik.tracker.common.database.dao.LocationDataDao
import com.adsamcik.tracker.common.database.dao.SessionDataDao
import com.adsamcik.tracker.common.database.dao.WifiDataDao
import com.adsamcik.tracker.common.database.data.DatabaseCellLocation
import com.adsamcik.tracker.common.database.data.DatabaseLocation
import com.adsamcik.tracker.common.database.data.DatabaseWifiData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


@Database(entities = [DatabaseLocation::class,
	TrackerSession::class,
	DatabaseWifiData::class,
	SessionActivity::class,
	NetworkOperator::class,
	DatabaseCellLocation::class],
		version = 9)
@TypeConverters(CellTypeConverter::class, DetectedActivityTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {

	abstract fun locationDao(): LocationDataDao

	abstract fun sessionDao(): SessionDataDao

	abstract fun wifiDao(): WifiDataDao

	abstract fun cellOperatorDao(): CellOperatorDao
	abstract fun cellLocationDao(): CellLocationDao

	abstract fun activityDao(): ActivityDao

	abstract fun generalDao(): GeneralDao

	companion object {
		private var instance_: AppDatabase? = null

		private fun createInstance(context: Context): AppDatabase {
			val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "main_database")
					.addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
							MIGRATION_7_8, MIGRATION_8_9)
					.build()
			initialize(context, instance)

			instance_ = instance
			return instance
		}

		//todo move this away so it's not run every time database is initialized
		private fun initialize(context: Context, database: AppDatabase) {
			GlobalScope.launch(Dispatchers.Default) {
				val activityDao = database.activityDao()

				val sessionActivity = NativeSessionActivity.values().map {
					it.getSessionActivity(context)
				}

				activityDao.insert(sessionActivity)
			}
		}

		@AnyThread
		@Synchronized
		fun getDatabase(context: Context): AppDatabase {
			return instance_ ?: createInstance(context)
		}

		@WorkerThread
		fun deleteAllCollectedData(context: Context) {
			val database = getDatabase(context)

			database.runInTransaction {
				database.sessionDao().deleteAll()
				database.cellLocationDao().deleteAll()
				database.cellOperatorDao().deleteAll()
				database.locationDao().deleteAll()
				database.wifiDao().deleteAll()
			}
		}

		fun getTestDatabase(context: Context): AppDatabase {
			return Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java)
					.build()
		}

	}
}

