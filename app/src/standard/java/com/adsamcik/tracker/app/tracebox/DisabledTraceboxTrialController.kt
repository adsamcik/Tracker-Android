package com.adsamcik.tracker.app.tracebox

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps the production-compatible variants free of the API-30 Tracebox alpha. */
@Singleton
class DisabledTraceboxTrialController @Inject constructor() : TraceboxTrialController {
    private val disabled = MutableStateFlow(false)

    override val isEnabled: StateFlow<Boolean> = disabled.asStateFlow()

    override fun enableForCurrentAppRun() = Unit

    override fun disableAndClear() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StandardTraceboxTrialModule {
    @Binds
    @Singleton
    abstract fun bindTraceboxTrialController(
        implementation: DisabledTraceboxTrialController,
    ): TraceboxTrialController
}
