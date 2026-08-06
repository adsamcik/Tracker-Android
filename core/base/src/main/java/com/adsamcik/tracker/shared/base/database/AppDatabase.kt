package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.data.NetworkOperator
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.converter.CellTypeConverter
import com.adsamcik.tracker.shared.base.database.converter.DetectedActivityTypeConverter
import com.adsamcik.tracker.shared.base.database.converter.GeoFeaturePropertiesConverter
import com.adsamcik.tracker.shared.base.database.converter.SessionlessTypeConverter
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.CellOperatorDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.FrequentPlaceDao
import com.adsamcik.tracker.shared.base.database.dao.GeneralDao
import com.adsamcik.tracker.shared.base.database.dao.InferredTripDao
import com.adsamcik.tracker.shared.base.database.dao.ImportReceiptDao
import com.adsamcik.tracker.shared.base.database.dao.LiveStatsDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDao
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDecisionDao
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.database.dao.OsmImportDao
import com.adsamcik.tracker.shared.base.database.dao.OsmWayCellDao
import com.adsamcik.tracker.shared.base.database.dao.OsmWayDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerRunDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerStateEventDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.dao.TripLegDao
import com.adsamcik.tracker.shared.base.database.dao.TrajectoryReconstructionDao
import com.adsamcik.tracker.shared.base.database.dao.UnifiedGeoDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.dao.XpLedgerDao
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.database.data.FrequentPlaceEntity
import com.adsamcik.tracker.shared.base.database.data.InferredTripEntity
import com.adsamcik.tracker.shared.base.database.data.ImportEntryReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportJobReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LiveStatsEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyLocationWifiCount
import com.adsamcik.tracker.shared.base.database.data.LegacyRejectedTrackerSession
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.LocationObservationDecision
import com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity
import com.adsamcik.tracker.shared.base.database.data.OsmImportEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayCellEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import com.adsamcik.tracker.shared.base.database.data.PlayerProfileEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
import com.adsamcik.tracker.shared.base.database.data.TrackerStateEvent
import com.adsamcik.tracker.shared.base.database.data.TripLegEntity
import com.adsamcik.tracker.shared.base.database.data.TrajectoryReconstructionRunEntity
import com.adsamcik.tracker.shared.base.database.data.TrajectorySourceLinkEntity
import com.adsamcik.tracker.shared.base.database.data.TrajectoryStateEntity
import com.adsamcik.tracker.shared.base.database.data.RouteHypothesisEntity
import com.adsamcik.tracker.shared.base.database.data.VisitIntervalEntity
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.PersonalRecordDao
import com.adsamcik.tracker.shared.base.database.dao.RouteCacheDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalClaimDao
import com.adsamcik.tracker.shared.base.database.dao.PlayerProfileDao
import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.QuarantinedSignalDao
import com.adsamcik.tracker.shared.base.database.dao.SkiRunSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.StorageSizeSnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.shared.base.database.data.ExplorationCellEntity
import com.adsamcik.tracker.shared.base.database.data.ExplorationStreakEntity
import com.adsamcik.tracker.shared.base.database.data.ExportLogEntity
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.shared.base.database.data.PersonalRecordEntity
import com.adsamcik.tracker.shared.base.database.data.QuarantinedSignalEntity
import com.adsamcik.tracker.shared.base.database.data.RouteCacheEntity
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.SkiRunSegment
import com.adsamcik.tracker.shared.base.database.data.StorageSizeSnapshotEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.dao.DomainEventDao
import com.adsamcik.tracker.shared.base.database.data.DomainEventCursorEntity
import com.adsamcik.tracker.shared.base.database.data.DomainEventEntity
import com.adsamcik.tracker.shared.base.database.data.XpLedgerEntity
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupStore
import com.adsamcik.tracker.shared.base.database.migration.MigrationBackupOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper

internal const val CURRENT_DATABASE_VERSION = 27

/**
 * Provides access to main database.
 * Contains only common data nothing module specific.
 *
 * CURRENT VERSION: 27 (App versionCode: 400 - UNRELEASED)
 *
 * Version 26 shipped in versionCode 385. All schema work since that release is
 * intentionally folded into the single 26 -> 27 migration until versionCode 400 ships.
 * See AppDatabaseMigrations.kt for full version history and migration rules.
 */
@Database(
		version = CURRENT_DATABASE_VERSION,
		entities = [
			// Core reference entities
			SessionActivity::class,
			NetworkOperator::class,
			// Sessionless architecture entities
			LocationSample::class,
			LocationObservation::class,
			LocationObservationDecision::class,
			StepInterval::class,
			ActivitySnapshot::class,
			CellSample::class,
			WifiObservation::class,
			TrackerRun::class,
			TrackerStateEvent::class,
			SourceEvidenceState::class,
			SessionSegment::class,
			LegacyRejectedTrackerSession::class,
			LegacyLocationWifiCount::class,
			DailySummaryEntity::class,
			LiveStatsEntity::class,
			// Trip inference entities (Phase 3b)
			FrequentPlaceEntity::class,
			InferredTripEntity::class,
			TripLegEntity::class,
			// Versioned, immutable historical reconstruction products
			TrajectoryReconstructionRunEntity::class,
			TrajectoryStateEntity::class,
			TrajectorySourceLinkEntity::class,
			RouteHypothesisEntity::class,
			VisitIntervalEntity::class,
			// Exploration and achievement entities (Phase 3c)
			ExplorationCellEntity::class,
			ExplorationStreakEntity::class,
			AchievementProgressEntity::class,
			PersonalRecordEntity::class,
			// Route compression and storage monitoring (Phase 6a)
			RouteCacheEntity::class,
			ExportLogEntity::class,
			ImportJobReceiptEntity::class,
			ImportEntryReceiptEntity::class,
			StorageSizeSnapshotEntity::class,
			// Domain events (stats pipeline)
			DomainEventEntity::class,
			DomainEventCursorEntity::class,
			// Ski detection entities (Phase 7)
			PressureSample::class,
			SkiRunSegment::class,
			// Durable write-ahead log for tracking signals
			PendingSignalEntity::class,
			QuarantinedSignalEntity::class,
			// Game progression and minigames,
			XpLedgerEntity::class,
			PlayerProfileEntity::class,
			MiniGameScoreEntity::class,
			// OSM road graph (Phase 2 vehicle speed compliance)
			OsmImportEntity::class,
			OsmWayEntity::class,
			OsmWayCellEntity::class,
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
	 * Provides access to cell network operator data.
	 */
	abstract fun cellOperatorDao(): CellOperatorDao

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

	abstract fun locationObservationDao(): LocationObservationDao

	abstract fun locationObservationDecisionDao(): LocationObservationDecisionDao

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

	abstract fun trackerStateEventDao(): TrackerStateEventDao

	abstract fun sourceEvidenceStateDao(): SourceEvidenceStateDao

	/**
	 * Provides access to inferred session segments.
	 */
	abstract fun sessionSegmentDao(): SessionSegmentDao

	/**
	 * Provides read-only Trip projections over session segments.
	 */
	abstract fun tripDao(): TripDao

	/**
	 * Provides access to materialized daily summary data.
	 */
	abstract fun dailySummaryDao(): DailySummaryDao

	/**
	 * Provides access to live tracking stats (single-row table).
	 */
	abstract fun liveStatsDao(): LiveStatsDao

	/**
	 * Provides access to frequent place clusters.
	 */
	abstract fun frequentPlaceDao(): FrequentPlaceDao

	/**
	 * Provides access to enriched inferred trips.
	 */
	abstract fun inferredTripDao(): InferredTripDao

	/**
	 * Provides access to trip legs.
	 */
	abstract fun tripLegDao(): TripLegDao

	abstract fun trajectoryReconstructionDao(): TrajectoryReconstructionDao

	// Exploration DAOs (Phase 4)

	/**
	 * Provides access to exploration cell data.
	 */
	abstract fun explorationCellDao(): ExplorationCellDao

	/**
	 * Provides access to exploration streak data.
	 */
	abstract fun explorationStreakDao(): ExplorationStreakDao

	/**
	 * Provides access to achievement progress data.
	 */
	abstract fun achievementProgressDao(): AchievementProgressDao

	/**
	 * Provides access to personal record data.
	 */
	abstract fun personalRecordDao(): PersonalRecordDao

	// Route compression and storage monitoring DAOs (Phase 6a)

	/**
	 * Provides access to compressed route cache data.
	 */
	abstract fun routeCacheDao(): RouteCacheDao

	/**
	 * Provides access to export log records.
	 */
	abstract fun exportLogDao(): ExportLogDao

	/** Provides durable job and entry receipts for resumable imports. */
	abstract fun importReceiptDao(): ImportReceiptDao

	/**
	 * Provides access to daily storage size snapshots.
	 */
	abstract fun storageSizeSnapshotDao(): StorageSizeSnapshotDao

	// Domain event DAO (stats pipeline)

	/**
	 * Provides access to domain events emitted by the stats processor pipeline.
	 */
	abstract fun domainEventDao(): DomainEventDao

	// Ski detection DAOs (Phase 7)

	/**
	 * Provides access to barometric pressure samples.
	 */
	abstract fun pressureSampleDao(): PressureSampleDao

	/**
	 * Provides access to ski run segment data.
	 */
	abstract fun skiRunSegmentDao(): SkiRunSegmentDao

	// Durable signal buffer DAO

	/**
	 * Provides access to the pending signal write-ahead log.
	 */
	abstract fun pendingSignalDao(): PendingSignalDao

	/**
	 * Provides recovery-only claim/lease operations for pending signals.
	 */
	abstract fun pendingSignalClaimDao(): PendingSignalClaimDao

	/**
	 * Provides read/admin access to permanently quarantined signal rows.
	 */
	abstract fun quarantinedSignalDao(): QuarantinedSignalDao

	/**
	 * Provides access to XP ledger entries.
	 */
	abstract fun xpLedgerDao(): XpLedgerDao

	/**
	 * Provides access to the player profile singleton.
	 */
	abstract fun playerProfileDao(): PlayerProfileDao

	/**
	 * Provides access to minigame score rows.
	 */
	abstract fun miniGameScoreDao(): MiniGameScoreDao

	// OSM road graph DAOs (Phase 2 vehicle speed compliance)

	/**
	 * Provides access to OSM import header rows.
	 *
	 * The presence of any row here is the runtime signal that the
	 * `SpeedLimitSource` should snap to imported road geometry instead of
	 * returning the fixed baseline.
	 */
	abstract fun osmImportDao(): OsmImportDao

	/**
	 * Provides access to OSM way (road segment) rows.
	 */
	abstract fun osmWayDao(): OsmWayDao

	/**
	 * Provides access to the coarse-grid spatial index linking OSM ways to cells.
	 */
	abstract fun osmWayCellDao(): OsmWayCellDao

	companion object : ObjectBaseDatabase<AppDatabase>(AppDatabase::class.java) {
		override val databaseName: String = "main_database"
		internal val migrations = arrayOf(
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
			MIGRATION_12_13,
			MIGRATION_13_14,
			MIGRATION_14_15,
			MIGRATION_15_16,
			MIGRATION_16_17,
			MIGRATION_17_18,
			MIGRATION_18_19,
			MIGRATION_19_20,
			MIGRATION_20_21,
			MIGRATION_21_22,
			MIGRATION_22_23,
			MIGRATION_23_24,
			MIGRATION_24_25,
			MIGRATION_25_26,
			MIGRATION_26_27,
		)

		override fun setupDatabase(database: Builder<AppDatabase>) {
			database.addMigrations(*migrations)
		}

		override fun openHelperFactory(
			context: Context,
			delegate: SupportSQLiteOpenHelper.Factory,
		): SupportSQLiteOpenHelper.Factory = MigrationBackupOpenHelperFactory(
			delegate = delegate,
			backupStore = DatabaseMigrationBackupStore(context),
			databaseName = databaseName,
			targetVersion = CURRENT_DATABASE_VERSION,
		)

		/**
		 * Deletes all collected data from the database.
		 * Does not delete database itself.
		 */
		@WorkerThread
		fun deleteAllCollectedData(context: Context) {
			val database = database(context)
			val backupStore = DatabaseMigrationBackupStore(context)
			backupStore.markDeletionPending()
			deleteAllCollectedData(database)
			backupStore.deleteAll()
		}

		/**
		 * Deletes collected rows while atomically recording the durable lifecycle
		 * transition that authorized the deletion. The singleton state row remains
		 * after the clear so an in-flight writer carrying an older epoch cannot
		 * repopulate the new database generation.
		 */
		suspend fun deleteAllCollectedData(
			context: Context,
			collectedDataEpoch: Long,
			retainedFromMs: Long?,
			updatedAtMs: Long,
		) {
			val database = database(context)
			val backupStore = DatabaseMigrationBackupStore(context)
			backupStore.markDeletionPending()
			deleteAllCollectedData(database, collectedDataEpoch, retainedFromMs, updatedAtMs)
			backupStore.deleteAll()
		}

		internal suspend fun deleteAllCollectedData(
			database: AppDatabase,
			collectedDataEpoch: Long,
			retainedFromMs: Long?,
			updatedAtMs: Long,
		) {
			database.withTransaction {
				val stateDao = database.sourceEvidenceStateDao()
				val lifecycleChanged = stateDao.synchronizeLifecycle(
					epoch = collectedDataEpoch,
					retainedFromMs = retainedFromMs,
					updatedAtMs = updatedAtMs,
				)
				if (!lifecycleChanged) {
					check(stateDao.incrementRevision(updatedAtMs) == 1) {
						"Unable to advance source-evidence revision for full deletion"
					}
				}
				deleteCollectedRows(database)
			}
		}

		internal fun deleteAllCollectedData(database: AppDatabase) {
			database.runInTransaction {
				deleteCollectedRows(database)
			}
		}

		private fun deleteCollectedRows(database: AppDatabase) {
			// Sessionless architecture tables
			database.locationSampleDao().deleteAll()
			database.locationObservationDao().deleteAll()
			database.locationObservationDecisionDao().deleteAll()
			database.stepIntervalDao().deleteAll()
			database.activitySnapshotDao().deleteAll()
			database.cellSampleDao().deleteAll()
			database.wifiObservationDao().deleteAll()
			database.trackerRunDao().deleteAll()
			database.trackerStateEventDao().deleteAll()
			database.sessionSegmentDao().deleteAll()
			database.dailySummaryDao().deleteAll()
			database.liveStatsDao().deleteAll()

			// Trip inference tables (FK-aware order: children first)
			database.trajectoryReconstructionDao().deleteAll()
			database.tripLegDao().deleteAll()
			database.inferredTripDao().deleteAll()
			database.frequentPlaceDao().deleteAll()

			// Exploration tables
			database.explorationCellDao().deleteAll()
			database.explorationStreakDao().deleteAll()
			database.achievementProgressDao().deleteAll()
			database.personalRecordDao().deleteAll()

			// Route compression and storage monitoring tables
			database.routeCacheDao().deleteAll()
			database.exportLogDao().deleteAll()
			database.importReceiptDao().deleteAllEntries()
			database.importReceiptDao().deleteAllJobs()
			database.storageSizeSnapshotDao().deleteAll()
			database.openHelper.writableDatabase.execSQL("DELETE FROM domain_event_cursor")
			database.openHelper.writableDatabase.execSQL("DELETE FROM domain_event")

			// Ski detection tables
			database.pressureSampleDao().deleteAll()
			database.skiRunSegmentDao().deleteAll()

			// Pending signal WAL
			database.pendingSignalDao().deleteAll()
			database.quarantinedSignalDao().deleteAll()
			// Game progression and minigames
			database.xpLedgerDao().deleteAll()
			database.playerProfileDao().deleteAll()
			database.miniGameScoreDao().deleteAll()
			database.openHelper.writableDatabase.execSQL("DELETE FROM legacy_rejected_tracker_session")
			database.openHelper.writableDatabase.execSQL("DELETE FROM legacy_location_wifi_count")

			// OSM road graph (user-imported region; not collected data per-se
			// but covered by the same "delete everything" semantics).
			database.osmImportDao().deleteAllTables()
		}
	}
}
