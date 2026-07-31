package com.adsamcik.tracker.activity.di

import com.adsamcik.tracker.activity.data.RoomSessionActivityRepository
import com.adsamcik.tracker.activity.data.SessionActivityRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ActivityDataModule {
	@Binds
	@Singleton
	abstract fun bindSessionActivityRepository(
		implementation: RoomSessionActivityRepository,
	): SessionActivityRepository
}
