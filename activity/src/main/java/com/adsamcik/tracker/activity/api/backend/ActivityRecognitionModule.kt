package com.adsamcik.tracker.activity.api.backend

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that binds the [GmsActivityRecognitionBackend] as the default
 * [ActivityRecognitionBackend] implementation.
 *
 * To swap backends (e.g. for on-device ML), replace this binding or use
 * a qualifier.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ActivityRecognitionModule {
	@Binds
	@Singleton
	abstract fun bindBackend(
		impl: GmsActivityRecognitionBackend,
	): ActivityRecognitionBackend
}
