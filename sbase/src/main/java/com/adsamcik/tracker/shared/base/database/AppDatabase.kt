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
import com.adsamcik.tracker.shared.base.database.converter.GeoFeaturePropertiesConverter
import com.adsamcik.tracker.shared.base.database.converter.SessionlessTypeConverter
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.CellLocationDao
import com.adsamcik.tracker.shared.base.database.dao.CellOperatorDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.GeneralDao
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationWifiCountDao
import com.adsamcik.tracker.shared.base.database.dao.SessionDataDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerRunDao
import com.adsamcik.tracker.shared.base.database.dao.WifiDataDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.dao.UnifiedGeoDao
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.DatabaseCellLocation
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocationWifiCount
import com.adsamcik.tracker.shared.base.database.data.DatabaseWifiData
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
import com.adsamcik.tracker.shared.base.database.data.WifiObservation


/**
 * Provides access to main database.
 * Contains only common data nothing module specific.
 *
 * CURRENT VERSION: 13 (App versionCode: 385 - UNRELEASED)
 * See AppDatabaseMigrations.kt for full version history and migration rules.
 */
@Database(
		version = 13,
		entities = [
			// Legacy entities (kept for read-only access during migration period)
			DatabaseLocation::class,
			TrackerSession::class,
			DatabaseWifiData::class,
			SessionActivity::class,
			NetworkOperator::class,
			DatabaseCellLocation::class,
			DatabaseLocationWifiCount::class,
			// New sessionless architecture entities
			LocationSample::class,
			StepInterval::class,
			ActivitySnapshot::class,
			CellSample::class,
			WifiObservation::class,
			TrackerRun::class,
			SessionSegment::class
		]
)
@TypeConverters(
	CellTypeConverter::class,
	DetectedActivityTypeConverter::class,
	GeoFeaturePropertiesConverter::class,
	SessionlessTypeConverter::class
)
abstract class AppDatabase : RoomDatabase() {

	/**
	 * Provides access to location data
	 */
	abstract fun locationDao(): LocationDataDao

	/**
	 * Provides access to session data
	 */
	abstract fun sessionDao(): SessionDataDao

	/**
	 * Provides access to Wi-Fi data
	 */
	abstract fun wifiDao(): WifiDataDao

	/**
	 * Provides access to Wi-Fi count location data
	 */
	abstract fun wifiLocationCountDao(): LocationWifiCountDao


	/**
	 * Provides access to cell network operator data.
	 */
	abstract fun cellOperatorDao(): CellOperatorDao

	/**
	 * Provides access to cell location quality data.
	 */
	abstract fun cellLocationDao(): CellLocationDao

	/**
	 * Provides access to activity data.
	 */
	abstract fun activityDao(): ActivityDao

	/**
	 * Provides access to general utility methods.
	 * These should be used only when absolutely needed.
	 */
	abstract fun generalDao(): GeneralDao

	/**
	 * Provides access to unified geo raw queries (Phase 3.1 experimental API).
	 */
	abstract fun unifiedGeoDao(): UnifiedGeoDao

	// New sessionless architecture DAOs

	/**
	 * Provides access to raw location samples (sessionless tracking).
	 */
	abstract fun locationSampleDao(): LocationSampleDao

	/**
	 * Provides access to step interval data (sessionless tracking).
	 */
	abstract fun stepIntervalDao(): StepIntervalDao

	/**
	 * Provides access to activity snapshots (sessionless tracking).
	 */
	abstract fun activitySnapshotDao(): ActivitySnapshotDao

	/**
	 * Provides access to cell tower samples (sessionless tracking).
	 */
	abstract fun cellSampleDao(): CellSampleDao

	/**
	 * Provides access to Wi-Fi observations (sessionless tracking).
	 */
	abstract fun wifiObservationDao(): WifiObservationDao

	/**
	 * Provides access to tracker runs (tracking policy state).
	 */
	abstract fun trackerRunDao(): TrackerRunDao

	/**
	 * Provides access to inferred session segments.
	 */
	abstract fun sessionSegmentDao(): SessionSegmentDao

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
						MIGRATION_9_10,
						MIGRATION_10_11,
						MIGRATION_11_12,
						MIGRATION_12_13
				)
		}

		/**
		 * Deletes all collected data from the database.
		 * Does not delete database itself.
		 * Clears both legacy session-based tables and new sessionless tables.
		 */
		@WorkerThread
		fun deleteAllCollectedData(context: Context) {
			val database = database(context)

			database.runInTransaction {
				// Legacy session-based tables
				database.sessionDao().deleteAll()
				database.cellLocationDao().deleteAll()
				database.cellOperatorDao().deleteAll()
				database.locationDao().deleteAll()
				database.wifiDao().deleteAll()

				// Sessionless architecture tables
				database.locationSampleDao().deleteAll()
				database.stepIntervalDao().deleteAll()
				database.activitySnapshotDao().deleteAll()
				database.cellSampleDao().deleteAll()
				database.wifiObservationDao().deleteAll()
				database.trackerRunDao().deleteAll()
				database.sessionSegmentDao().deleteAll()
			}
		}
	}
}

