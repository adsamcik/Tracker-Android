package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.stats.api.repository.AchievementRepository
import com.adsamcik.tracker.stats.api.repository.DailySummaryRepository
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.ExplorationRepository
import com.adsamcik.tracker.stats.api.repository.LiveStatsRepository
import com.adsamcik.tracker.stats.api.repository.TripRepository
import com.adsamcik.tracker.stats.data.repository.DefaultAchievementRepository
import com.adsamcik.tracker.stats.data.repository.DefaultDailySummaryRepository
import com.adsamcik.tracker.stats.data.repository.DefaultDomainEventRepository
import com.adsamcik.tracker.stats.data.repository.DefaultExplorationRepository
import com.adsamcik.tracker.stats.data.repository.DefaultLiveStatsRepository
import com.adsamcik.tracker.stats.data.repository.DefaultTripRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StatsDataModule {

	@Binds
	@Singleton
	abstract fun bindTripRepository(impl: DefaultTripRepository): TripRepository

	@Binds
	@Singleton
	abstract fun bindDailySummaryRepository(impl: DefaultDailySummaryRepository): DailySummaryRepository

	@Binds
	@Singleton
	abstract fun bindExplorationRepository(impl: DefaultExplorationRepository): ExplorationRepository

	@Binds
	@Singleton
	abstract fun bindLiveStatsRepository(impl: DefaultLiveStatsRepository): LiveStatsRepository

	@Binds
	@Singleton
	abstract fun bindAchievementRepository(impl: DefaultAchievementRepository): AchievementRepository

	@Binds
	@Singleton
	abstract fun bindDomainEventRepository(impl: DefaultDomainEventRepository): DomainEventRepository
}
