package com.adsamcik.tracker.activity.api.backend

import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.activity.api.DefaultActivityRequestManager
import com.adsamcik.tracker.activity.ski.DefaultSkiInfrastructureManager
import com.adsamcik.tracker.activity.ski.SkiInfrastructureManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.DefaultActivityRegistrationArbiter

/**
 * Hilt bindings for activity-recognition implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ActivityRecognitionModule {
    @Binds
    @Singleton
    abstract fun bindBackend(
        impl: ArbitratedActivityRecognitionBackend,
    ): ActivityRecognitionBackend

	@Binds
	@Singleton
	abstract fun bindActivityRegistrationArbiter(
		impl: DefaultActivityRegistrationArbiter,
	): ActivityRegistrationArbiter

    @Binds
    @Singleton
    abstract fun bindActivityRequestManager(
        impl: DefaultActivityRequestManager,
    ): ActivityRequestManager

    @Binds
    @Singleton
    abstract fun bindSkiInfrastructureManager(
        impl: DefaultSkiInfrastructureManager,
    ): SkiInfrastructureManager
}
