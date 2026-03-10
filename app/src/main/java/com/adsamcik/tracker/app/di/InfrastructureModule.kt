package com.adsamcik.tracker.app.di

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.DomainEventDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.FrequentPlaceDao
import com.adsamcik.tracker.shared.base.database.dao.InferredTripDao
import com.adsamcik.tracker.shared.base.database.dao.LiveStatsDao
import com.adsamcik.tracker.shared.base.database.dao.TripLegDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.PersonalRecordDao
import com.adsamcik.tracker.shared.base.database.dao.RouteCacheDao
import com.adsamcik.tracker.shared.base.database.dao.SkiRunSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.StorageSizeSnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerRunDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
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
    fun provideAppScope(): CoroutineScope = 
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Provides the Room database instance.
     * Singleton to ensure single database connection pool.
     */
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.database(context)

    // DAO Providers - enable direct DAO injection without going through AppDatabase

    /**
     * Provides SessionSegmentDao for session segment persistence.
     */
    @Provides
    @Singleton
    fun provideSessionSegmentDao(database: AppDatabase): SessionSegmentDao = database.sessionSegmentDao()

    /**
     * Provides LocationSampleDao for raw location sample access.
     */
    @Provides
    @Singleton
    fun provideLocationSampleDao(database: AppDatabase): LocationSampleDao = database.locationSampleDao()

    /**
     * Provides TrackerRunDao for tracker run state persistence.
     */
    @Provides
    @Singleton
    fun provideTrackerRunDao(database: AppDatabase): TrackerRunDao = database.trackerRunDao()

    /**
     * Provides TripDao for read-only Trip projections over session segments.
     */
    @Provides
    @Singleton
    fun provideTripDao(database: AppDatabase): TripDao = database.tripDao()

    /**
     * Provides DailySummaryDao for daily aggregate statistics.
     */
    @Provides
    @Singleton
    fun provideDailySummaryDao(database: AppDatabase): DailySummaryDao = database.dailySummaryDao()

    /**
     * Provides SkiRunSegmentDao for ski session detail views.
     */
    @Provides
    @Singleton
    fun provideSkiRunSegmentDao(database: AppDatabase): SkiRunSegmentDao = database.skiRunSegmentDao()

    /**
     * Provides ExplorationCellDao for exploration cell data persistence.
     */
    @Provides
    @Singleton
    fun provideExplorationCellDao(database: AppDatabase): ExplorationCellDao = database.explorationCellDao()

    /**
     * Provides ExplorationStreakDao for exploration streak data persistence.
     */
    @Provides
    @Singleton
    fun provideExplorationStreakDao(database: AppDatabase): ExplorationStreakDao = database.explorationStreakDao()

    /**
     * Provides AchievementProgressDao for achievement progress data persistence.
     */
    @Provides
    @Singleton
    fun provideAchievementProgressDao(database: AppDatabase): AchievementProgressDao = database.achievementProgressDao()

    /**
     * Provides PersonalRecordDao for personal record data persistence.
     */
    @Provides
    @Singleton
    fun providePersonalRecordDao(database: AppDatabase): PersonalRecordDao = database.personalRecordDao()

    /**
     * Provides RouteCacheDao for compressed route polyline persistence.
     */
    @Provides
    @Singleton
    fun provideRouteCacheDao(database: AppDatabase): RouteCacheDao = database.routeCacheDao()

    /**
     * Provides ExportLogDao for export history persistence.
     */
    @Provides
    @Singleton
    fun provideExportLogDao(database: AppDatabase): ExportLogDao = database.exportLogDao()

    /**
     * Provides StorageSizeSnapshotDao for daily storage metrics persistence.
     */
    @Provides
    @Singleton
    fun provideStorageSizeSnapshotDao(database: AppDatabase): StorageSizeSnapshotDao = database.storageSizeSnapshotDao()

    /**
     * Provides LiveStatsDao for real-time dashboard stats.
     */
    @Provides
    @Singleton
    fun provideLiveStatsDao(database: AppDatabase): LiveStatsDao = database.liveStatsDao()

    /**
     * Provides FrequentPlaceDao for trip enrichment (Phase 3b).
     */
    @Provides
    @Singleton
    fun provideFrequentPlaceDao(database: AppDatabase): FrequentPlaceDao = database.frequentPlaceDao()

    /**
     * Provides InferredTripDao for enriched trip data (Phase 3b).
     */
    @Provides
    @Singleton
    fun provideInferredTripDao(database: AppDatabase): InferredTripDao = database.inferredTripDao()

    /**
     * Provides TripLegDao for trip segment data (Phase 3b).
     */
    @Provides
    @Singleton
    fun provideTripLegDao(database: AppDatabase): TripLegDao = database.tripLegDao()

    /**
     * Provides DomainEventDao for stats pipeline event persistence.
     */
    @Provides
    @Singleton
    fun provideDomainEventDao(database: AppDatabase): DomainEventDao = database.domainEventDao()

    /**
     * Provides CellSampleDao for cell tower sample persistence.
     */
    @Provides
    @Singleton
    fun provideCellSampleDao(database: AppDatabase): CellSampleDao = database.cellSampleDao()

    /**
     * Provides WifiObservationDao for Wi-Fi observation persistence.
     */
    @Provides
    @Singleton
    fun provideWifiObservationDao(database: AppDatabase): WifiObservationDao = database.wifiObservationDao()

    /**
     * Provides PressureSampleDao for barometric pressure data.
     */
    @Provides
    @Singleton
    fun providePressureSampleDao(database: AppDatabase): PressureSampleDao = database.pressureSampleDao()

    /**
     * Provides StepIntervalDao for step interval data.
     */
    @Provides
    @Singleton
    fun provideStepIntervalDao(database: AppDatabase): StepIntervalDao = database.stepIntervalDao()

    /**
     * Provides ActivitySnapshotDao for activity snapshot data.
     */
    @Provides
    @Singleton
    fun provideActivitySnapshotDao(database: AppDatabase): ActivitySnapshotDao = database.activitySnapshotDao()
}
