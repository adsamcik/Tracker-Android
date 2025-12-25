package com.adsamcik.tracker.app.di

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
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
     * Provides application-scoped CoroutineScope.
     * Use for long-running operations that outlive individual components.
     * Uses SupervisorJob to prevent failure propagation between children.
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
}
