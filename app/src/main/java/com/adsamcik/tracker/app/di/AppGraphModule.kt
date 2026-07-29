package com.adsamcik.tracker.app.di

import android.content.Context
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.game.di.DefaultDailyPointsProvider
import com.adsamcik.tracker.game.di.DefaultGoalProgressProvider
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.impexp.exporter.automation.ExportAutomationController
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.LiveStatsDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.di.DailyPointsProvider
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.stats.api.TrackerLiveLocationFeed
import com.adsamcik.tracker.tracker.controller.DefaultLockManager
import com.adsamcik.tracker.tracker.controller.DefaultTrackerServiceController
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import com.adsamcik.tracker.tracker.di.DefaultDailySummaryProvider
import com.adsamcik.tracker.tracker.feed.DefaultTrackerLiveLocationFeed
import com.adsamcik.tracker.tracker.service.ActivityWatcherServiceController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppGraphModule {

    @Provides
    @Singleton
    fun provideApplication(@ApplicationContext context: Context): Application {
        return context.applicationContext as Application
    }

    @Provides
    @Singleton
    fun providePreferences(@ApplicationContext context: Context): Preferences = Preferences(context)

    @Provides
    @Singleton
    fun provideTrackerServiceController(): TrackerServiceController {
        return DefaultTrackerServiceController()
    }

    @Provides
    @Singleton
    fun provideTrackerStateReader(
        controller: TrackerServiceController,
    ): TrackerStateReader = controller

    @Provides
    @Singleton
    fun provideTrackerLiveLocationFeed(
        controller: TrackerStateReader,
    ): TrackerLiveLocationFeed = DefaultTrackerLiveLocationFeed(controller)

    @Provides
    @Singleton
    fun provideLockManager(
        controller: TrackerStateReader,
        watcherController: ActivityWatcherServiceController,
    ): LockManager {
        return DefaultLockManager(controller, watcherController)
    }

    @Provides
    @Singleton
    fun provideDailySummaryProvider(
        tripDao: TripDao,
        dailySummaryDao: DailySummaryDao,
        liveStatsDao: LiveStatsDao,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): DailySummaryProvider = DefaultDailySummaryProvider(tripDao, dailySummaryDao, liveStatsDao, ioDispatcher)

    @Provides
    @Singleton
    fun provideDailyPointsProvider(
        gameRepository: GameRepository,
        @ApplicationScope appScope: CoroutineScope,
    ): DailyPointsProvider = DefaultDailyPointsProvider(gameRepository, appScope)

    @Provides
    @Singleton
    fun provideGoalProgressProvider(
        @ApplicationContext context: Context,
        gameRepository: GameRepository,
        @ApplicationScope appScope: CoroutineScope,
    ): GoalProgressProvider = DefaultGoalProgressProvider(context, gameRepository, appScope)

    @Provides
    @Singleton
    fun provideExportPlanStore(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        clock: Clock,
    ): ExportPlanStore = ExportPlanStore(context, ioDispatcher, clock)

    @Provides
    @Singleton
    fun provideExportAutomationController(
        @ApplicationContext context: Context,
        exportPlanStore: ExportPlanStore,
        dispatchersProvider: DispatchersProvider,
        clock: Clock,
        @ApplicationScope appScope: CoroutineScope,
    ): ExportAutomationController = ExportAutomationController(
        context, exportPlanStore, dispatchersProvider, clock, appScope
    )
}
