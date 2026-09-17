package com.adsamcik.tracker.tracker.di

import com.adsamcik.tracker.tracker.api.AtomicTrackingPurposeAvailabilityStore
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailabilityReader
import com.adsamcik.tracker.tracker.api.SourceCallerGuard
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReader
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReporter
import com.adsamcik.tracker.tracker.api.TrackingPurposeDeletionFencer
import com.adsamcik.tracker.tracker.api.TrackingPurposeReconciliationRetryScheduler
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciler
import com.adsamcik.tracker.tracker.api.TrackingPurposeSourceOwnerRegistrar
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciler
import com.adsamcik.tracker.tracker.source.runtime.AmbientStepsPurposeOwner
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityReader
import com.adsamcik.tracker.tracker.source.runtime.CurrentTrackingPurposeAuthorityReader
import com.adsamcik.tracker.tracker.source.runtime.CurrentTrackingPurposeAvailabilityProjection
import com.adsamcik.tracker.tracker.source.runtime.CurrentSourceCallerAuthorityReader
import com.adsamcik.tracker.tracker.source.runtime.CurrentSourceCallerAuthorityProvider
import com.adsamcik.tracker.tracker.source.runtime.DefaultAmbientStepsPurposeOwner
import com.adsamcik.tracker.tracker.source.runtime.DefaultTrackingPurposePublicationRuntime
import com.adsamcik.tracker.tracker.source.runtime.ExactSourceCallerGuard
import com.adsamcik.tracker.tracker.source.runtime.GuardedSourceCallerDemandDispatcher
import com.adsamcik.tracker.tracker.source.runtime.RoomSourceCallerAcceptedAuthorityRepository
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerAcceptedAuthorityRepository
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerAuthoritySnapshotReader
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerDemandDispatcher
import com.adsamcik.tracker.tracker.source.runtime.TrackingPurposeAuthorityReader
import com.adsamcik.tracker.tracker.source.runtime.TrackingPurposeOwnerCasTokenFactory
import com.adsamcik.tracker.tracker.worker.TrackingPurposeReconciliationWorkScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TrackingPurposePublicationModule {
	@Binds
	internal abstract fun bindAuthorityReader(
		impl: CurrentTrackingPurposeAuthorityReader,
	): TrackingPurposeAuthorityReader

	@Binds
	internal abstract fun bindSettingsReconciler(
		impl: DefaultTrackingPurposePublicationRuntime,
	): TrackingPurposeSettingsReconciler

	@Binds
	internal abstract fun bindDeletionFencer(
		impl: DefaultTrackingPurposePublicationRuntime,
	): TrackingPurposeDeletionFencer

	@Binds
	internal abstract fun bindReconciliationRetryScheduler(
		impl: TrackingPurposeReconciliationWorkScheduler,
	): TrackingPurposeReconciliationRetryScheduler

	@Binds
	internal abstract fun bindRetentionFloorReconciler(
		impl: DefaultTrackingPurposePublicationRuntime,
	): TrackingRetentionFloorReconciler

	@Binds
	internal abstract fun bindSourceOwnerRegistrar(
		impl: DefaultTrackingPurposePublicationRuntime,
	): TrackingPurposeSourceOwnerRegistrar

	@Binds
	internal abstract fun bindAmbientStepsPurposeOwner(
		impl: DefaultAmbientStepsPurposeOwner,
	): AmbientStepsPurposeOwner

	@Binds
	internal abstract fun bindCurrentAvailabilityReader(
		impl: CurrentTrackingPurposeAvailabilityProjection,
	): CurrentTrackingPurposeAvailabilityReader

	@Binds
	internal abstract fun bindSourceCallerAuthorityReader(
		impl: CurrentSourceCallerAuthorityReader,
	): SourceCallerAuthoritySnapshotReader

	@Binds
	internal abstract fun bindCurrentSourceCallerAuthorityProvider(
		impl: CurrentSourceCallerAuthorityReader,
	): CurrentSourceCallerAuthorityProvider

	@Binds
	internal abstract fun bindSourceCallerAuthorityRepository(
		impl: RoomSourceCallerAcceptedAuthorityRepository,
	): SourceCallerAcceptedAuthorityRepository

	@Binds
	internal abstract fun bindSourceCallerGuard(
		impl: ExactSourceCallerGuard,
	): SourceCallerGuard

	@Binds
	internal abstract fun bindSourceCallerDemandDispatcher(
		impl: GuardedSourceCallerDemandDispatcher,
	): SourceCallerDemandDispatcher

	companion object {
		@Provides
		@Singleton
		fun provideAvailabilityStore(): AtomicTrackingPurposeAvailabilityStore =
			AtomicTrackingPurposeAvailabilityStore()

		@Provides
		fun provideAvailabilityReader(
			store: AtomicTrackingPurposeAvailabilityStore,
		): TrackingPurposeAvailabilityReader = store

		@Provides
		fun provideAvailabilityReporter(
			store: AtomicTrackingPurposeAvailabilityStore,
		): TrackingPurposeAvailabilityReporter = store

		@Provides
		internal fun provideOwnerCasTokenFactory(): TrackingPurposeOwnerCasTokenFactory =
			TrackingPurposeOwnerCasTokenFactory { UUID.randomUUID().toString() }

		@Provides
		@Singleton
		internal fun provideRetentionAuthorityReader(
			producer: RetentionAuthorityProducer,
		): RetentionAuthorityReader = producer
	}
}
