package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsV2
import com.adsamcik.tracker.stats.data.repository.RoomExportPortableAmbientSteps
import com.adsamcik.tracker.stats.data.repository.RoomExportPortableAmbientStepsV2
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Source binding required by the user-selected native Ambient Steps file exporter. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class PortableAmbientStepsFileDataModule {
	@Binds
	@Singleton
	abstract fun bindExportPortableAmbientSteps(
		implementation: RoomExportPortableAmbientSteps,
	): ExportPortableAmbientSteps

	@Binds
	@Singleton
	abstract fun bindExportPortableAmbientStepsV2(
		implementation: RoomExportPortableAmbientStepsV2,
	): ExportPortableAmbientStepsV2
}
