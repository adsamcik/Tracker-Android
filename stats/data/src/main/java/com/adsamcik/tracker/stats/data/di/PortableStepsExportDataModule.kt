package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.stats.api.repository.ExportPortableSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsV2
import com.adsamcik.tracker.stats.data.repository.RoomExportPortableSteps
import com.adsamcik.tracker.stats.data.repository.RoomExportPortableStepsV2
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Product binding for the source-local portable Steps reader. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class PortableStepsExportDataModule {
	@Binds
	@Singleton
	abstract fun bindExportPortableSteps(impl: RoomExportPortableSteps): ExportPortableSteps

	@Binds
	@Singleton
	abstract fun bindExportPortableStepsV2(impl: RoomExportPortableStepsV2): ExportPortableStepsV2
}
