package com.adsamcik.tracker.app.di

import android.app.Application as AndroidApplication
import android.content.Context
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.shared.utils.module.TrackerSessionChannel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@OptIn(ExperimentalStdlibApi::class)
@Module
@InstallIn(SingletonComponent::class)
object AppGraphModule {

@Provides
@Singleton
fun provideApplication(@ApplicationContext context: Context): Application {
return context.applicationContext as Application
}

@Provides
@Singleton
fun provideTrackerServiceController(application: Application): TrackerServiceController {
return application.appGraph.trackerServiceController
}

@Provides
@Singleton
fun provideLockManager(application: Application): LockManager {
return application.appGraph.lockManager
}

@Provides
@Singleton
fun provideTrackerSessionChannel(application: Application): TrackerSessionChannel {
return application.appGraph.trackerSessionChannel
}
}