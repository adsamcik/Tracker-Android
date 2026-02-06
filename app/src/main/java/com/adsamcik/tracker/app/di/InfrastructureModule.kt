package com.adsamcik.tracker.app.di

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.CellLocationDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.dao.SessionDataDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerRunDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.dao.WifiDataDao
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
     * Provides SessionDataDao for tracking session persistence.
     */
    @Provides
    @Singleton
    fun provideSessionDao(database: AppDatabase): SessionDataDao = database.sessionDao()

    /**
     * Provides LocationDataDao for location data persistence.
     */
    @Provides
    @Singleton
    fun provideLocationDao(database: AppDatabase): LocationDataDao = database.locationDao()

    /**
     * Provides WifiDataDao for Wi-Fi data persistence.
     */
    @Provides
    @Singleton
    fun provideWifiDao(database: AppDatabase): WifiDataDao = database.wifiDao()

    /**
     * Provides CellLocationDao for cell location data persistence.
     */
    @Provides
    @Singleton
    fun provideCellLocationDao(database: AppDatabase): CellLocationDao = database.cellLocationDao()

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
}
