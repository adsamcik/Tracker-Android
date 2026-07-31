package com.adsamcik.tracker.impexp.exporter.di

import com.adsamcik.tracker.impexp.exporter.data.ImportExportDataRepository
import com.adsamcik.tracker.impexp.exporter.data.RoomImportExportDataRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ImportExportDataModule {
    @Binds
    @Singleton
    abstract fun bindImportExportDataRepository(
        implementation: RoomImportExportDataRepository,
    ): ImportExportDataRepository
}
