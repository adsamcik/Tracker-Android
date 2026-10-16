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
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciler
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationResult
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import kotlinx.coroutines.flow.first
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

	// Keep the application settings, source-policy and retention owners in one DI composition.
	@Suppress("TooManyFunctions")
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
		/** Composes durable settings with source-policy, retention and all-day Steps reconciliation. */
		fun provideAuthoritativeTrackingParamsRepository(
			@ApplicationContext context: Context,
			dispatchers: DispatchersProvider,
			sourcePolicyRepository: SourcePolicyRepository,
			@ApplicationScope applicationScope: CoroutineScope,
			trackingStartupGate: TrackingStartupGate,
			onboardingRepository: OnboardingRepository,
			retentionAuthorityProducer: RetentionAuthorityProducer,
			purposeSettingsReconciler: Provider<TrackingPurposeSettingsReconciler>,
		): AuthoritativeTrackingParamsRepository = AuthoritativeTrackingParamsRepository(
			legacy = DefaultTrackingParamsRepository(
				context = context,
				io = dispatchers.io,
			),
			sourcePolicyRepository = sourcePolicyRepository,
			applicationScope = applicationScope,
			trackingStartupGate = trackingStartupGate,
			setupCompleted = onboardingRepository.isCompleted,
			retentionAuthorityProducer = retentionAuthorityProducer,
			ambientStepsPolicyRevisionReconciler =
				object : AmbientStepsPolicyRevisionReconciler {
					override suspend fun reconcileAfterRetentionReissue() =
						purposeSettingsReconciler.get()
							.reconcileCurrentSettings()
							.toPolicyRevisionReconciliation()

					override suspend fun retireAfterRetentionDebt() =
						purposeSettingsReconciler.get()
							.reconcileCurrentSettings()
							.toPolicyRevisionReconciliation()
				},
		)

		@Provides
		/** Exposes the authoritative settings instance to every source and screen. */
		fun provideTrackingParamsRepository(
			repository: AuthoritativeTrackingParamsRepository,
		): TrackingParamsRepository = repository

		@Provides
		/** Reuses authoritative settings reconciliation instead of creating a second policy owner. */
		fun provideSourcePolicyRevisionReconciliationCoordinator(
			repository: AuthoritativeTrackingParamsRepository,
		): SourcePolicyRevisionReconciliationCoordinator = repository

		@Provides
		/** Reuses the settings bootstrap that prepares source authority before collection. */
		fun provideSourcePolicyAuthorityBootstrapCoordinator(
			repository: AuthoritativeTrackingParamsRepository,
		): SourcePolicyAuthorityBootstrapCoordinator = repository

		@Provides
		@Singleton
		fun provideSourcePolicyRepository(
			database: AppDatabase,
			clock: Clock,
			bootClockDomainProvider: BootClockDomainProvider,
			onboardingRepository: OnboardingRepository,
		): SourcePolicyRepository = RoomSourcePolicyRepository(
			database = database,
			setupCompleted = { onboardingRepository.isCompleted.first() },
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
		/** Composes local retention approval with source policies, deletion lifecycle and passive Location. */
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
		/** Shares the retention producer’s passive Location reconciliation. */
		fun provideLocationPassiveRetentionAuthority(
			producer: RetentionAuthorityProducer,
		): LocationPassiveRetentionAuthority = producer

		@Provides
		@Singleton
		/** Shares serialized retention mutation ownership across Room and DataStore. */
		fun provideRetentionAuthorityOperationLease(
			@ApplicationScope applicationScope: CoroutineScope,
		): RetentionAuthorityOperationLease =
			RetentionAuthorityOperationLease(completionScope = applicationScope)

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

private fun TrackingPurposeSettingsReconciliationResult.toPolicyRevisionReconciliation():
	AmbientStepsPolicyRevisionReconciliation = when (this) {
	is TrackingPurposeSettingsReconciliationResult.Complete ->
		AmbientStepsPolicyRevisionReconciliation.Complete
	is TrackingPurposeSettingsReconciliationResult.Debt ->
		AmbientStepsPolicyRevisionReconciliation.Retryable(
			debt.failures.joinToString(separator = ",") { failure ->
				"${failure.source?.name ?: "GLOBAL"}:${failure.reason.name}"
			},
		)
}
