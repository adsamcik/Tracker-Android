package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.engine.processor.AchievementProcessor
import com.adsamcik.tracker.stats.engine.processor.AggregatorProcessor
import com.adsamcik.tracker.stats.engine.processor.ExplorationProcessor
import com.adsamcik.tracker.stats.engine.processor.SegmentDetectorProcessor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt module that provides SignalProcessor instances via multibinding.
 * Each processor is registered with @IntoSet for automatic discovery
 * by ProcessorPipeline.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProcessorPipelineModule {

	@Provides
	@IntoSet
	fun provideAggregatorProcessor(): SignalProcessor {
		return AggregatorProcessor()
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
	fun provideAchievementProcessor(): SignalProcessor {
		return AchievementProcessor()
	}
}
