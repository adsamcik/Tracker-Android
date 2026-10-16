package com.adsamcik.tracker.app.di

import com.adsamcik.tracker.app.settings.DefaultDataBackupRestore
import com.adsamcik.tracker.impexp.backup.DataBackupRestore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds logical data restore to app-owned deletion, startup and recovery coordination. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataBackupModule {
    /** Shares one restore service for data-only imports without replacing database connections. */
    @Binds @Singleton abstract fun dataBackupRestore(implementation: DefaultDataBackupRestore): DataBackupRestore
}
