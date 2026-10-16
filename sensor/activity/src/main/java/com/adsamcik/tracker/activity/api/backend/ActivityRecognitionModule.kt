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
import com.adsamcik.tracker.activity.api.registration.ActivityMotionAssistance
import com.adsamcik.tracker.activity.api.registration.DefaultActivityMotionAssistance

/**
 * Hilt bindings for activity-recognition implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ActivityRecognitionModule {
	/** Shares transient Location motion assistance through the existing Activity registration arbiter. */
	@Binds
	@Singleton
	abstract fun bindMotionAssistance(impl: DefaultActivityMotionAssistance): ActivityMotionAssistance

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
