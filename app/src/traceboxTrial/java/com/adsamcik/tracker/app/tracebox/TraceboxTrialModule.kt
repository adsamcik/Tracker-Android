package com.adsamcik.tracker.app.tracebox

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TraceboxTrialModule {
    @Binds
    @Singleton
    abstract fun bindTraceboxTrialController(
        implementation: AlphaTraceboxTrialController,
    ): TraceboxTrialController
}
