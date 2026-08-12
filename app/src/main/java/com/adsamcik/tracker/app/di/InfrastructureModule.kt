package com.adsamcik.tracker.app.di

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import com.adsamcik.tracker.app.tracebox.TrackerTraceboxHandleProvider
import com.adsamcik.tracker.network.DefaultNetworkGateway
import com.adsamcik.tracker.network.NetworkGateway
import com.adsamcik.tracker.app.settings.CollectedDataDeletionService
import com.adsamcik.tracker.app.settings.CollectedDataWriterQuiescer
import com.adsamcik.tracker.app.settings.DefaultCollectedDataDeletionService
import com.adsamcik.tracker.app.settings.DefaultCollectedDataWriterQuiescer
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.impexp.exporter.automation.ExportAutomationController
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupRepository
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupStore
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseRepository
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.DomainEventDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.GeneralDao
import com.adsamcik.tracker.shared.base.database.dao.LiveStatsDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDao
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDecisionDao
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalClaimDao
import com.adsamcik.tracker.shared.base.database.dao.QuarantinedSignalDao
import com.adsamcik.tracker.shared.base.database.dao.PlayerProfileDao
import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.SkiRunSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerRunDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerStateEventDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.dao.TrajectoryReconstructionDao
import com.adsamcik.tracker.shared.base.database.dao.UnifiedGeoDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.dao.XpLedgerDao
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.di.DefaultDispatcher
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.base.time.SystemClock
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import com.adsamcik.tracker.tracker.service.ActivityWatcherController
import com.adsamcik.tracker.shared.base.location.DefaultUiLocationProvider
import com.adsamcik.tracker.shared.base.location.UiLocationProvider
import com.google.android.gms.location.LocationServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton
import dev.tracebox.api.DeleteReport
import dev.tracebox.api.DeleteRequest
import kotlinx.coroutines.withContext

/**
 * Hilt module providing core infrastructure dependencies.
 * These are application-scoped singletons used across the entire app.
 * 
 * Per copilot-instructions Section 16A:
 * - Stable abstractions for time, dispatchers, coroutine scopes
 * - Single source of truth for database instance
 */
@Module
@InstallIn(SingletonComponent::class)
object InfrastructureModule {

    @Provides
    @Singleton
    fun provideUiLocationProvider(
        @ApplicationContext context: Context,
    ): UiLocationProvider = DefaultUiLocationProvider(
        client = LocationServices.getFusedLocationProviderClient(context),
        processLifecycle = ProcessLifecycleOwner.get().lifecycle,
    )

    /**
     * Provides the coroutine dispatchers abstraction.
     * Use for all coroutine dispatcher access to enable test injection.
     */
    @Provides
    @Singleton
    fun provideDispatchersProvider(): DispatchersProvider = DefaultDispatchersProvider

    /**
     * Provides the IO dispatcher for database/file operations.
     */
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Provides the Default dispatcher for CPU-intensive work.
     */
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * Provides the clock abstraction.
     * Use for all time access to enable deterministic testing.
     */
    @Provides
    @Singleton
    fun provideClock(): Clock = SystemClock

    /**
     * Provides application-scoped CoroutineScope for DI injection.
     *
     * This scope is intentionally tied to the application's process lifetime.
     * It lives for the entire duration of the app process and requires no explicit
     * cancellation, as it will be cleaned up when the process terminates.
     *
     * Use for long-running operations that outlive individual components (ViewModels,
     * Activities, Services). Uses [SupervisorJob] to prevent failure propagation
     * between independent child coroutines.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideAppScope(
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
    ): CoroutineScope =
        CoroutineScope(SupervisorJob() + defaultDispatcher)

    /**
     * Provides the Room database instance.
     * Singleton to ensure single database connection pool.
     */
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.database(context)

    @Provides
    @Singleton
    fun provideDatabaseMigrationBackupRepository(
        @ApplicationContext context: Context,
    ): DatabaseMigrationBackupRepository = DatabaseMigrationBackupStore(context)

    @Provides
    @Singleton
    fun provideLegacyDatabaseRepository(
        @ApplicationContext context: Context,
    ): LegacyDatabaseRepository = LegacyDatabaseRepository(context)

    @Provides
    @Singleton
    fun provideCollectedDataWriterQuiescer(
        @ApplicationContext context: Context,
        trackerStateReader: TrackerStateReader,
        activityWatcherController: ActivityWatcherController,
        exportAutomationController: ExportAutomationController,
    ): CollectedDataWriterQuiescer = DefaultCollectedDataWriterQuiescer(
        context = context,
        trackerStateReader = trackerStateReader,
        activityWatcherController = activityWatcherController,
        exportAutomationController = exportAutomationController,
    )

    @Provides
    @Singleton
    fun provideCollectedDataDeletionService(
        @ApplicationContext context: Context,
        pointsDatabase: PointsDatabase,
        exportPlanStore: ExportPlanStore,
        writerQuiescer: CollectedDataWriterQuiescer,
		collectedDataLifecycleStore: CollectedDataLifecycleStore,
		activityRegistrationArbiter: ActivityRegistrationArbiter,
        dispatchersProvider: DispatchersProvider,
        traceboxHandleProvider: TrackerTraceboxHandleProvider,
    ): CollectedDataDeletionService = DefaultCollectedDataDeletionService(
        context = context,
        pointsAwardedDao = pointsDatabase.pointsAwardedDao(),
        exportPlanStore = exportPlanStore,
        writerQuiescer = writerQuiescer,
        collectedDataLifecycleStore = collectedDataLifecycleStore,
		activityRegistrationArbiter = activityRegistrationArbiter,
        traceboxDataDeletion = {
            withContext(dispatchersProvider.io) {
                traceboxHandleProvider.handle.delete(DeleteRequest.ALL_TRACEBOX_DATA) ==
                    DeleteReport.COMPLETE
            }
        },
    )

    // DAO Providers - enable direct DAO injection without going through AppDatabase.
    // DAOs are lightweight proxies to the singleton database and do not need @Singleton scoping.

    @Provides
    fun provideActivityDao(database: AppDatabase): ActivityDao = database.activityDao()

    @Provides
    fun provideGeneralDao(database: AppDatabase): GeneralDao = database.generalDao()

    @Provides
    fun provideUnifiedGeoDao(database: AppDatabase): UnifiedGeoDao = database.unifiedGeoDao()

    @Provides
    fun provideLocationSampleDao(database: AppDatabase): LocationSampleDao = database.locationSampleDao()

    @Provides
    fun provideLocationObservationDao(database: AppDatabase): LocationObservationDao =
        database.locationObservationDao()

    @Provides
    fun provideLocationObservationDecisionDao(database: AppDatabase): LocationObservationDecisionDao =
        database.locationObservationDecisionDao()

    @Provides
    fun provideSourceEvidenceStateDao(database: AppDatabase): SourceEvidenceStateDao =
        database.sourceEvidenceStateDao()

    @Provides
    fun provideStepIntervalDao(database: AppDatabase): StepIntervalDao = database.stepIntervalDao()

    @Provides
    fun provideActivitySnapshotDao(database: AppDatabase): ActivitySnapshotDao = database.activitySnapshotDao()

    @Provides
    fun provideCellSampleDao(database: AppDatabase): CellSampleDao = database.cellSampleDao()

    @Provides
    fun provideWifiObservationDao(database: AppDatabase): WifiObservationDao = database.wifiObservationDao()

    @Provides
    fun provideTrackerRunDao(database: AppDatabase): TrackerRunDao = database.trackerRunDao()

    @Provides
    fun provideTrackerStateEventDao(database: AppDatabase): TrackerStateEventDao =
        database.trackerStateEventDao()

    @Provides
    fun provideSessionSegmentDao(database: AppDatabase): SessionSegmentDao = database.sessionSegmentDao()

    @Provides
    fun provideTripDao(database: AppDatabase): TripDao = database.tripDao()

    @Provides
    fun provideDailySummaryDao(database: AppDatabase): DailySummaryDao = database.dailySummaryDao()

    @Provides
    fun provideLiveStatsDao(database: AppDatabase): LiveStatsDao = database.liveStatsDao()

    @Provides
    fun provideTrajectoryReconstructionDao(database: AppDatabase): TrajectoryReconstructionDao =
        database.trajectoryReconstructionDao()

    @Provides
    fun provideExplorationCellDao(database: AppDatabase): ExplorationCellDao = database.explorationCellDao()

    @Provides
    fun provideExplorationStreakDao(database: AppDatabase): ExplorationStreakDao = database.explorationStreakDao()

    @Provides
    fun provideAchievementProgressDao(database: AppDatabase): AchievementProgressDao = database.achievementProgressDao()

    @Provides
    fun provideExportLogDao(database: AppDatabase): ExportLogDao = database.exportLogDao()

    @Provides
    fun provideDomainEventDao(database: AppDatabase): DomainEventDao = database.domainEventDao()

    @Provides
    fun providePressureSampleDao(database: AppDatabase): PressureSampleDao = database.pressureSampleDao()

    @Provides
    fun provideSkiRunSegmentDao(database: AppDatabase): SkiRunSegmentDao = database.skiRunSegmentDao()

    @Provides
    fun providePendingSignalDao(database: AppDatabase): PendingSignalDao = database.pendingSignalDao()

    @Provides
    fun providePendingSignalClaimDao(database: AppDatabase): PendingSignalClaimDao =
        database.pendingSignalClaimDao()

    @Provides
    fun provideQuarantinedSignalDao(database: AppDatabase): QuarantinedSignalDao =
        database.quarantinedSignalDao()

    @Provides
    fun provideXpLedgerDao(database: AppDatabase): XpLedgerDao = database.xpLedgerDao()

    @Provides
    fun providePlayerProfileDao(database: AppDatabase): PlayerProfileDao = database.playerProfileDao()

    @Provides
    fun provideMiniGameScoreDao(database: AppDatabase): MiniGameScoreDao = database.miniGameScoreDao()

    /**
     * Provides the singleton [NetworkGateway] — the ONLY allowed network egress
     * point in the app. Defaults: kill switch OFF, deny-all allowlist; the
     * gateway is then driven by [com.adsamcik.tracker.network.NetworkPolicyAggregator],
     * which composes contributions from every Hilt-registered
     * [com.adsamcik.tracker.network.NetworkPolicyContributor]
     * (`@IntoSet`) — see `com.adsamcik.tracker.network.NetworkGateway` KDoc
     * for the privacy contract.
     *
     * Direct calls to [NetworkGateway.setEnabled] / [NetworkGateway.setPolicy]
     * from feature code are an anti-pattern; they will be silently overwritten
     * on the aggregator's next emission. Register a contributor instead.
     *
     * The injected [DispatchersProvider] reaches into [DefaultNetworkGateway.request]
     * so tests can substitute a controlled dispatcher; production injection
     * binds to [DefaultDispatchersProvider] above.
     */
    @Provides
    @Singleton
    fun provideNetworkGateway(dispatchers: DispatchersProvider): NetworkGateway =
        DefaultNetworkGateway(dispatchers = dispatchers)
}
