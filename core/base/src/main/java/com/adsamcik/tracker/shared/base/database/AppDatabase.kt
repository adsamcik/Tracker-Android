package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedRadioFileMembershipDao
import com.adsamcik.tracker.shared.base.database.dao.LocationFileIdentityDao
import com.adsamcik.tracker.shared.base.database.dao.LocationPointCorrectionDao
import com.adsamcik.tracker.shared.base.database.dao.OrganizationAnnotationDao
import com.adsamcik.tracker.shared.base.database.data.EffectiveLocationSample
import com.adsamcik.tracker.shared.base.database.data.ImportedRadioFileMembership
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchivePointEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationPointCorrection
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.data.NetworkOperator
import com.adsamcik.tracker.shared.base.database.data.LegacyRejectedTrackerSession
import com.adsamcik.tracker.shared.base.database.data.LegacyLocationWifiCount
import com.adsamcik.tracker.shared.base.database.dao.LegacyPreservedDataDao
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
import com.adsamcik.tracker.shared.base.database.dao.LocationPurposeDao
import com.adsamcik.tracker.shared.base.database.data.LocationRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSamplePurposeAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.dao.AmbientWifiFactDao
import com.adsamcik.tracker.shared.base.database.dao.CellCapturedFactDao
import com.adsamcik.tracker.shared.base.database.dao.RadioObservationChildDao
import com.adsamcik.tracker.shared.base.database.dao.CapturedRadioFactRetentionProofDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.CollectedDataDeletionOperationDao
import com.adsamcik.tracker.shared.base.database.dao.RetentionWorkExecutionReceiptDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.GeneralDao
import com.adsamcik.tracker.shared.base.database.dao.ImportReceiptDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedRadioChildDao
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
import com.adsamcik.tracker.shared.base.database.dao.ImportedPortableStepsCountDomainDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiDao
import com.adsamcik.tracker.shared.base.database.dao.PressureFactRevisionDao
import com.adsamcik.tracker.shared.base.database.dao.PressureFactRetentionAuthorityDao
import com.adsamcik.tracker.shared.base.database.dao.PressureFactRetentionRevisionAuthorityDao
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
import com.adsamcik.tracker.shared.base.database.data.AmbientCellChildEntity
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
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedDeletedRunEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity
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
import com.adsamcik.tracker.shared.base.database.data.ImportedCellChildEntity
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
import com.adsamcik.tracker.shared.base.database.data.PressureFactRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRetentionRevisionAuthorityEntity
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
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellTombstoneEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiChildEntity
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
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainRootEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFileReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFullClearBindingStageEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFullClearExplicitLineageStageEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFullClearOwnerRevisionStageEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFullClearOwnerStageEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFullClearSessionProductStageEntity
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
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiChildEntity
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
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedChildEntity
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
import com.adsamcik.tracker.shared.base.database.dao.SourceEventRetentionProofDao
import com.adsamcik.tracker.shared.base.database.dao.SourceEventRetentionScopeProofDao
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
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionScopeProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionCheckpointEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.NativePortableOwnershipEntity
import com.adsamcik.tracker.shared.base.database.dao.NativePortableOwnershipDao
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
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLiteOpenHelperFactory

/** The new active filename began at this unreleased schema; released `main` used legacy v12. */
internal const val INITIAL_ACTIVE_DATABASE_VERSION = 27
internal const val LAST_RELEASED_LEGACY_DATABASE_VERSION = 12

/** Current development schema. Versions 28 and 29 have not shipped. */
internal const val CURRENT_DATABASE_VERSION = 30

/**
 * Provides access to main database.
 * Contains only common data nothing module specific.
 *
 * CURRENT VERSION: 29 (UNRELEASED)
 *
 * Released `main` used v12 in `main_database`. Versions 13-29 are development schemas.
 * Version 27 introduced the new active filename; its recognized local files migrate in place.
 * See AppDatabaseMigrations.kt for the full history and migration rules.
 */
@Database(
		version = CURRENT_DATABASE_VERSION,
		views = [EffectiveLocationSample::class],
		entities = [
			OrganizationAnnotationEntity::class,
			// Core reference entities
			SessionActivity::class,
			NetworkOperator::class,
			LegacyRejectedTrackerSession::class,
			LegacyLocationWifiCount::class,
			// Sessionless architecture entities
			LocationSample::class,
			LocationPointCorrection::class,
			ImportedRadioFileMembership::class,
			LocationFileIdentityEntity::class,
			LocationFileArchiveEntity::class,
			LocationFileArchivePointEntity::class,
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
			ImportedPortableStepsCountDomainGraphEntity::class,
			ImportedPortableStepsCountDomainReceiptEntity::class,
			ImportedPortableStepsCountDomainOwnerRevisionEntity::class,
			ImportedPortableStepsCountDomainCompletenessEntity::class,
			ImportedPortableStepsCountDomainRootEntity::class,
			ImportedPortableStepsCountDomainBindingEntity::class,
			ImportedPortableStepsFileReceiptEntity::class,
			ImportedPortableStepsCountDomainOwnerFenceEntity::class,
			ImportedPortableStepsFullClearBindingStageEntity::class,
			ImportedPortableStepsFullClearSessionProductStageEntity::class,
			ImportedPortableStepsFullClearOwnerStageEntity::class,
			ImportedPortableStepsFullClearOwnerRevisionStageEntity::class,
			ImportedPortableStepsFullClearExplicitLineageStageEntity::class,
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
			PressureFactRetentionAuthorityEntity::class,
			PressureFactRetentionRevisionAuthorityEntity::class,
			ActivityCapturedRegistrationPlanEntity::class,
			ActivityCapturedWindowRevisionEntity::class,
			NativePortableOwnershipEntity::class,
			ActivityCapturedFragmentEntity::class,
			ActivityCapturedEvidenceEntity::class,
			ActivityCapturedWindowCursorEntity::class,
			CellCapturedFactRevisionEntity::class,
			CellCapturedChildEntity::class,
			CellCapturedFactCursorEntity::class,
			CapturedRadioFactRetentionProofEntity::class,
			CellCaptureDeletionGenerationEntity::class,
			CellCapturedEntryDeletionReceiptEntity::class,
			CellCapturedDeletedRunEntity::class,
			ImportedCellEntryRevisionEntity::class,
			ImportedCellReceiptEntity::class,
			ImportedCellRunEntity::class,
			ImportedCellObservationEntity::class,
			ImportedCellChildEntity::class,
			ImportedCellEntryDeletionEntity::class,
			ImportedCellDeletionGenerationEntity::class,
			ImportedCellEntryDeletionReceiptEntity::class,
			ImportedCellDeletedIdentityEntity::class,
			WifiCapturedFactRevisionEntity::class,
			WifiCapturedChildEntity::class,
			WifiCapturedFactCursorEntity::class,
			WifiCaptureDeletionGenerationEntity::class,
			ImportedWifiEntryRevisionEntity::class,
			ImportedWifiReceiptEntity::class,
			ImportedWifiRunEntity::class,
			ImportedWifiRunZoneEntity::class,
			ImportedWifiObservationEntity::class,
			ImportedWifiChildEntity::class,
			ImportedWifiEntryDeletionEntity::class,
			ImportedWifiDeletionGenerationEntity::class,
			WifiSelectedDeletionReceiptEntity::class,
			WifiSelectedDeletionProtectedIdentityEntity::class,
			AmbientWifiAuthorityEntity::class,
			AmbientWifiRetentionAuthorityEntity::class,
			AmbientLocationRetentionAuthorityEntity::class,
			LocationRegistrationPlanEntity::class,
			LocationAcceptedReceiptEntity::class,
			LocationSamplePurposeAuthorityEntity::class,
			AmbientWifiFactRevisionEntity::class,
			AmbientWifiChildEntity::class,
			AmbientWifiFactCursorEntity::class,
			AmbientWifiGapEntity::class,
			AmbientWifiDeletionMarkerEntity::class,
			ImportedAmbientWifiFactEntity::class,
			ImportedAmbientWifiChildEntity::class,
			ImportedAmbientWifiGapEntity::class,
			ImportedAmbientWifiReceiptEntity::class,
			ImportedAmbientWifiTombstoneEntity::class,
			AmbientWifiReplayFootprintEntity::class,
			AmbientCellAuthorityEntity::class,
			AmbientCellRetentionAuthorityEntity::class,
			AmbientCellFactRevisionEntity::class,
			AmbientCellChildEntity::class,
			AmbientCellFactCursorEntity::class,
			AmbientCellGapEntity::class,
			AmbientCellDeletionMarkerEntity::class,
			ImportedAmbientCellFactEntity::class,
			ImportedAmbientCellChildEntity::class,
			ImportedAmbientCellGapEntity::class,
			ImportedAmbientCellReceiptEntity::class,
			ImportedAmbientCellTombstoneEntity::class,
			AmbientCellReplayFootprintEntity::class,
			SourceDeletionFenceEntity::class,
			SourceDestinationOwnerEntity::class,
			ActivitySnapshot::class,
			CellSample::class,
			LegacyCellChildEntity::class,
			WifiObservation::class,
			LegacyWifiChildEntity::class,
			TrackerRun::class,
			TrackerStateEvent::class,
			SourceEvidenceState::class,
			CollectedDataDeletionOperationEntity::class,
			RetentionWorkExecutionReceiptEntity::class,
			SourceEventWalEntity::class,
			SourceEventRetentionProofEntity::class,
			SourceEventRetentionScopeProofEntity::class,
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
	 * Accesses released data fields retained when no modern typed observation represents their full
	 * original content.
	 */
	abstract fun legacyPreservedDataDao(): LegacyPreservedDataDao

	/**
	 * Accesses optional session labels and grouping intervals over existing observations.
	 */
	abstract fun organizationAnnotationDao(): OrganizationAnnotationDao

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
	 * Accesses the current user/import correction for an original saved Location point.
	 */
	abstract fun locationPointCorrectionDao(): LocationPointCorrectionDao
	/**
	 * Accesses original per-file Wi-Fi/Cell reading membership for selected-date and overlapping
	 * imports.
	 */
	abstract fun importedRadioFileMembershipDao(): ImportedRadioFileMembershipDao

	/**
	 * Accesses original Location file/point identity membership used for duplicate and conflict
	 * resolution.
	 */
	abstract fun locationFileIdentityDao(): LocationFileIdentityDao

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
	/**
	 * Accesses independently saved purpose associations and retained provenance of canonical Location
	 * points.
	 */
	abstract fun locationPurposeDao(): LocationPurposeDao

	/** Default-off sessionless Cell evidence; access alone creates no demand. */
	abstract fun ambientCellFactDao(): AmbientCellFactDao
	/**
	 * Accesses actual network/tower detail children of native and all-day radio observations.
	 */
	abstract fun radioObservationChildDao(): RadioObservationChildDao
	/**
	 * Accesses actual network/tower detail children retained from portable radio imports.
	 */
	abstract fun importedRadioChildDao(): ImportedRadioChildDao

	/** Desired, revisioned source-qualified goal effects; no provider or award is started by access. */
	abstract fun stepsGoalEffectDao(): StepsGoalEffectDao

	/** Source-local dirty-day queue for bounded historical Steps goal correction. */
	abstract fun stepsGoalRepairDayDao(): StepsGoalRepairDayDao

	/** Dormant imported Steps metadata; this accessor does not grant import admission authority. */
	abstract fun importedStepsDao(): ImportedStepsDao

	/** Portable ambient origin only; no provider, capture, or consent authority is created. */
	abstract fun importedAmbientStepsDao(): ImportedAmbientStepsDao

	/** Authenticated imported-only count-domain evidence; never native collection authority. */
	abstract fun importedPortableStepsCountDomainDao(): ImportedPortableStepsCountDomainDao

	/** Dormant Pressure portable-origin storage; this accessor grants no import authority. */
	abstract fun importedPressureDao(): ImportedPressureDao

	/** Imported captured Activity product evidence; never a live source authority. */
	abstract fun importedActivityDao(): ImportedActivityDao

	/** Provides the dormant append-only source-qualified Pressure fact history. */
	abstract fun pressureFactRevisionDao(): PressureFactRevisionDao

	/** Value-free terminal authority for native Pressure fact lineages removed by retention. */
	abstract fun pressureFactRetentionAuthorityDao(): PressureFactRetentionAuthorityDao

	/** Value-free per-revision identity authority for retained native Pressure correction lineages. */
	abstract fun pressureFactRetentionRevisionAuthorityDao(): PressureFactRetentionRevisionAuthorityDao

	/** Dormant append-only captured Activity facts; this accessor does not activate acquisition. */
	abstract fun activityCapturedFactDao(): ActivityCapturedFactDao

	/**
	 * Accesses the original native recording/import ownership used to resolve portable identity
	 * collisions.
	 */
	abstract fun nativePortableOwnershipDao(): NativePortableOwnershipDao
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

	/**
	 * Accesses durable full-clear and retention-settlement operation progress and exact ownership.
	 */
	abstract fun collectedDataDeletionOperationDao(): CollectedDataDeletionOperationDao

	/**
	 * Accesses original WorkManager retention attempt receipts, plans and cancellation state.
	 */
	abstract fun retentionWorkExecutionReceiptDao(): RetentionWorkExecutionReceiptDao

	abstract fun sourceEventWalDao(): SourceEventWalDao

	/**
	 * Accesses retained event-level source evidence needed after operational WAL pruning.
	 */
	abstract fun sourceEventRetentionProofDao(): SourceEventRetentionProofDao

	/**
	 * Accesses retained scope-level source authority after operational WAL pruning.
	 */
	abstract fun sourceEventRetentionScopeProofDao(): SourceEventRetentionScopeProofDao

	/**
	 * Accesses original retained Wi-Fi/Cell fact proof and represented reading membership.
	 */
	abstract fun capturedRadioFactRetentionProofDao(): CapturedRadioFactRetentionProofDao

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

	/**
	 * Accesses local caller registrations used to admit exact lifecycle actions.
	 */
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
		/** Preserved chain used only to normalize a disposable copy of released v12 data to v26. */
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
		internal val activeMigrations: Array<Migration> = arrayOf(MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30)

		override fun delegateOpenHelperFactory(): SupportSQLiteOpenHelper.Factory =
			SQLiteXSupportSQLiteOpenHelperFactory(
				preserveDatabaseFilesOnCorruption = true,
			)

		/**
		 * Applies the common AppDatabase migrations and creation/open callbacks.
		 *
		 * Repository code that needs a directly configurable Room builder must start from
		 * [fileBuilder] or [inMemoryBuilder] rather than constructing AppDatabase through Room
		 * directly. This keeps migrations and the exact Steps schema callbacks on those paths.
		 * Production file containment and migration backup are supplied by [openHelperFactory];
		 * a configurable file builder does not install that factory automatically.
		 */
		fun configureBuilder(database: Builder<AppDatabase>): Builder<AppDatabase> = database.apply {
			addMigrations(*activeMigrations)
			addCallback(TrackingOwnerValidationRoomCallback)
			addCallback(FinalV28SchemaAssemblyRoomCallback)
			addCallback(RadioObservationChildRoomCallback)
		}

		/**
		 * Creates a file-backed Room builder with the existing schema migrations and integrity
		 * callbacks; it does not open the database yet.
		 */
		fun fileBuilder(context: Context, name: String): Builder<AppDatabase> =
			configureBuilder(
				Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name),
			)

		/** Uses the same released-data importer on a staged backup, without altering live settings. */
		fun releasedBackupCopyBuilder(context: Context, name: String, legacyFile: java.io.File): Builder<AppDatabase> =
			fileBuilder(context, name).addCallback(LegacyImportRoomCallback(
				context, legacyPublicMigrationsThroughV26, sourceFileOverride = legacyFile))

		/**
		 * Creates an in-memory Room builder with the same schema and integrity callbacks as the
		 * file-backed database.
		 */
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


	}
}
