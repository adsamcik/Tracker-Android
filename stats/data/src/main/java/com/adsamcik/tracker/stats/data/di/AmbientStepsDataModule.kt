package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRepository
import com.adsamcik.tracker.stats.api.repository.AmbientStepsNumericRangeReader
import com.adsamcik.tracker.stats.api.repository.DeleteImportedAmbientStepsAfterConsentReset
import com.adsamcik.tracker.stats.api.repository.DeleteImportedAmbientStepsDay
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsV2
import com.adsamcik.tracker.stats.api.repository.ReexportImportedAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ReexportImportedAmbientStepsV2
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityQuery
import com.adsamcik.tracker.stats.api.repository.TruncateImportedAmbientStepsRetention
import com.adsamcik.tracker.stats.data.repository.DefaultAmbientStepsHistoryRepository
import com.adsamcik.tracker.stats.data.repository.RoomDeleteImportedAmbientStepsAfterConsentReset
import com.adsamcik.tracker.stats.data.repository.RoomDeleteImportedAmbientStepsDay
import com.adsamcik.tracker.stats.data.repository.RoomImportPortableAmbientSteps
import com.adsamcik.tracker.stats.data.repository.RoomReexportImportedAmbientSteps
import com.adsamcik.tracker.stats.data.repository.RoomReexportImportedAmbientStepsV2
import com.adsamcik.tracker.stats.data.repository.RoomStepsCountDomainCompatibilityQuery
import com.adsamcik.tracker.stats.data.repository.RoomTruncateImportedAmbientStepsRetention
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Source-specific Ambient Steps product and portable-origin bindings. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AmbientStepsDataModule {
	@Binds
	@Singleton
	abstract fun bindAmbientStepsHistoryRepository(
		impl: DefaultAmbientStepsHistoryRepository,
	): AmbientStepsHistoryRepository

	@Binds
	@Singleton
	abstract fun bindAmbientStepsNumericRangeReader(
		impl: DefaultAmbientStepsHistoryRepository,
	): AmbientStepsNumericRangeReader

	@Binds
	@Singleton
	abstract fun bindStepsCountDomainCompatibilityQuery(
		impl: RoomStepsCountDomainCompatibilityQuery,
	): StepsCountDomainCompatibilityQuery

	@Binds
	@Singleton
	abstract fun bindImportPortableAmbientSteps(
		impl: RoomImportPortableAmbientSteps,
	): ImportPortableAmbientSteps

	@Binds
	@Singleton
	abstract fun bindImportPortableAmbientStepsV2(
		impl: RoomImportPortableAmbientSteps,
	): ImportPortableAmbientStepsV2

	@Binds
	@Singleton
	abstract fun bindReexportImportedAmbientSteps(
		impl: RoomReexportImportedAmbientSteps,
	): ReexportImportedAmbientSteps

	@Binds
	@Singleton
	abstract fun bindReexportImportedAmbientStepsV2(
		impl: RoomReexportImportedAmbientStepsV2,
	): ReexportImportedAmbientStepsV2

	@Binds
	@Singleton
	abstract fun bindDeleteImportedAmbientStepsDay(
		impl: RoomDeleteImportedAmbientStepsDay,
	): DeleteImportedAmbientStepsDay

	@Binds
	@Singleton
	abstract fun bindTruncateImportedAmbientStepsRetention(
		impl: RoomTruncateImportedAmbientStepsRetention,
	): TruncateImportedAmbientStepsRetention

	@Binds
	@Singleton
	abstract fun bindDeleteImportedAmbientStepsAfterConsentReset(
		impl: RoomDeleteImportedAmbientStepsAfterConsentReset,
	): DeleteImportedAmbientStepsAfterConsentReset

	companion object {
		@Provides
		@Singleton
		fun provideImportedAmbientStepsDao(database: AppDatabase): ImportedAmbientStepsDao =
			database.importedAmbientStepsDao()
	}
}
