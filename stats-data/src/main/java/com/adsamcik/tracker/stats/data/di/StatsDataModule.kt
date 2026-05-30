package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.stats.api.repository.AchievementMetricsProvider
import com.adsamcik.tracker.stats.api.repository.AchievementRepository
import com.adsamcik.tracker.stats.api.repository.DailySummaryRepository
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.ExplorationRepository
import com.adsamcik.tracker.stats.api.repository.LocationSampleRepository
import com.adsamcik.tracker.stats.api.repository.LiveStatsRepository
import com.adsamcik.tracker.stats.api.repository.SessionStatsRepository
import com.adsamcik.tracker.stats.api.repository.SkiRunSegmentRepository
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.stats.api.repository.TripRepository
import com.adsamcik.tracker.stats.api.repository.WindowedMetricsProvider
import com.adsamcik.tracker.stats.api.repository.WifiObservationRepository
import com.adsamcik.tracker.stats.api.scheduler.AchievementEvaluationScheduler
import com.adsamcik.tracker.stats.api.speed.SpeedLimitSource
import com.adsamcik.tracker.stats.data.repository.DefaultAchievementMetricsProvider
import com.adsamcik.tracker.stats.data.repository.DefaultAchievementRepository
import com.adsamcik.tracker.stats.data.repository.DefaultDailySummaryRepository
import com.adsamcik.tracker.stats.data.repository.DefaultDomainEventRepository
import com.adsamcik.tracker.stats.data.repository.DefaultExplorationRepository
import com.adsamcik.tracker.stats.data.repository.DefaultLocationSampleRepository
import com.adsamcik.tracker.stats.data.repository.DefaultSessionStatsRepository
import com.adsamcik.tracker.stats.data.repository.DefaultSkiRunSegmentRepository
import com.adsamcik.tracker.stats.data.repository.DefaultTripRepository
import com.adsamcik.tracker.stats.data.repository.DefaultWifiObservationRepository
import com.adsamcik.tracker.stats.data.repository.DefaultWindowedMetricsProvider
import com.adsamcik.tracker.stats.data.repository.ProtoLiveStatsRepository
import com.adsamcik.tracker.stats.data.scheduler.WorkManagerAchievementEvaluationScheduler
import com.adsamcik.tracker.stats.data.speed.DefaultSpeedLimitSource
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
	abstract fun bindTripPresentationRepository(impl: DefaultTripRepository): TripPresentationRepository

	@Binds
	@Singleton
	abstract fun bindDailySummaryRepository(impl: DefaultDailySummaryRepository): DailySummaryRepository

	@Binds
	@Singleton
	abstract fun bindExplorationRepository(impl: DefaultExplorationRepository): ExplorationRepository

	@Binds
	@Singleton
	abstract fun bindLocationSampleRepository(
		impl: DefaultLocationSampleRepository,
	): LocationSampleRepository

	@Binds
	@Singleton
	abstract fun bindSessionStatsRepository(
		impl: DefaultSessionStatsRepository,
	): SessionStatsRepository

	@Binds
	@Singleton
	abstract fun bindWifiObservationRepository(
		impl: DefaultWifiObservationRepository,
	): WifiObservationRepository

	@Binds
	@Singleton
	abstract fun bindSkiRunSegmentRepository(
		impl: DefaultSkiRunSegmentRepository,
	): SkiRunSegmentRepository

	@Binds
	@Singleton
	abstract fun bindLiveStatsRepository(impl: ProtoLiveStatsRepository): LiveStatsRepository

	@Binds
	@Singleton
	abstract fun bindAchievementRepository(impl: DefaultAchievementRepository): AchievementRepository

	@Binds
	@Singleton
	abstract fun bindDomainEventRepository(impl: DefaultDomainEventRepository): DomainEventRepository

	@Binds
	@Singleton
	abstract fun bindAchievementMetricsProvider(
		impl: DefaultAchievementMetricsProvider,
	): AchievementMetricsProvider

	@Binds
	@Singleton
	abstract fun bindWindowedMetricsProvider(
		impl: DefaultWindowedMetricsProvider,
	): WindowedMetricsProvider

	@Binds
	@Singleton
	abstract fun bindMetricDirtyTracker(
		impl: com.adsamcik.tracker.stats.data.metric.DefaultMetricDirtyTracker,
	): com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker

	@Binds
	@Singleton
	abstract fun bindAchievementEvaluationScheduler(
		impl: WorkManagerAchievementEvaluationScheduler,
	): AchievementEvaluationScheduler

	@Binds
	@Singleton
	abstract fun bindSpeedLimitSource(impl: DefaultSpeedLimitSource): SpeedLimitSource
}
