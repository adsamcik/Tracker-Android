package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.stats.api.repository.RadioLocationContextRepository
import com.adsamcik.tracker.stats.data.repository.RoomRadioLocationContextRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RadioLocationContextDataModule {
    @Binds
    @Singleton
    abstract fun repository(implementation: RoomRadioLocationContextRepository): RadioLocationContextRepository
}
