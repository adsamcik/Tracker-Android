package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.rule.AchievementRules
import com.adsamcik.tracker.stats.api.rule.RuleRegistry
import com.adsamcik.tracker.stats.engine.processor.AchievementProcessor
import com.adsamcik.tracker.stats.engine.processor.AggregatorProcessor
import com.adsamcik.tracker.stats.engine.processor.ExplorationProcessor
import com.adsamcik.tracker.stats.engine.processor.SegmentDetectorProcessor
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import com.adsamcik.tracker.tracker.pipeline.persistence.RoomPersistenceTransactor
import com.adsamcik.tracker.tracker.pipeline.persistence.TrackingPersistenceTransactor
import com.adsamcik.tracker.shared.base.database.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Hilt module that provides SignalProcessor instances via multibinding.
 * Each processor is registered with @IntoSet for automatic discovery
 * by ProcessorPipeline.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProcessorPipelineModule {

	@Provides
	@Singleton
	fun provideTrackingPersistenceTransactor(
		database: AppDatabase,
	): TrackingPersistenceTransactor {
		return RoomPersistenceTransactor(database)
	}

	@Provides
	@Singleton
	fun provideAggregatorProcessorInstance(
		dirtyTracker: com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker,
	): AggregatorProcessor {
		// Inject the dirty tracker so the aggregator marks `aggregator_state` dirty when
		// its in-memory accumulators move. Without this, the downstream
		// AchievementProcessor's short-circuit would suppress real progress because
		// snapshot metrics change without any backing table write (R2 review fix).
		return AggregatorProcessor(dirtyTracker = dirtyTracker)
	}

	@Provides
	@IntoSet
	fun provideAggregatorProcessor(instance: AggregatorProcessor): SignalProcessor {
		return instance
	}

	@Provides
	@IntoSet
	fun provideSegmentDetectorProcessor(aggregator: AggregatorProcessor): SignalProcessor {
		return SegmentDetectorProcessor(
			onTripCompleted = { aggregator.notifyTripCompleted() },
		)
	}

	@Provides
	@IntoSet
	fun provideExplorationProcessor(): SignalProcessor {
		return ExplorationProcessor()
	}

	@Provides
	@IntoSet
	fun provideAchievementProcessor(
		aggregator: AggregatorProcessor,
		dirtyTracker: com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker,
		@AchievementRules achievementRegistry: RuleRegistry,
	): SignalProcessor {
		return AchievementProcessor(
			registry = achievementRegistry,
			metricsProvider = { aggregator.snapshotMetrics() },
			dirtyTracker = dirtyTracker,
		)
	}

	@Provides
	@IntoSet
	fun providePersistenceProcessor(instance: PersistenceProcessor): SignalProcessor {
		return instance
	}
}
