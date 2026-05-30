package com.adsamcik.tracker.app.di

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.CellOperatorDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.DomainEventDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.FrequentPlaceDao
import com.adsamcik.tracker.shared.base.database.dao.GeneralDao
import com.adsamcik.tracker.shared.base.database.dao.InferredTripDao
import com.adsamcik.tracker.shared.base.database.dao.LiveStatsDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.database.dao.OsmImportDao
import com.adsamcik.tracker.shared.base.database.dao.OsmWayCellDao
import com.adsamcik.tracker.shared.base.database.dao.OsmWayDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.dao.PersonalRecordDao
import com.adsamcik.tracker.shared.base.database.dao.PlayerProfileDao
import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.RouteCacheDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.SkiRunSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.StorageSizeSnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerRunDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.dao.TripLegDao
import com.adsamcik.tracker.shared.base.database.dao.UnifiedGeoDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.dao.XpLedgerDao
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.di.DefaultDispatcher
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.base.time.SystemClock
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

    // DAO Providers - enable direct DAO injection without going through AppDatabase.
    // DAOs are lightweight proxies to the singleton database and do not need @Singleton scoping.

    @Provides
    fun provideCellOperatorDao(database: AppDatabase): CellOperatorDao = database.cellOperatorDao()

    @Provides
    fun provideActivityDao(database: AppDatabase): ActivityDao = database.activityDao()

    @Provides
    fun provideGeneralDao(database: AppDatabase): GeneralDao = database.generalDao()

    @Provides
    fun provideUnifiedGeoDao(database: AppDatabase): UnifiedGeoDao = database.unifiedGeoDao()

    @Provides
    fun provideLocationSampleDao(database: AppDatabase): LocationSampleDao = database.locationSampleDao()

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
    fun provideSessionSegmentDao(database: AppDatabase): SessionSegmentDao = database.sessionSegmentDao()

    @Provides
    fun provideTripDao(database: AppDatabase): TripDao = database.tripDao()

    @Provides
    fun provideDailySummaryDao(database: AppDatabase): DailySummaryDao = database.dailySummaryDao()

    @Provides
    fun provideLiveStatsDao(database: AppDatabase): LiveStatsDao = database.liveStatsDao()

    @Provides
    fun provideFrequentPlaceDao(database: AppDatabase): FrequentPlaceDao = database.frequentPlaceDao()

    @Provides
    fun provideInferredTripDao(database: AppDatabase): InferredTripDao = database.inferredTripDao()

    @Provides
    fun provideTripLegDao(database: AppDatabase): TripLegDao = database.tripLegDao()

    @Provides
    fun provideExplorationCellDao(database: AppDatabase): ExplorationCellDao = database.explorationCellDao()

    @Provides
    fun provideExplorationStreakDao(database: AppDatabase): ExplorationStreakDao = database.explorationStreakDao()

    @Provides
    fun provideAchievementProgressDao(database: AppDatabase): AchievementProgressDao = database.achievementProgressDao()

    @Provides
    fun providePersonalRecordDao(database: AppDatabase): PersonalRecordDao = database.personalRecordDao()

    @Provides
    fun provideRouteCacheDao(database: AppDatabase): RouteCacheDao = database.routeCacheDao()

    @Provides
    fun provideExportLogDao(database: AppDatabase): ExportLogDao = database.exportLogDao()

    @Provides
    fun provideStorageSizeSnapshotDao(database: AppDatabase): StorageSizeSnapshotDao = database.storageSizeSnapshotDao()

    @Provides
    fun provideDomainEventDao(database: AppDatabase): DomainEventDao = database.domainEventDao()

    @Provides
    fun providePressureSampleDao(database: AppDatabase): PressureSampleDao = database.pressureSampleDao()

    @Provides
    fun provideSkiRunSegmentDao(database: AppDatabase): SkiRunSegmentDao = database.skiRunSegmentDao()

    @Provides
    fun providePendingSignalDao(database: AppDatabase): PendingSignalDao = database.pendingSignalDao()

    @Provides
    fun provideXpLedgerDao(database: AppDatabase): XpLedgerDao = database.xpLedgerDao()

    @Provides
    fun providePlayerProfileDao(database: AppDatabase): PlayerProfileDao = database.playerProfileDao()

    @Provides
    fun provideMiniGameScoreDao(database: AppDatabase): MiniGameScoreDao = database.miniGameScoreDao()

    // OSM road graph DAOs (Phase 2 vehicle speed compliance)

    @Provides
    fun provideOsmImportDao(database: AppDatabase): OsmImportDao = database.osmImportDao()

    @Provides
    fun provideOsmWayDao(database: AppDatabase): OsmWayDao = database.osmWayDao()

    @Provides
    fun provideOsmWayCellDao(database: AppDatabase): OsmWayCellDao = database.osmWayCellDao()
}
