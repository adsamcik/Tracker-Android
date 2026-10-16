package com.adsamcik.tracker.tracker.di

import com.adsamcik.tracker.stats.api.repository.SavedLocationLiveUpdates

import android.content.Context
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.location.LocationWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationCanonicalHandoff
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationCanonicalWriterChain
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationLiveWriterRegistry
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationOfflineCanonicalWriter
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationAmbientCanonicalWriter
import com.adsamcik.tracker.tracker.source.coordinator.ProtectedLocationCanonicalSourceDrain
import kotlinx.coroutines.CoroutineScope
import com.adsamcik.tracker.tracker.source.catalog.DefaultSourceImplementationCatalog
import com.adsamcik.tracker.tracker.source.catalog.SourceAcquisitionPlanFactory
import com.adsamcik.tracker.tracker.source.catalog.SourceImplementationCatalog
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjection
import com.adsamcik.tracker.tracker.source.projection.AmbientCellProjection
import com.adsamcik.tracker.tracker.source.projection.AmbientWifiProjection
import com.adsamcik.tracker.tracker.source.projection.Projection
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.runtime.AndroidBootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.ActivitySourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioMutationLeaseGuard
import com.adsamcik.tracker.tracker.source.runtime.ClaimedSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.PressureSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.SharedLocationSourceController
import com.adsamcik.tracker.tracker.source.runtime.SerializedTrackingPurposeLeaseIssuer
import com.adsamcik.tracker.tracker.source.runtime.TrackingPurposeMutationLeaseGuard
import com.adsamcik.tracker.tracker.source.runtime.SharedCellSourceController
import com.adsamcik.tracker.tracker.source.runtime.SharedStepSourceController
import com.adsamcik.tracker.tracker.source.runtime.SharedWifiSourceController
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeKey
import com.adsamcik.tracker.tracker.source.coordinator.ProtectedLocationSourceDrain
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
import dagger.multibindings.IntoMap
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
	internal fun provideAmbientWifiProjection(projection: AmbientWifiProjection): Projection = projection

	@Provides
	@IntoSet
	internal fun provideAmbientCellProjection(projection: AmbientCellProjection): Projection = projection

	/** Registers the shared Activity owner in the source runtime map. */
	@Provides
	@IntoMap
	@SourceRuntimeKey(SourceKind.ACTIVITY)
	fun provideActivitySourceRuntime(runtime: ActivitySourceRuntime): ClaimedSourceRuntime<out SourcePlan> = runtime

	/** Registers the shared Steps sensor owner in the source runtime map. */
	@Provides
	@IntoMap
	@SourceRuntimeKey(SourceKind.STEPS)
	fun provideStepSourceRuntime(runtime: SharedStepSourceController): ClaimedSourceRuntime<out SourcePlan> = runtime

	/** Registers the Pressure owner in the source runtime map. */
	@Provides
	@IntoMap
	@SourceRuntimeKey(SourceKind.PRESSURE)
	fun providePressureSourceRuntime(runtime: PressureSourceRuntime): ClaimedSourceRuntime<out SourcePlan> = runtime

	/** Registers the single Location owner shared by recording and temporary consumers. */
	@Provides
	@IntoMap
	@SourceRuntimeKey(SourceKind.LOCATION)
	fun provideLocationSourceRuntime(runtime: SharedLocationSourceController): ClaimedSourceRuntime<out SourcePlan> =
        runtime

	/** Registers the Wi-Fi owner shared by session and all-day demands. */
	@Provides
	@IntoMap
	@SourceRuntimeKey(SourceKind.WIFI)
	fun provideWifiSourceRuntime(
		runtime: SharedWifiSourceController,
	): ClaimedSourceRuntime<out SourcePlan> = runtime

	/** Registers the Cell owner shared by session and all-day demands. */
	@Provides
	@IntoMap
	@SourceRuntimeKey(SourceKind.CELL)
	fun provideCellSourceRuntime(
		runtime: SharedCellSourceController,
	): ClaimedSourceRuntime<out SourcePlan> = runtime

	@Provides
	@Singleton
	internal fun provideProtectedLocationSourceDrain(
		handoff: ProtectedLocationCanonicalHandoff,
	): ProtectedLocationSourceDrain = ProtectedLocationCanonicalSourceDrain(handoff)

	@Provides
	@Singleton
	internal fun provideProtectedLocationCanonicalHandoff(
		database: AppDatabase,
		qualifier: LocationWalQualificationAdapter,
		live: ProtectedLocationLiveWriterRegistry,
		offline: ProtectedLocationOfflineCanonicalWriter,
		ambient: ProtectedLocationAmbientCanonicalWriter,
        savedLocationUpdates: SavedLocationLiveUpdates,
		@ApplicationScope scope: CoroutineScope,
	): ProtectedLocationCanonicalHandoff = ProtectedLocationCanonicalHandoff(
		database, qualifier, ProtectedLocationCanonicalWriterChain(live::current, offline, ambient), scope,
        savedLocationUpdates::pointCommitted,
	)

	/** Binds the router that settles source writers through a captured admission boundary. */
	@Provides
	@Singleton
	fun provideSourceProductDrainRouter(
		router: RoomSourceProductDrainRouter,
	): SourceProductDrainRouter = router

	/** Binds the read-only catalog of supported acquisition and product contracts. */
	@Provides
	@Singleton
	fun provideSourceImplementationCatalog(
		catalog: DefaultSourceImplementationCatalog,
	): SourceImplementationCatalog = catalog

	/** Uses the catalog to build acquisition plans without starting providers. */
	@Provides
	@Singleton
	fun provideSourceAcquisitionPlanFactory(
		catalog: SourceImplementationCatalog,
	): SourceAcquisitionPlanFactory = catalog

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
