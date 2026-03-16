package com.adsamcik.tracker.app.di

import android.content.Context
import androidx.room.Room
import com.adsamcik.tracker.shared.base.database.DebugDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DebugDatabaseModule {

    @Provides
    @Singleton
    fun provideDebugDatabase(@ApplicationContext context: Context): DebugDatabase =
        Room.databaseBuilder(
            context,
            DebugDatabase::class.java,
            "debug_database",
        ).addMigrations(DebugDatabase.MIGRATION_1_2).build()
}
