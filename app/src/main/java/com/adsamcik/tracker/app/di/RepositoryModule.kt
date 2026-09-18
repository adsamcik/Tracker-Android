package com.adsamcik.tracker.app.di

import android.content.Context
import com.adsamcik.tracker.game.repository.DefaultGameRepository
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.game.goals.settings.DefaultGoalsSettingsRepository
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.preferences.map.DefaultMapSettingsRepository
import com.adsamcik.tracker.shared.preferences.map.DefaultOnlineMapTilesRepository
import com.adsamcik.tracker.shared.preferences.map.MapSettingsRepository
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesRepository
import com.adsamcik.tracker.shared.preferences.onboarding.DefaultOnboardingRepository
import com.adsamcik.tracker.shared.preferences.onboarding.OnboardingRepository
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.retention.DefaultRetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.LocationPassiveRetentionAuthority
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityOperationLease
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.lifecycle.DefaultCollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.settings.DefaultTrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.tracking.DefaultTrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.AmbientStepsPolicyRevisionReconciliation
import com.adsamcik.tracker.shared.preferences.tracking.AmbientStepsPolicyRevisionReconciler
import com.adsamcik.tracker.shared.preferences.tracking.AndroidSourcePolicyEffectiveTimeProvider
import com.adsamcik.tracker.shared.preferences.tracking.AuthoritativeTrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityBootstrapCoordinator
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationCoordinator
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.api.AmbientStepsProviderLifecycle
import com.adsamcik.tracker.tracker.api.AmbientStepsSettingsReconciliationResult
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * Hilt module providing repository bindings.
 * Binds interface types to their default implementations.
 * 
 * Per copilot-instructions Section 16A:
 * - Separate interface from implementation across module boundaries
 * - Repositories are application-scoped (long-lived)
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

	/**
	 * Binds GameRepository interface to its default implementation.
	 * Used by GameViewModel for accessing game data.
	 */
	@Binds
	@Singleton
	abstract fun bindGameRepository(
		impl: DefaultGameRepository,
	): GameRepository

	companion object {
		/**
		 * Provides TrackerSettingsRepository.
		 * Uses @Provides instead of @Binds because DefaultTrackerSettingsRepository
		 * has optional constructor parameters that need explicit handling.
		 */
		@Provides
		@Singleton
		fun provideTrackerSettingsRepository(
			@ApplicationContext context: Context,
			dispatchers: DispatchersProvider,
		): TrackerSettingsRepository = DefaultTrackerSettingsRepository(
			context = context,
			io = dispatchers.io,
		)

		@Provides
		@Singleton
		fun provideMapSettingsRepository(
			@ApplicationContext context: Context,
			dispatchers: DispatchersProvider,
		): MapSettingsRepository = DefaultMapSettingsRepository(
			context = context,
			io = dispatchers.io,
		)

		/**
		 * Provides the online-map-tiles preference repository. Defaults are
		 * fully opt-out (enabled=false, OpenFreeMap as the would-be provider
		 * when enabled). See `com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesRepository`.
		 */
		@Provides
		@Singleton
		fun provideOnlineMapTilesRepository(
			@ApplicationContext context: Context,
			dispatchers: DispatchersProvider,
		): OnlineMapTilesRepository = DefaultOnlineMapTilesRepository(
			context = context,
			io = dispatchers.io,
		)

		@Provides
		@Singleton
		fun provideGoalsSettingsRepository(
			@ApplicationContext context: Context,
			dispatchers: DispatchersProvider,
		): GoalsSettingsRepository = DefaultGoalsSettingsRepository(
			context = context,
			io = dispatchers.io,
		)

		@Provides
		@Singleton
		fun provideAuthoritativeTrackingParamsRepository(
			@ApplicationContext context: Context,
			dispatchers: DispatchersProvider,
			sourcePolicyRepository: SourcePolicyRepository,
			@ApplicationScope applicationScope: CoroutineScope,
			trackingStartupGate: TrackingStartupGate,
			retentionAuthorityProducer: RetentionAuthorityProducer,
			ambientStepsProviderLifecycle: Provider<AmbientStepsProviderLifecycle>,
		): AuthoritativeTrackingParamsRepository = AuthoritativeTrackingParamsRepository(
			legacy = DefaultTrackingParamsRepository(
				context = context,
				io = dispatchers.io,
			),
			sourcePolicyRepository = sourcePolicyRepository,
			applicationScope = applicationScope,
			trackingStartupGate = trackingStartupGate,
			retentionAuthorityProducer = retentionAuthorityProducer,
			ambientStepsPolicyRevisionReconciler =
				object : AmbientStepsPolicyRevisionReconciler {
					override suspend fun reconcileAfterRetentionReissue() =
						ambientStepsProviderLifecycle.get()
							.reconcileAfterSettingsChange()
							.toPolicyRevisionReconciliation()

					override suspend fun retireAfterRetentionDebt() =
						ambientStepsProviderLifecycle.get()
							.retireAfterRetentionAuthorityFailure()
							.toPolicyRevisionReconciliation()
				},
		)

		@Provides
		fun provideTrackingParamsRepository(
			repository: AuthoritativeTrackingParamsRepository,
		): TrackingParamsRepository = repository

		@Provides
		fun provideSourcePolicyRevisionReconciliationCoordinator(
			repository: AuthoritativeTrackingParamsRepository,
		): SourcePolicyRevisionReconciliationCoordinator = repository

		@Provides
		fun provideSourcePolicyAuthorityBootstrapCoordinator(
			repository: AuthoritativeTrackingParamsRepository,
		): SourcePolicyAuthorityBootstrapCoordinator = repository

		@Provides
		@Singleton
		fun provideSourcePolicyRepository(
			database: AppDatabase,
			clock: Clock,
			bootClockDomainProvider: BootClockDomainProvider,
		): SourcePolicyRepository = RoomSourcePolicyRepository(
			database = database,
			effectiveTimeProvider = AndroidSourcePolicyEffectiveTimeProvider(
				bootClockDomainProvider,
				clock,
			),
		)

		@Provides
		@Singleton
		fun provideRetentionConfigStore(
			@ApplicationContext context: Context,
			dispatchers: DispatchersProvider,
		): RetentionConfigStore = RetentionConfigStore(
			context = context,
			ioDispatcher = dispatchers.io,
		)

		@Provides
		@Singleton
		fun provideRetentionAuthorityProducer(
			database: AppDatabase,
			sourcePolicyRepository: SourcePolicyRepository,
			retentionConfigStore: RetentionConfigStore,
			collectedDataLifecycleStore: CollectedDataLifecycleStore,
			clock: Clock,
			bootClockDomainProvider: BootClockDomainProvider,
			operationLease: RetentionAuthorityOperationLease,
		): RetentionAuthorityProducer = DefaultRetentionAuthorityProducer(
			database = database,
			sourcePolicyRepository = sourcePolicyRepository,
			retentionConfigStore = retentionConfigStore,
			collectedDataLifecycleStore = collectedDataLifecycleStore,
			effectiveTimeProvider = AndroidSourcePolicyEffectiveTimeProvider(
				bootClockDomainProvider,
				clock,
			),
			operationLease = operationLease,
		)

		@Provides
		fun provideLocationPassiveRetentionAuthority(
			producer: RetentionAuthorityProducer,
		): LocationPassiveRetentionAuthority = producer

		@Provides
		@Singleton
		fun provideRetentionAuthorityOperationLease(): RetentionAuthorityOperationLease =
			RetentionAuthorityOperationLease()

		@Provides
		@Singleton
		fun provideCollectedDataLifecycleStore(
			@ApplicationContext context: Context,
			operationLease: RetentionAuthorityOperationLease,
		): CollectedDataLifecycleStore = DefaultCollectedDataLifecycleStore(
			context,
			operationLease,
		)

		@Provides
		@Singleton
		fun provideOnboardingRepository(
			@ApplicationContext context: Context,
			dispatchers: DispatchersProvider,
		): OnboardingRepository = DefaultOnboardingRepository(
			context = context,
			io = dispatchers.io,
		)
	}
}

private fun AmbientStepsSettingsReconciliationResult.toPolicyRevisionReconciliation():
	AmbientStepsPolicyRevisionReconciliation = when {
	complete -> AmbientStepsPolicyRevisionReconciliation.Complete
	retryable -> AmbientStepsPolicyRevisionReconciliation.Retryable(
		requireNotNull(failure).name,
	)
	else -> AmbientStepsPolicyRevisionReconciliation.Unverifiable(
		requireNotNull(failure).name,
	)
}
