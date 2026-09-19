package com.adsamcik.tracker.tracker.di

import android.content.Context
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjection
import com.adsamcik.tracker.tracker.source.projection.Projection
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.runtime.AndroidBootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.ActivitySourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioMutationLeaseGuard
import com.adsamcik.tracker.tracker.source.runtime.ClaimedSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.PressureSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.LocationSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.SerializedTrackingPurposeLeaseIssuer
import com.adsamcik.tracker.tracker.source.runtime.TrackingPurposeMutationLeaseGuard
import com.adsamcik.tracker.tracker.source.runtime.SharedCellSourceController
import com.adsamcik.tracker.tracker.source.runtime.SharedStepSourceController
import com.adsamcik.tracker.tracker.source.runtime.SharedWifiSourceController
import com.adsamcik.tracker.tracker.source.coordinator.ProtectedLocationSourceDrain
import com.adsamcik.tracker.tracker.source.coordinator.RequiredProtectedLocationSourceDrain
import com.adsamcik.tracker.tracker.source.coordinator.RoomSourceProductDrainRouter
import com.adsamcik.tracker.tracker.source.coordinator.SourceProductDrainRouter
import com.adsamcik.tracker.tracker.source.coordinator.LegacySourceWriterTransitionBoundary
import com.adsamcik.tracker.tracker.source.coordinator.PersistenceLegacySourceWriterTransitionBoundary
import com.adsamcik.tracker.tracker.source.coordinator.MonotonicRearmSourceWriterSupport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SourcePipelineModule {
	@Provides
	@Singleton
	fun provideBootClockDomainProvider(
		@ApplicationContext context: Context,
	): BootClockDomainProvider = AndroidBootClockDomainProvider(context)

	@Provides
	@Singleton
	internal fun provideAmbientRadioMutationLeaseGuard(
		issuer: SerializedTrackingPurposeLeaseIssuer,
	): AmbientRadioMutationLeaseGuard = issuer

	@Provides
	@Singleton
	internal fun provideTrackingPurposeMutationLeaseGuard(
		issuer: SerializedTrackingPurposeLeaseIssuer,
	): TrackingPurposeMutationLeaseGuard = issuer

	@Provides
	@IntoSet
	fun provideActivityAutomationProjection(projection: ActivityAutomationProjection): Projection = projection

	@Provides
	@IntoSet
	fun provideActivitySourceRuntime(runtime: ActivitySourceRuntime): ClaimedSourceRuntime<out SourcePlan> = runtime

	@Provides
	@IntoSet
	fun provideStepSourceRuntime(runtime: SharedStepSourceController): ClaimedSourceRuntime<out SourcePlan> = runtime

	@Provides
	@IntoSet
	fun providePressureSourceRuntime(runtime: PressureSourceRuntime): ClaimedSourceRuntime<out SourcePlan> = runtime

	@Provides
	@IntoSet
	fun provideLocationSourceRuntime(runtime: LocationSourceRuntime): ClaimedSourceRuntime<out SourcePlan> = runtime

	@Provides
	@IntoSet
	fun provideWifiSourceRuntime(
		runtime: SharedWifiSourceController,
	): ClaimedSourceRuntime<out SourcePlan> = runtime

	@Provides
	@IntoSet
	fun provideCellSourceRuntime(
		runtime: SharedCellSourceController,
	): ClaimedSourceRuntime<out SourcePlan> = runtime

	@Provides
	@Singleton
	fun provideProtectedLocationSourceDrain(
		required: RequiredProtectedLocationSourceDrain,
	): ProtectedLocationSourceDrain = required

	@Provides
	@Singleton
	fun provideSourceProductDrainRouter(
		router: RoomSourceProductDrainRouter,
	): SourceProductDrainRouter = router

	@Provides
	@Singleton
	internal fun provideLegacySourceWriterTransitionBoundary(
		boundary: PersistenceLegacySourceWriterTransitionBoundary,
	): LegacySourceWriterTransitionBoundary = boundary
}

@Module
@InstallIn(SingletonComponent::class)
internal interface SourceWriterCapabilityModule {
	@Multibinds
	fun monotonicRearmSourceWriterSupport(): Set<MonotonicRearmSourceWriterSupport>
}
