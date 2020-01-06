package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.adsamcik.tracker.shared.base.data.NetworkOperator
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.converter.CellTypeConverter
import com.adsamcik.tracker.shared.base.database.converter.DetectedActivityTypeConverter
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import com.adsamcik.tracker.shared.base.database.dao.CellLocationDao
import com.adsamcik.tracker.shared.base.database.dao.CellOperatorDao
import com.adsamcik.tracker.shared.base.database.dao.GeneralDao
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.dao.LocationWifiCountDao
import com.adsamcik.tracker.shared.base.database.dao.SessionDataDao
import com.adsamcik.tracker.shared.base.database.dao.WifiDataDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseCellLocation
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocationWifiCount
import com.adsamcik.tracker.shared.base.database.data.DatabaseWifiData


/**
 * Provides access to main database.
 * Contains only common data nothing module specific.
 */
@Database(
		version = 10,
		entities = [DatabaseLocation::class,
			TrackerSession::class,
			DatabaseWifiData::class,
			SessionActivity::class,
			NetworkOperator::class,
			DatabaseCellLocation::class,
			DatabaseLocationWifiCount::class]
)
@TypeConverters(CellTypeConverter::class, DetectedActivityTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {

	abstract fun locationDao(): LocationDataDao

	abstract fun sessionDao(): SessionDataDao

	abstract fun wifiDao(): WifiDataDao
	abstract fun wifiLocationCountDao(): LocationWifiCountDao

	abstract fun cellOperatorDao(): CellOperatorDao
	abstract fun cellLocationDao(): CellLocationDao

	abstract fun activityDao(): ActivityDao

	abstract fun generalDao(): GeneralDao

	companion object : ObjectBaseDatabase<AppDatabase>(AppDatabase::class.java) {
		override val databaseName: String = "main_database"

		override fun setupDatabase(database: Builder<AppDatabase>) {
			database.addMigrations(
					MIGRATION_2_3,
					MIGRATION_3_4,
					MIGRATION_4_5,
					MIGRATION_5_6,
					MIGRATION_6_7,
					MIGRATION_7_8,
					MIGRATION_8_9,
					MIGRATION_9_10
			)
		}

		/**
		 * Deletes all collected data from the database.
		 * Does not delete database itself.
		 */
		@WorkerThread
		fun deleteAllCollectedData(context: Context) {
			val database = database(context)

			database.runInTransaction {
				database.sessionDao().deleteAll()
				database.cellLocationDao().deleteAll()
				database.cellOperatorDao().deleteAll()
				database.locationDao().deleteAll()
				database.wifiDao().deleteAll()
			}
		}
	}
}

