package com.adsamcik.tracker.app.di

import android.content.Context
import com.adsamcik.tracker.game.repository.DefaultGameRepository
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.game.goals.settings.DefaultGoalsSettingsRepository
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.preferences.map.DefaultMapSettingsRepository
import com.adsamcik.tracker.shared.preferences.map.MapSettingsRepository
import com.adsamcik.tracker.shared.preferences.settings.DefaultTrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.statistics.repository.DefaultSessionRepository
import com.adsamcik.tracker.statistics.repository.SessionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing repository bindings.
 * Binds interface types to their default implementations.
 * 
 * Per copilot-instructions Section 16A:
 * - Separate interface from implementation across module boundaries
 * - Repositories are application-scoped (long-lived)
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /**
     * Binds SessionRepository interface to its default implementation.
     * Used by StatsViewModel for accessing session data.
     */
    @Binds
    @Singleton
    abstract fun bindSessionRepository(
        impl: DefaultSessionRepository
    ): SessionRepository

    /**
     * Binds GameRepository interface to its default implementation.
     * Used by GameViewModel for accessing game data.
     */
    @Binds
    @Singleton
    abstract fun bindGameRepository(
        impl: DefaultGameRepository
    ): GameRepository

    companion object {
        /**
         * Provides TrackerSettingsRepository.
         * Uses @Provides instead of @Binds because DefaultTrackerSettingsRepository
         * has optional constructor parameters that need explicit handling.
         */
        @Provides
        @Singleton
        fun provideTrackerSettingsRepository(
            @ApplicationContext context: Context,
            dispatchers: DispatchersProvider
        ): TrackerSettingsRepository = DefaultTrackerSettingsRepository(
            context = context,
            io = dispatchers.io
        )

        @Provides
        @Singleton
        fun provideMapSettingsRepository(
            @ApplicationContext context: Context,
            dispatchers: DispatchersProvider
        ): MapSettingsRepository = DefaultMapSettingsRepository(
            context = context,
            io = dispatchers.io
        )

        @Provides
        @Singleton
        fun provideGoalsSettingsRepository(
            @ApplicationContext context: Context,
            dispatchers: DispatchersProvider
        ): GoalsSettingsRepository = DefaultGoalsSettingsRepository(
            context = context,
            io = dispatchers.io
        )
    }
}
