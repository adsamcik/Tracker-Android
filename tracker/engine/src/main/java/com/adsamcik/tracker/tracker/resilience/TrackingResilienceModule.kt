package com.adsamcik.tracker.tracker.resilience

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TrackingResilienceModule {
	@Binds
	@Singleton
	abstract fun bindActiveTrackingSessionStore(
		implementation: DefaultActiveTrackingSessionStore,
	): ActiveTrackingSessionStore

	@Binds
	@Singleton
	abstract fun bindTrackingLifecycleCommandAuthority(
		implementation: SharedPreferencesTrackingLifecycleCommandAuthority,
	): TrackingLifecycleCommandAuthority

	@Binds
	@Singleton
	abstract fun bindTrackingStartupGuard(
		implementation: DefaultTrackingStartupGuard,
	): TrackingStartupGuard

	@Binds
	@Singleton
	abstract fun bindPendingSignalDrainScheduler(
		implementation: WorkManagerPendingSignalDrainScheduler,
	): PendingSignalDrainScheduler
}
