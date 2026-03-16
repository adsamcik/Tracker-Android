package com.adsamcik.tracker.app.di

import com.adsamcik.tracker.activity.ActivityModuleInitializer
import com.adsamcik.tracker.game.GameModuleInitializer
import com.adsamcik.tracker.points.PointsInitializer
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import com.adsamcik.tracker.tracker.module.TrackerModuleInitializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
object StartupModule {

	@Provides
	@IntoSet
	fun provideActivityModuleInitializer(
		initializer: ActivityModuleInitializer,
	): ModuleInitializer = initializer

	@Provides
	@IntoSet
	fun provideTrackerModuleInitializer(
		initializer: TrackerModuleInitializer,
	): ModuleInitializer = initializer

	@Provides
	@IntoSet
	fun provideGameModuleInitializer(
		initializer: GameModuleInitializer,
	): ModuleInitializer = initializer

	@Provides
	@IntoSet
	fun providePointsInitializer(
		initializer: PointsInitializer,
	): ModuleInitializer = initializer
}
