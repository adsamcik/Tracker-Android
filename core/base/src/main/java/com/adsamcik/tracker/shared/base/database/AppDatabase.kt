package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.converter.CellTypeConverter
import com.adsamcik.tracker.shared.base.database.converter.DetectedActivityTypeConverter
import com.adsamcik.tracker.shared.base.database.converter.GeoFeaturePropertiesConverter
import com.adsamcik.tracker.shared.base.database.converter.SessionlessTypeConverter
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import com.adsamcik.tracker.shared.base.database.dao.ActivityAutomaticStartActionDao
import com.adsamcik.tracker.shared.base.database.dao.ActivityAutomationEpochDao
import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.GeneralDao
import com.adsamcik.tracker.shared.base.database.dao.ImportReceiptDao
import com.adsamcik.tracker.shared.base.database.dao.LiveStatsDao
import com.adsamcik.tracker.shared.base.database.dao.LegacyV27ProjectionDrainDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDao
import com.adsamcik.tracker.shared.base.database.dao.LocationProjectionDao
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDecisionDao
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerRunDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerStateEventDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.dao.TrajectoryReconstructionDao
import com.adsamcik.tracker.shared.base.database.dao.UnifiedGeoDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.dao.XpLedgerDao
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.database.data.ImportEntryReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportJobReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LiveStatsEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionDrainEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionTargetEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionObservationEntity
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionPointEntity
import com.adsamcik.tracker.shared.base.database.data.LocationObservationDecision
import com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity
import com.adsamcik.tracker.shared.base.database.data.PlayerProfileEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
import com.adsamcik.tracker.shared.base.database.data.TrackerStateEvent
import com.adsamcik.tracker.shared.base.database.data.TrajectoryReconstructionRunEntity
import com.adsamcik.tracker.shared.base.database.data.TrajectorySourceLinkEntity
import com.adsamcik.tracker.shared.base.database.data.TrajectoryStateEntity
import com.adsamcik.tracker.shared.base.database.data.VisitIntervalEntity
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalClaimDao
import com.adsamcik.tracker.shared.base.database.dao.PlayerProfileDao
import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.QuarantinedSignalDao
import com.adsamcik.tracker.shared.base.database.dao.SkiRunSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao
import com.adsamcik.tracker.shared.base.database.dao.SourceEventWalDao
import com.adsamcik.tracker.shared.base.database.dao.SourceProjectionStateDao
import com.adsamcik.tracker.shared.base.database.dao.SourceRegistrationStateDao
import com.adsamcik.tracker.shared.base.database.dao.SourceSessionDao
import com.adsamcik.tracker.shared.base.database.dao.TrackingRolloutStateDao
import com.adsamcik.tracker.shared.base.database.dao.recordFullDeletion
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.shared.base.database.data.ExplorationCellEntity
import com.adsamcik.tracker.shared.base.database.data.ExplorationStreakEntity
import com.adsamcik.tracker.shared.base.database.data.ExportLogEntity
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.shared.base.database.data.QuarantinedSignalEntity
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.SkiRunSegment
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionCheckpointEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.TrackingRolloutStateEntity
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAppliedPlanStateEntity
import com.adsamcik.tracker.shared.base.database.dao.SourcePlanStateDao
import com.adsamcik.tracker.shared.base.database.dao.SourcePolicyDao
import com.adsamcik.tracker.shared.base.database.dao.SourceBrokerDao
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.dao.SourceRuntimeStateDao
import com.adsamcik.tracker.shared.base.database.dao.DomainEventDao
import com.adsamcik.tracker.shared.base.database.data.DomainEventCursorEntity
import com.adsamcik.tracker.shared.base.database.data.DomainEventEntity
import com.adsamcik.tracker.shared.base.database.data.XpLedgerEntity
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupStore
import com.adsamcik.tracker.shared.base.database.migration.MigrationBackupOpenHelperFactory
import com.adsamcik.tracker.shared.base.database.legacy.ACTIVE_DATABASE_NAME
import com.adsamcik.tracker.shared.base.database.legacy.LegacyImportRoomCallback
import com.adsamcik.tracker.shared.base.database.legacy.LEGACY_IMPORT_JOB_ID
import androidx.sqlite.db.SupportSQLiteOpenHelper

/** Last schema shipped on the stable active database file. Its contract is immutable. */
internal const val LAST_RELEASED_ACTIVE_DATABASE_VERSION = 27

/** Current development schema. Version 28 has not shipped and may still be refined in place. */
internal const val CURRENT_DATABASE_VERSION = 28

/**
 * Provides access to main database.
 * Contains only common data nothing module specific.
 *
 * CURRENT VERSION: 28 (App versionCode: 400 - UNRELEASED)
 *
 * Version 26 shipped in versionCode 385. Version 27 established the stable active database file
 * and is now the frozen compatibility boundary. Version 28 is the first additive in-place
 * migration on that file. See AppDatabaseMigrations.kt for the full history and migration rules.
 */
@Database(
		version = CURRENT_DATABASE_VERSION,
		entities = [
			// Core reference entities
			SessionActivity::class,
			// Sessionless architecture entities
			LocationSample::class,
			LocationObservation::class,
			LocationProjectionObservationEntity::class,
			LocationProjectionPointEntity::class,
			LocationObservationDecision::class,
			StepInterval::class,
			ActivitySnapshot::class,
			CellSample::class,
			WifiObservation::class,
			TrackerRun::class,
			TrackerStateEvent::class,
			SourceEvidenceState::class,
			SourceEventWalEntity::class,
			SourceProductProjectionLaneEntity::class,
			SourceProjectionRegistrationEntity::class,
			SourceProjectionCheckpointEntity::class,
			SourceProjectionFailureEntity::class,
			SourceProjectionJoinStateEntity::class,
			SourceProjectionOutboxEntity::class,
			SourceCoordinatorLeaseEntity::class,
			LegacyV27ProjectionDrainEntity::class,
			LegacyV27ProjectionTargetEntity::class,
			SourceRegistrationStateEntity::class,
			LogicalTrackingSessionEntity::class,
			SourceServiceRunEntity::class,
			SourceSessionCompletenessEntity::class,
			SessionManifestVersionEntity::class,
			SessionManifestSourceEntity::class,
			SessionLifecycleIntentVersionEntity::class,
			LifecycleDesiredActionEntity::class,
			ActivityAutomaticStartActionEntity::class,
			ActivityAutomationEpochEntity::class,
			TrackingRolloutStateEntity::class,
			AcquisitionPlanRevisionEntity::class,
			SourceDesiredPlanEntity::class,
			SourceAppliedPlanStateEntity::class,
			SourceRuntimeStateEntity::class,
			SourcePolicyAuthorityEntity::class,
			SourcePolicyEntity::class,
			SourceConsentEpochEntity::class,
			SourceDemandEntity::class,
			ProviderRegistrationGenerationEntity::class,
			SourceAuthorizationEntity::class,
			SessionSegment::class,
			DailySummaryEntity::class,
			LiveStatsEntity::class,
			// Versioned, immutable historical reconstruction products
			TrajectoryReconstructionRunEntity::class,
			TrajectoryStateEntity::class,
			TrajectorySourceLinkEntity::class,
			VisitIntervalEntity::class,
			// Exploration and achievement entities (Phase 3c)
			ExplorationCellEntity::class,
			ExplorationStreakEntity::class,
			AchievementProgressEntity::class,
			// Export and import audit records
			ExportLogEntity::class,
			ImportJobReceiptEntity::class,
			ImportEntryReceiptEntity::class,
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

	abstract fun locationProjectionDao(): LocationProjectionDao

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

	abstract fun sourceEventWalDao(): SourceEventWalDao

	abstract fun sourceProjectionStateDao(): SourceProjectionStateDao

	abstract fun legacyV27ProjectionDrainDao(): LegacyV27ProjectionDrainDao

	abstract fun sourceRegistrationStateDao(): SourceRegistrationStateDao

	abstract fun sourceSessionDao(): SourceSessionDao

	abstract fun activityAutomaticStartActionDao(): ActivityAutomaticStartActionDao

	abstract fun activityAutomationEpochDao(): ActivityAutomationEpochDao

	abstract fun trackingRolloutStateDao(): TrackingRolloutStateDao

	abstract fun sourcePlanStateDao(): SourcePlanStateDao

	abstract fun sourceRuntimeStateDao(): SourceRuntimeStateDao

	abstract fun sourcePolicyDao(): SourcePolicyDao

	abstract fun sourceBrokerDao(): SourceBrokerDao

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
	 * Provides access to export log records.
	 */
	abstract fun exportLogDao(): ExportLogDao

	/** Provides durable job and entry receipts for resumable imports. */
	abstract fun importReceiptDao(): ImportReceiptDao

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

	companion object : ObjectBaseDatabase<AppDatabase>(AppDatabase::class.java) {
		// This filename remains stable for v27+; "main_database" is the retained v26 vault.
		override val databaseName: String = ACTIVE_DATABASE_NAME
		/** Frozen released chain used only to normalize a disposable legacy copy to v26. */
		internal val legacyPublicMigrationsThroughV26 = arrayOf(
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
		)
		/** Normal in-place migrations for the stable v27+ active database filename. */
		internal val activeMigrations: Array<Migration> = arrayOf(MIGRATION_27_28)

		override fun setupDatabase(database: Builder<AppDatabase>) {
			database.addMigrations(*activeMigrations)
		}

		override fun setupDatabase(context: Context, database: Builder<AppDatabase>) {
			super.setupDatabase(context, database)
			database.addCallback(
				LegacyImportRoomCallback(context, legacyPublicMigrationsThroughV26),
			)
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
				database.sourceEvidenceStateDao().recordFullDeletion(
					epoch = collectedDataEpoch,
					retainedFromMs = retainedFromMs,
					deletedSourceEventHighWaterOrdinal = sourceEventWalHighWater(database),
					updatedAtMs = updatedAtMs,
				)
				deleteCollectedRows(database)
			}
		}

		internal fun deleteAllCollectedData(database: AppDatabase) {
			database.runInTransaction {
				val sqlite = database.openHelper.writableDatabase
				val deletedSourceEventHighWaterOrdinal = sourceEventWalHighWater(database)
				sqlite.execSQL(
					"INSERT OR IGNORE INTO source_evidence_state " +
						"(id, revision, collected_data_epoch, retained_from_ms, " +
						"deleted_source_event_high_water_ordinal, updated_at_ms) " +
						"VALUES (1, 0, 0, NULL, 0, 0)",
				)
				val currentEpoch = sqlite.query(
					"SELECT collected_data_epoch FROM source_evidence_state WHERE id = 1",
				).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
				val nextEpoch = currentEpoch + 1L
				val updatedAtMs = System.currentTimeMillis()
				sqlite.execSQL(
					"UPDATE source_evidence_state SET collected_data_epoch = ?, revision = revision + 1, " +
						"deleted_source_event_high_water_ordinal = " +
						"MAX(deleted_source_event_high_water_ordinal, ?), " +
						"updated_at_ms = ? WHERE id = 1",
					arrayOf(nextEpoch, deletedSourceEventHighWaterOrdinal, updatedAtMs),
				)
				deleteCollectedRows(database)
			}
		}

		private fun sourceEventWalHighWater(database: AppDatabase): Long {
			val sqlite = database.openHelper.writableDatabase
			return sqlite.query(
				"SELECT MAX(" +
					"COALESCE((SELECT seq FROM sqlite_sequence " +
					"WHERE name = 'source_event_wal'), 0), " +
					"COALESCE((SELECT MAX(admission_ordinal) FROM source_event_wal), 0))",
			).use { cursor ->
				check(cursor.moveToFirst()) { "Unable to read source-event WAL high-water" }
				cursor.getLong(0)
			}
		}

		private fun deleteCollectedRows(database: AppDatabase) {
			// Source-event pipeline. Delete dependent state before immutable evidence.
			database.locationProjectionDao().deleteAllObservations()
			database.sourceBrokerDao().deleteAllAuthorizations()
			database.sourceBrokerDao().deleteAllRegistrations()
			database.sourceBrokerDao().deleteAllDemands()
			database.sourceProjectionStateDao().deleteAllProductLanes()
			database.sourceProjectionStateDao().deleteAllJoinState()
			database.sourceProjectionStateDao().deleteAllOutbox()
			database.sourceProjectionStateDao().deleteAllFailures()
			database.sourceProjectionStateDao().deleteAllCheckpoints()
			database.sourceProjectionStateDao().deleteAllRegistrations()
			database.sourceProjectionStateDao().deleteAllLeases()
			database.legacyV27ProjectionDrainDao().deleteAllTargets()
			database.legacyV27ProjectionDrainDao().deleteDrain()
			database.sourceRegistrationStateDao().deleteAll()
			database.sourcePlanStateDao().deleteAllAppliedStates()
			database.sourceRuntimeStateDao().deleteAll()
			database.sourceSessionDao().deleteAllCompleteness()
			database.activityAutomaticStartActionDao().deleteAll()
			database.sourceSessionDao().deleteAllLifecycleActions()
			database.sourceSessionDao().deleteAllLifecycleIntents()
			database.sourceSessionDao().deleteAllManifestSources()
			database.sourceSessionDao().deleteAllManifests()
			database.sourceSessionDao().deleteAllServiceRuns()
			database.sourceSessionDao().deleteAllSessions()
			database.sourceEventWalDao().deleteAll()

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

			// Historical reconstruction products
			database.trajectoryReconstructionDao().deleteAll()

			// Exploration tables
			database.explorationCellDao().deleteAll()
			database.explorationStreakDao().deleteAll()
			database.achievementProgressDao().deleteAll()

			// Export and import audit records. Keep the legacy-import marker so the
			// retained v26 vault is not imported again after a collected-data clear.
			database.exportLogDao().deleteAll()
			database.importReceiptDao().deleteAllEntriesExcept(LEGACY_IMPORT_JOB_ID)
			database.importReceiptDao().deleteAllJobsExcept(LEGACY_IMPORT_JOB_ID)
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
		}
	}
}
