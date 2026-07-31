package com.adsamcik.tracker.tracker.di

import com.adsamcik.tracker.tracker.data.RecentTripsRepository
import com.adsamcik.tracker.tracker.data.RoomRecentTripsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TrackerFeatureDataModule {
	@Binds
	@Singleton
	abstract fun bindRecentTripsRepository(
		implementation: RoomRecentTripsRepository,
	): RecentTripsRepository
}
