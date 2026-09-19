package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.adsamcik.tracker.shared.base.database.steps.imported.preserveStepsFullClearFences
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.converter.CellTypeConverter
import com.adsamcik.tracker.shared.base.database.converter.DetectedActivityTypeConverter
import com.adsamcik.tracker.shared.base.database.converter.GeoFeaturePropertiesConverter
import com.adsamcik.tracker.shared.base.database.converter.SessionlessTypeConverter
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import com.adsamcik.tracker.shared.base.database.dao.ActivityCapturedFactDao
import com.adsamcik.tracker.shared.base.database.dao.ActivityAutomaticStartActionDao
import com.adsamcik.tracker.shared.base.database.dao.ActivityAutomationEpochDao
import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.AmbientStepsFactRevisionDao
import com.adsamcik.tracker.shared.base.database.dao.AmbientStepsImportStateDao
import com.adsamcik.tracker.shared.base.database.dao.AmbientCellFactDao
import com.adsamcik.tracker.shared.base.database.dao.AmbientWifiFactDao
import com.adsamcik.tracker.shared.base.database.dao.CellCapturedFactDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.CollectedDataDeletionOperationDao
import com.adsamcik.tracker.shared.base.database.dao.RetentionWorkExecutionReceiptDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.GeneralDao
import com.adsamcik.tracker.shared.base.database.dao.ImportReceiptDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.dao.LiveStatsDao
import com.adsamcik.tracker.shared.base.database.dao.LegacyV27ProjectionDrainDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDao
import com.adsamcik.tracker.shared.base.database.dao.LocationProjectionDao
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDecisionDao
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.StepFactRevisionDao
import com.adsamcik.tracker.shared.base.database.dao.StepsCountDomainReceiptDao
import com.adsamcik.tracker.shared.base.database.dao.StepsGoalEffectDao
import com.adsamcik.tracker.shared.base.database.dao.StepsGoalRepairDayDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedStepsDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiDao
import com.adsamcik.tracker.shared.base.database.dao.PressureFactRevisionDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.SourceDeletionFenceDao
import com.adsamcik.tracker.shared.base.database.dao.SourceDestinationOwnerDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerRunDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerStateEventDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.dao.TrajectoryReconstructionDao
import com.adsamcik.tracker.shared.base.database.dao.UnifiedGeoDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.dao.WifiCapturedFactDao
import com.adsamcik.tracker.shared.base.database.dao.XpLedgerDao
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellDeletionMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsNativeReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiDeletionMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedDeletedRunEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.database.data.RetentionWorkExecutionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.database.data.ImportEntryReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportJobReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellRunEntity
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
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainSchemaMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.shared.base.database.data.StepsGoalRepairDayEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsEntryEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsManifestEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureWindowEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureIdentityFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureSourceEraseEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureSourceEraseWitnessEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellTombstoneEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiTombstoneEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveDayEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsProtectedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsSourceFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityWindowEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityZoneEpochEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunZoneEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCaptureAdmissionBarrierEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
import com.adsamcik.tracker.shared.base.database.data.TrackerStateEvent
import com.adsamcik.tracker.shared.base.database.data.TrajectoryReconstructionRunEntity
import com.adsamcik.tracker.shared.base.database.data.TrajectorySourceLinkEntity
import com.adsamcik.tracker.shared.base.database.data.TrajectoryStateEntity
import com.adsamcik.tracker.shared.base.database.data.VisitIntervalEntity
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionProtectedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionReceiptEntity
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
import com.adsamcik.tracker.shared.base.database.dao.TrackingHistoryReadDao
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
import com.adsamcik.tracker.shared.base.database.dao.SourceCallerAuthorityDao
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEntity
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
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLiteOpenHelperFactory

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
			StepFactRevisionEntity::class,
			AmbientStepsFactRevisionEntity::class,
			StepsCountDomainReceiptEntity::class,
			StepsCountDomainOwnerRevisionEntity::class,
			StepsCountDomainCompletenessMarkerEntity::class,
			StepsCountDomainSchemaMarkerEntity::class,
			AmbientStepsRetentionAuthorityEntity::class,
			AmbientStepsNativeReplayFootprintEntity::class,
			AmbientStepsImportAuthorityTransitionEntity::class,
			AmbientStepsImportCursorEntity::class,
			AmbientStepsImportGapEntity::class,
			StepsGoalEffectEntity::class,
			StepsGoalRepairDayEntity::class,
			ImportedStepsEntryEntity::class,
			ImportedStepsRunEntity::class,
			ImportedStepsManifestEntity::class,
			ImportedAmbientStepsArchiveEntity::class,
			ImportedAmbientStepsReceiptEntity::class,
			ImportedAmbientStepsArchiveDayEntity::class,
			ImportedAmbientStepsDayRevisionEntity::class,
			ImportedAmbientStepsFactEntity::class,
			ImportedAmbientStepsGapEntity::class,
			ImportedAmbientStepsDayFenceEntity::class,
			ImportedAmbientStepsProtectedIdentityEntity::class,
			ImportedAmbientStepsSourceFenceEntity::class,
			ImportedPressureEntryRevisionEntity::class,
			ImportedPressureReceiptEntity::class,
			ImportedPressureRunEntity::class,
			ImportedPressureWindowEntity::class,
			ImportedPressureEntryDeletionEntity::class,
			ImportedPressureDeletionGenerationEntity::class,
			ImportedPressureRetentionReceiptEntity::class,
			ImportedPressureRetainedIdentityEntity::class,
			ImportedPressureIdentityFenceEntity::class,
			ImportedPressureSourceEraseEntity::class,
			ImportedPressureSourceEraseWitnessEntity::class,
			ImportedActivityEntryRevisionEntity::class,
			ImportedActivityReceiptEntity::class,
			ImportedActivityRunEntity::class,
			ImportedActivityZoneEpochEntity::class,
			ImportedActivityWindowEntity::class,
			ImportedActivityFragmentEntity::class,
			ImportedActivityRetentionReceiptEntity::class,
			ImportedActivityRetainedIdentityEntity::class,
			ImportedActivityEntryDeletionEntity::class,
			ImportedActivityEntryDeletionReceiptEntity::class,
			ImportedActivityDeletionGenerationEntity::class,
			PressureFactRevisionEntity::class,
			ActivityCapturedRegistrationPlanEntity::class,
			ActivityCapturedWindowRevisionEntity::class,
			ActivityCapturedFragmentEntity::class,
			ActivityCapturedEvidenceEntity::class,
			ActivityCapturedWindowCursorEntity::class,
			CellCapturedFactRevisionEntity::class,
			CellCapturedFactCursorEntity::class,
			CellCaptureDeletionGenerationEntity::class,
			CellCapturedEntryDeletionReceiptEntity::class,
			CellCapturedDeletedRunEntity::class,
			ImportedCellEntryRevisionEntity::class,
			ImportedCellReceiptEntity::class,
			ImportedCellRunEntity::class,
			ImportedCellObservationEntity::class,
			ImportedCellEntryDeletionEntity::class,
			ImportedCellDeletionGenerationEntity::class,
			ImportedCellEntryDeletionReceiptEntity::class,
			ImportedCellDeletedIdentityEntity::class,
			WifiCapturedFactRevisionEntity::class,
			WifiCapturedFactCursorEntity::class,
			WifiCaptureDeletionGenerationEntity::class,
			ImportedWifiEntryRevisionEntity::class,
			ImportedWifiReceiptEntity::class,
			ImportedWifiRunEntity::class,
			ImportedWifiRunZoneEntity::class,
			ImportedWifiObservationEntity::class,
			ImportedWifiEntryDeletionEntity::class,
			ImportedWifiDeletionGenerationEntity::class,
			WifiSelectedDeletionReceiptEntity::class,
			WifiSelectedDeletionProtectedIdentityEntity::class,
			AmbientWifiAuthorityEntity::class,
			AmbientWifiRetentionAuthorityEntity::class,
			AmbientWifiFactRevisionEntity::class,
			AmbientWifiFactCursorEntity::class,
			AmbientWifiGapEntity::class,
			AmbientWifiDeletionMarkerEntity::class,
			ImportedAmbientWifiFactEntity::class,
			ImportedAmbientWifiGapEntity::class,
			ImportedAmbientWifiReceiptEntity::class,
			ImportedAmbientWifiTombstoneEntity::class,
			AmbientWifiReplayFootprintEntity::class,
			AmbientCellAuthorityEntity::class,
			AmbientCellRetentionAuthorityEntity::class,
			AmbientCellFactRevisionEntity::class,
			AmbientCellFactCursorEntity::class,
			AmbientCellGapEntity::class,
			AmbientCellDeletionMarkerEntity::class,
			ImportedAmbientCellFactEntity::class,
			ImportedAmbientCellGapEntity::class,
			ImportedAmbientCellReceiptEntity::class,
			ImportedAmbientCellTombstoneEntity::class,
			AmbientCellReplayFootprintEntity::class,
			SourceDeletionFenceEntity::class,
			SourceDestinationOwnerEntity::class,
			ActivitySnapshot::class,
			CellSample::class,
			WifiObservation::class,
			TrackerRun::class,
			TrackerStateEvent::class,
			SourceEvidenceState::class,
			CollectedDataDeletionOperationEntity::class,
			RetentionWorkExecutionReceiptEntity::class,
			SourceEventWalEntity::class,
			SourceCaptureAdmissionBarrierEntity::class,
			SourceRunRetirementEntity::class,
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
			SourceCallerAcceptedAuthorityEntity::class,
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

	/** Provides append-only semantic revisions and contribution receipts for Steps. */
	abstract fun stepFactRevisionDao(): StepFactRevisionDao

	/** Provides append-only sessionless system-provider Steps aggregates. */
	abstract fun ambientStepsFactRevisionDao(): AmbientStepsFactRevisionDao

	/** Provides authenticated ownership of native Steps counter domains. */
	abstract fun stepsCountDomainReceiptDao(): StepsCountDomainReceiptDao

	/** Provides exact source-local Ambient Steps import progress and discontinuities. */
	abstract fun ambientStepsImportStateDao(): AmbientStepsImportStateDao

	/** Default-off sessionless Wi-Fi evidence; access alone creates no demand. */
	abstract fun ambientWifiFactDao(): AmbientWifiFactDao

	/** Default-off sessionless Cell evidence; access alone creates no demand. */
	abstract fun ambientCellFactDao(): AmbientCellFactDao

	/** Desired, revisioned source-qualified goal effects; no provider or award is started by access. */
	abstract fun stepsGoalEffectDao(): StepsGoalEffectDao

	/** Source-local dirty-day queue for bounded historical Steps goal correction. */
	abstract fun stepsGoalRepairDayDao(): StepsGoalRepairDayDao

	/** Dormant imported Steps metadata; this accessor does not grant import admission authority. */
	abstract fun importedStepsDao(): ImportedStepsDao

	/** Portable ambient origin only; no provider, capture, or consent authority is created. */
	abstract fun importedAmbientStepsDao(): ImportedAmbientStepsDao

	/** Dormant Pressure portable-origin storage; this accessor grants no import authority. */
	abstract fun importedPressureDao(): ImportedPressureDao

	/** Imported captured Activity product evidence; never a live source authority. */
	abstract fun importedActivityDao(): ImportedActivityDao

	/** Provides the dormant append-only source-qualified Pressure fact history. */
	abstract fun pressureFactRevisionDao(): PressureFactRevisionDao

	/** Dormant append-only captured Activity facts; this accessor does not activate acquisition. */
	abstract fun activityCapturedFactDao(): ActivityCapturedFactDao
	/** Dormant identity-free Cell captured-fact persistence. */
	abstract fun cellCapturedFactDao(): CellCapturedFactDao

	/** Cell-specific imported product storage; this accessor grants no live capture authority. */
	abstract fun importedCellDao(): ImportedCellDao
	/** Dormant identity-free Wi-Fi captured-fact persistence. */
	abstract fun wifiCapturedFactDao(): WifiCapturedFactDao

	/** Dormant imported captured Wi-Fi product evidence; never live source authority. */
	abstract fun importedWifiDao(): ImportedWifiDao

	/** Provides payload-free source/run deletion authority for source mutation paths. */
	abstract fun sourceDeletionFenceDao(): SourceDeletionFenceDao

	abstract fun sourceDestinationOwnerDao(): SourceDestinationOwnerDao

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

	abstract fun collectedDataDeletionOperationDao(): CollectedDataDeletionOperationDao

	abstract fun retentionWorkExecutionReceiptDao(): RetentionWorkExecutionReceiptDao

	abstract fun sourceEventWalDao(): SourceEventWalDao

	abstract fun sourceProjectionStateDao(): SourceProjectionStateDao

	abstract fun legacyV27ProjectionDrainDao(): LegacyV27ProjectionDrainDao

	abstract fun sourceRegistrationStateDao(): SourceRegistrationStateDao

	abstract fun sourceSessionDao(): SourceSessionDao

	/** Fixed-count, read-only composition inputs for source-qualified history. */
	abstract fun trackingHistoryReadDao(): TrackingHistoryReadDao

	abstract fun activityAutomaticStartActionDao(): ActivityAutomaticStartActionDao

	abstract fun activityAutomationEpochDao(): ActivityAutomationEpochDao

	abstract fun trackingRolloutStateDao(): TrackingRolloutStateDao

	abstract fun sourcePlanStateDao(): SourcePlanStateDao

	abstract fun sourceRuntimeStateDao(): SourceRuntimeStateDao

	abstract fun sourcePolicyDao(): SourcePolicyDao

	abstract fun sourceBrokerDao(): SourceBrokerDao

	abstract fun sourceCallerAuthorityDao(): SourceCallerAuthorityDao

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

		override fun delegateOpenHelperFactory(): SupportSQLiteOpenHelper.Factory =
			SQLiteXSupportSQLiteOpenHelperFactory(
				preserveDatabaseFilesOnCorruption = true,
			)

		/**
		 * Applies the common AppDatabase migrations and creation/open callbacks.
		 *
		 * Repository code that needs a directly configurable Room builder must start from
		 * [fileBuilder] or [inMemoryBuilder] rather than constructing AppDatabase through Room
		 * directly. That keeps the final-v28 schema authentication on every supported path.
		 */
		fun configureBuilder(database: Builder<AppDatabase>): Builder<AppDatabase> = database.apply {
			addMigrations(*activeMigrations)
			addCallback(TrackingOwnerValidationRoomCallback)
			addCallback(FinalV28SchemaAssemblyRoomCallback)
		}

		fun fileBuilder(context: Context, name: String): Builder<AppDatabase> =
			configureBuilder(
				Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name),
			)

		fun inMemoryBuilder(context: Context): Builder<AppDatabase> =
			configureBuilder(
				Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java),
			)

		override fun setupDatabase(database: Builder<AppDatabase>) {
			configureBuilder(database)
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
		): SupportSQLiteOpenHelper.Factory = DevelopmentV28ContainmentOpenHelperFactory(
			context = context,
			databaseName = databaseName,
			delegate = MigrationBackupOpenHelperFactory(
				delegate = delegate,
				backupStore = DatabaseMigrationBackupStore(context),
				databaseName = databaseName,
				targetVersion = CURRENT_DATABASE_VERSION,
			),
		)

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
		): CollectedDataDeletionOperationEntity = deleteAllCollectedData(
			context = context,
			operationId = "legacy-$collectedDataEpoch-$updatedAtMs",
			collectedDataEpoch = collectedDataEpoch,
			retainedFromMs = retainedFromMs,
			updatedAtMs = updatedAtMs,
		)

		suspend fun deleteAllCollectedData(
			context: Context,
			operationId: String,
			collectedDataEpoch: Long,
			retainedFromMs: Long?,
			updatedAtMs: Long,
		): CollectedDataDeletionOperationEntity {
			val database = database(context)
			val backupStore = DatabaseMigrationBackupStore(context)
			backupStore.markDeletionPending()
			val operation = deleteAllCollectedData(
				database,
				operationId,
				collectedDataEpoch,
				retainedFromMs,
				updatedAtMs,
			)
			backupStore.deleteAll()
			return operation
		}

		internal suspend fun deleteAllCollectedData(
			database: AppDatabase,
			operationId: String,
			collectedDataEpoch: Long,
			retainedFromMs: Long?,
			updatedAtMs: Long,
		): CollectedDataDeletionOperationEntity =
			database.withTransaction {
				database.collectedDataDeletionOperationDao().get(operationId)?.let { existing ->
					check(existing.targetCollectedDataEpoch == collectedDataEpoch)
					check(existing.retainedFromMs == retainedFromMs)
					check(existing.deletedAtMs == updatedAtMs)
					return@withTransaction existing
				}
				database.sourceEvidenceStateDao().ensure()
				val oldState = requireNotNull(database.sourceEvidenceStateDao().get())
				database.preserveAmbientStepsNativeReplayFootprintsForFullClearInCurrentTransaction(
					oldCollectedDataEpoch = oldState.collectedDataEpoch,
					newCollectedDataEpoch = collectedDataEpoch,
					protectedAtMs = updatedAtMs,
				)
				deleteAllCollectedDataInCurrentTransaction(
					database = database,
					operationId = operationId,
					oldState = oldState,
					newCollectedDataEpoch = collectedDataEpoch,
					retainedFromMs = retainedFromMs,
					updatedAtMs = updatedAtMs,
				)
			}

		suspend fun readCollectedDataDeletionOperation(
			context: Context,
			operationId: String,
		): CollectedDataDeletionOperationEntity? {
			val database = database(context)
			val operation = database.collectedDataDeletionOperationDao().get(operationId)
			if (operation != null) {
				DatabaseMigrationBackupStore(context).deleteAll()
			}
			return operation
		}

		internal suspend fun deleteAllCollectedData(
			database: AppDatabase,
			collectedDataEpoch: Long,
			retainedFromMs: Long?,
			updatedAtMs: Long,
		): CollectedDataDeletionOperationEntity = deleteAllCollectedData(
			database = database,
			operationId = "legacy-$collectedDataEpoch-$updatedAtMs",
			collectedDataEpoch = collectedDataEpoch,
			retainedFromMs = retainedFromMs,
			updatedAtMs = updatedAtMs,
		)

		internal suspend fun deleteAllCollectedData(database: AppDatabase) {
			database.sourceEvidenceStateDao().ensure()
			val oldState = requireNotNull(database.sourceEvidenceStateDao().get())
			val updatedAtMs = System.currentTimeMillis()
			deleteAllCollectedData(
				database = database,
				operationId = "legacy-${oldState.collectedDataEpoch + 1L}-$updatedAtMs",
				collectedDataEpoch = Math.addExact(oldState.collectedDataEpoch, 1L),
				retainedFromMs = oldState.retainedFromMs,
				updatedAtMs = updatedAtMs,
			)
		}

		private suspend fun deleteAllCollectedDataInCurrentTransaction(
			database: AppDatabase,
			operationId: String,
			oldState: SourceEvidenceState,
			newCollectedDataEpoch: Long,
			retainedFromMs: Long?,
			updatedAtMs: Long,
		): CollectedDataDeletionOperationEntity {
			require(operationId.isNotBlank())
			require(newCollectedDataEpoch > oldState.collectedDataEpoch)
			require(retainedFromMs == null || retainedFromMs >= 0L)
			require(updatedAtMs >= 0L)
			val nextRevision = Math.addExact(oldState.revision, 1L)
			val deletedSourceEventHighWaterOrdinal = maxOf(
				oldState.deletedSourceEventHighWaterOrdinal,
				sourceEventWalHighWater(database),
			)
			val nextRetainedFromMs = listOfNotNull(
				oldState.retainedFromMs,
				retainedFromMs,
			).maxOrNull()
			val sqlite = database.openHelper.writableDatabase
			val importedAmbientStepsDao = database.importedAmbientStepsDao()

			preserveImportedPressureFullClearAuthority(
				sqlite = sqlite,
				expectedCollectedDataEpoch = oldState.collectedDataEpoch,
				fencedAtMs = updatedAtMs,
			)
			preserveStepsFullClearFences(
				sqlite = sqlite,
				oldCollectedDataEpoch = oldState.collectedDataEpoch,
				newCollectedDataEpoch = newCollectedDataEpoch,
				deletedAtMs = updatedAtMs,
			)
			preserveWifiFullClearAuthority(
				sqlite = sqlite,
				oldCollectedDataEpoch = oldState.collectedDataEpoch,
				newCollectedDataEpoch = newCollectedDataEpoch,
				newRetainedFromMs = nextRetainedFromMs,
				clearedAtMs = updatedAtMs,
			)
			preserveCellFullClearAuthority(
				database = database,
				sqlite = sqlite,
				oldCollectedDataEpoch = oldState.collectedDataEpoch,
				newCollectedDataEpoch = newCollectedDataEpoch,
				newRetainedFromMs = nextRetainedFromMs,
				clearedAtMs = updatedAtMs,
			)
			check(
				database.clearAmbientWifiProductInCurrentFullClearTransaction(
					oldState.collectedDataEpoch,
					updatedAtMs,
				) is AmbientWifiDeletionResult.Deleted,
			) { "Ambient Wi-Fi full clear could not preserve replay authority" }
			check(
				database.clearAmbientCellProductInCurrentFullClearTransaction(
					oldState.collectedDataEpoch,
					updatedAtMs,
				) is AmbientCellDeletionResult.Deleted,
			) { "Ambient Cell full clear could not preserve replay authority" }
			importedAmbientStepsDao.prepareFullClearFencesInCurrentTransaction(
				oldCollectedDataEpoch = oldState.collectedDataEpoch,
				newCollectedDataEpoch = newCollectedDataEpoch,
				sourceEvidenceRevision = nextRevision,
				clearedAtMs = updatedAtMs,
			)
			check(
				clearStepsCountDomainEvidenceInCurrentTransaction(
					database,
					StepsCountDomainFullClearMode.PRESERVE_TERMINAL,
				) is StepsCountDomainMaintenanceResult.Applied,
			) { "Steps count-domain full clear could not preserve terminal authority" }
			publishFullDeletionState(
				database = database,
				oldState = oldState,
				newCollectedDataEpoch = newCollectedDataEpoch,
				newRevision = nextRevision,
				retainedFromMs = nextRetainedFromMs,
				deletedSourceEventHighWaterOrdinal = deletedSourceEventHighWaterOrdinal,
				updatedAtMs = updatedAtMs,
			)
			importedAmbientStepsDao.deleteFullClearPayloadInCurrentTransaction(
				newCollectedDataEpoch,
			)
			database.retentionWorkExecutionReceiptDao()
				.supersedeOpenExecutions(updatedAtMs)
			deleteCollectedRows(database)
			return CollectedDataDeletionOperationEntity(
				operationId = operationId,
				targetCollectedDataEpoch = newCollectedDataEpoch,
				retainedFromMs = nextRetainedFromMs,
				deletedAtMs = updatedAtMs,
				phase = CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED,
				updatedAtMs = updatedAtMs,
			).also { operation ->
				database.collectedDataDeletionOperationDao().deleteAllExcept(operationId)
				database.collectedDataDeletionOperationDao().insert(operation)
			}
		}

		private fun publishFullDeletionState(
			database: AppDatabase,
			oldState: SourceEvidenceState,
			newCollectedDataEpoch: Long,
			newRevision: Long,
			retainedFromMs: Long?,
			deletedSourceEventHighWaterOrdinal: Long,
			updatedAtMs: Long,
		) {
			database.openHelper.writableDatabase.compileStatement(
				"UPDATE source_evidence_state SET revision = ?, collected_data_epoch = ?, " +
					"retained_from_ms = ?, deleted_source_event_high_water_ordinal = ?, " +
					"updated_at_ms = ? WHERE id = ? AND revision = ? " +
					"AND collected_data_epoch = ?",
			).use { statement ->
				statement.bindLong(1, newRevision)
				statement.bindLong(2, newCollectedDataEpoch)
				if (retainedFromMs == null) {
					statement.bindNull(3)
				} else {
					statement.bindLong(3, retainedFromMs)
				}
				statement.bindLong(4, deletedSourceEventHighWaterOrdinal)
				statement.bindLong(5, updatedAtMs)
				statement.bindLong(6, oldState.id.toLong())
				statement.bindLong(7, oldState.revision)
				statement.bindLong(8, oldState.collectedDataEpoch)
				check(statement.executeUpdateDelete() == 1) {
					"Unable to publish full collected-data deletion"
				}
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
			database.openHelper.writableDatabase.execSQL("DELETE FROM source_capture_admission_barrier")
			database.openHelper.writableDatabase.execSQL("DELETE FROM source_run_retirement")
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
			database.sourceCallerAuthorityDao().deleteAll()
			database.sourceEventWalDao().deleteAll()

			// Sessionless architecture tables
			database.locationSampleDao().deleteAll()
			database.locationObservationDao().deleteAll()
			database.locationObservationDecisionDao().deleteAll()
			// Payload-free source fences deliberately survive repeated full clears. Imported
			// Ambient Steps payload was authenticated and removed before this common cascade; its
			// day fences, protected identities, and source fence remain as deletion authority.
			database.stepFactRevisionDao().deleteAll()
			database.ambientStepsFactRevisionDao().deleteAllRetentionAuthorities()
			database.ambientStepsFactRevisionDao().deleteAll()
			database.ambientStepsImportStateDao().deleteAllAuthorityTransitions()
			database.ambientStepsImportStateDao().deleteAllGaps()
			database.ambientStepsImportStateDao().deleteAllCursors()
			database.stepsGoalEffectDao().deleteAll()
			database.stepsGoalRepairDayDao().deleteAll()
			database.importedStepsDao().deleteAll()
			database.importedPressureDao().deleteAllReceipts()
			database.importedPressureDao().deleteAllEntries()
			database.importedPressureDao().deleteAllRetainedIdentities()
			database.importedPressureDao().deleteAllRetentionReceipts()
			database.importedActivityDao().deleteAllReceipts()
			database.importedActivityDao().deleteAllEntries()
			database.importedActivityDao().deleteAllRetainedIdentities()
			database.importedActivityDao().deleteAllRetentionReceipts()
			database.importedActivityDao().deleteAllEntryDeletionReceipts()
			database.importedActivityDao().deleteAllEntryDeletions()
			database.importedActivityDao().deleteAllDeletionGenerations()
			database.pressureFactRevisionDao().deleteAll()
			database.activityCapturedFactDao().deleteAllEvidence()
			database.activityCapturedFactDao().deleteAllFragments()
			database.activityCapturedFactDao().deleteAllCursors()
			database.activityCapturedFactDao().deleteAllRevisions()
			database.activityCapturedFactDao().deleteAllRegistrationPlanBindings()
			database.cellCapturedFactDao().deleteAllCursors()
			database.cellCapturedFactDao().deleteAllRevisions()
			database.importedCellDao().deleteAllReceipts()
			database.importedCellDao().deleteAllEntries()
			database.wifiCapturedFactDao().deleteAllCursors()
			// Wi-Fi coverage revisions reference aggregate owners through an immediate RESTRICT FK.
			database.wifiCapturedFactDao().deleteAllDependentRevisions()
			database.wifiCapturedFactDao().deleteAllRevisions()
			database.importedWifiDao().deleteAllReceipts()
			database.importedWifiDao().deleteAllEntryRevisions()
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
			database.importedPressureDao().deleteSourceEraseWitnesses()
			database.importedPressureDao().deleteSourceErase()
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
