package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.stats.api.repository.ExportPortablePressure
import com.adsamcik.tracker.stats.data.repository.RoomExportPortablePressure
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Product binding for the source-local, read-only portable Pressure exporter. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class PortablePressureExportDataModule {
	@Binds
	@Singleton
	abstract fun bindExportPortablePressure(
		impl: RoomExportPortablePressure,
	): ExportPortablePressure
}
