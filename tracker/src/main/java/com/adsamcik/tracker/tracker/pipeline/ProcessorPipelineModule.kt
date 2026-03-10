package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.engine.processor.AchievementProcessor
import com.adsamcik.tracker.stats.engine.processor.AggregatorProcessor
import com.adsamcik.tracker.stats.engine.processor.ExplorationProcessor
import com.adsamcik.tracker.stats.engine.processor.SegmentDetectorProcessor
import com.adsamcik.tracker.tracker.data.DefaultPersistenceErrorCollector
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import com.adsamcik.tracker.tracker.pipeline.NotificationProcessor
import com.adsamcik.tracker.tracker.pipeline.SkiTrackingProcessor
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
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
	fun providePersistenceErrorCollector(): PersistenceErrorCollector {
		return DefaultPersistenceErrorCollector()
	}

	@Provides
	@Singleton
	fun provideAggregatorProcessorInstance(): AggregatorProcessor {
		return AggregatorProcessor()
	}

	@Provides
	@IntoSet
	fun provideAggregatorProcessor(instance: AggregatorProcessor): SignalProcessor {
		return instance
	}

	@Provides
	@IntoSet
	fun provideSegmentDetectorProcessor(): SignalProcessor {
		return SegmentDetectorProcessor()
	}

	@Provides
	@IntoSet
	fun provideExplorationProcessor(): SignalProcessor {
		return ExplorationProcessor()
	}

	@Provides
	@IntoSet
	fun provideAchievementProcessor(aggregator: AggregatorProcessor): SignalProcessor {
		return AchievementProcessor(
			metricsProvider = { aggregator.snapshotMetrics() },
		)
	}

	@Provides
	@IntoSet
	fun providePersistenceProcessor(instance: PersistenceProcessor): SignalProcessor {
		return instance
	}

	@Provides
	@IntoSet
	fun provideNotificationProcessor(processor: NotificationProcessor): SignalProcessor {
		return processor
	}

	@Provides
	@IntoSet
	fun provideSkiTrackingProcessor(processor: SkiTrackingProcessor): SignalProcessor {
		return processor
	}
}
