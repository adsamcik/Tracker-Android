package com.adsamcik.tracker.app.di

import com.adsamcik.tracker.app.settings.DefaultDataBackupRestore
import com.adsamcik.tracker.impexp.backup.DataBackupRestore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBackupModule {
    @Binds @Singleton abstract fun dataBackupRestore(implementation: DefaultDataBackupRestore): DataBackupRestore
}
