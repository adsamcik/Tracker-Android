package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.stats.api.repository.DeleteImportedPressureEntry
import com.adsamcik.tracker.stats.api.repository.ErasePressureSource
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressure
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressure
import com.adsamcik.tracker.stats.api.repository.TruncateImportedPressureRetention
import com.adsamcik.tracker.stats.data.repository.RoomDeleteImportedPressureEntry
import com.adsamcik.tracker.stats.data.repository.RoomErasePressureSource
import com.adsamcik.tracker.stats.data.repository.RoomExportPortablePressure
import com.adsamcik.tracker.stats.data.repository.RoomImportPortablePressure
import com.adsamcik.tracker.stats.data.repository.RoomTruncateImportedPressureRetention
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Product bindings for source-local portable Pressure transfer. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class PortablePressureExportDataModule {
	@Binds
	@Singleton
	abstract fun bindExportPortablePressure(
		impl: RoomExportPortablePressure,
	): ExportPortablePressure

	@Binds
	@Singleton
	abstract fun bindImportPortablePressure(
		impl: RoomImportPortablePressure,
	): ImportPortablePressure

	@Binds
	@Singleton
	abstract fun bindDeleteImportedPressureEntry(
		impl: RoomDeleteImportedPressureEntry,
	): DeleteImportedPressureEntry

	@Binds
	@Singleton
	abstract fun bindTruncateImportedPressureRetention(
		impl: RoomTruncateImportedPressureRetention,
	): TruncateImportedPressureRetention

	@Binds
	@Singleton
	abstract fun bindErasePressureSource(
		impl: RoomErasePressureSource,
	): ErasePressureSource
}
