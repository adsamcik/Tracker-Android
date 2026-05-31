package com.adsamcik.tracker.stats.data.di

import android.content.Context
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.PersistentDirtyState
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
import com.adsamcik.tracker.stats.data.metric.DefaultMetricDirtyTracker
import com.adsamcik.tracker.stats.data.metric.DefaultPersistentDirtyState
import com.adsamcik.tracker.stats.data.metric.DurableMetricDirtyTracker
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
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
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

	// NOTE: `bindMetricDirtyTracker` removed — the MetricDirtyTracker is now
	// constructed by [DurableMetricDirtyTrackerModule.provideMetricDirtyTracker]
	// which wraps DefaultMetricDirtyTracker with persistence-layer durability.
	// See R2 round-7 finding `r2r7-worker-dirty-process-death`.

	@Binds
	@Singleton
	abstract fun bindAchievementEvaluationScheduler(
		impl: WorkManagerAchievementEvaluationScheduler,
	): AchievementEvaluationScheduler

	@Binds
	@Singleton
	abstract fun bindSpeedLimitSource(impl: DefaultSpeedLimitSource): SpeedLimitSource

	/**
	 * Companion holds [Provides] functions that need explicit construction
	 * — primarily the durable [MetricDirtyTracker] wrapper which can't use
	 * constructor-injection because it composes a [DefaultMetricDirtyTracker]
	 * with a [PersistentDirtyState] and the app-scoped [CoroutineScope].
	 */
	companion object {

		/**
		 * Provides the file-backed [PersistentDirtyState] used by
		 * [DurableMetricDirtyTracker]. Stored in `context.filesDir/
		 * metric_dirty_persistence.txt`, a single tiny file that survives
		 * process death so AchievementWorker can recover dirty bits the OS
		 * killed mid-tracking.
		 */
		@Provides
		@Singleton
		fun providePersistentDirtyState(
			@ApplicationContext context: Context,
		): PersistentDirtyState = DefaultPersistentDirtyState(context.filesDir)
	}
}

/**
 * Separate module so the constructor-injectable [DefaultMetricDirtyTracker]
 * binding in [StatsDataModule.bindMetricDirtyTracker] is REMOVED and
 * replaced with the durable wrapper. Kept in its own module to make the
 * substitution easy to reason about in code review.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DurableMetricDirtyTrackerModule {

	/**
	 * The app-wide [MetricDirtyTracker] singleton. Wraps the in-memory
	 * [DefaultMetricDirtyTracker] with [DurableMetricDirtyTracker] so the
	 * PERSISTENCE consumer's marks survive process death.
	 *
	 * The wrapper rehydrates the in-memory state from disk in its `init`
	 * block, so by the time Hilt finishes constructing the singleton the
	 * tracker already reflects everything the previous process left behind.
	 */
	@Provides
	@Singleton
	fun provideMetricDirtyTracker(
		persistentState: PersistentDirtyState,
		@ApplicationScope appScope: CoroutineScope,
	): MetricDirtyTracker = DurableMetricDirtyTracker(
		delegate = DefaultMetricDirtyTracker(),
		persistentState = persistentState,
		persistenceScope = appScope,
	)
}
