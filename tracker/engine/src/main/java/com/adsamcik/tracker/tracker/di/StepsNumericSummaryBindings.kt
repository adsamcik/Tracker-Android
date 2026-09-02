package com.adsamcik.tracker.tracker.di

import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRepository
import com.adsamcik.tracker.tracker.source.summary.RoomStepsNumericSummaryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt boundary for completeness-sensitive Steps numeric reads. */
@Module
@InstallIn(SingletonComponent::class)
abstract class StepsNumericSummaryBindings {
	/** Binds the read-only Room implementation without exposing it across module boundaries. */
	@Binds
	abstract fun bindStepsNumericSummaryRepository(
		impl: RoomStepsNumericSummaryRepository,
	): StepsNumericSummaryRepository
}
