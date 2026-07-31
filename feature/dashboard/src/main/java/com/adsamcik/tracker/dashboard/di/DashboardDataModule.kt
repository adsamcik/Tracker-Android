package com.adsamcik.tracker.dashboard.di

import com.adsamcik.tracker.dashboard.data.DashboardHistoryRepository
import com.adsamcik.tracker.dashboard.data.DashboardLayoutRepository
import com.adsamcik.tracker.dashboard.data.DashboardLayoutStore
import com.adsamcik.tracker.dashboard.data.RoomDashboardHistoryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DashboardDataModule {
	@Binds
	@Singleton
	abstract fun bindDashboardHistoryRepository(
		implementation: RoomDashboardHistoryRepository,
	): DashboardHistoryRepository

	@Binds
	@Singleton
	abstract fun bindDashboardLayoutStore(
		implementation: DashboardLayoutRepository,
	): DashboardLayoutStore
}
