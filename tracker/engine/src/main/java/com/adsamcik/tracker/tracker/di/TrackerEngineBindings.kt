package com.adsamcik.tracker.tracker.di

import com.adsamcik.tracker.tracker.insights.DefaultSessionInsightsGenerator
import com.adsamcik.tracker.tracker.insights.SessionInsightsGenerator
import com.adsamcik.tracker.tracker.service.ActivityWatcherController
import com.adsamcik.tracker.tracker.service.ActivityWatcherServiceController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class TrackerEngineBindings {
	@Binds
	abstract fun bindActivityWatcherController(
		impl: ActivityWatcherServiceController,
	): ActivityWatcherController

	@Binds
	abstract fun bindSessionInsightsGenerator(
		impl: DefaultSessionInsightsGenerator,
	): SessionInsightsGenerator
}
