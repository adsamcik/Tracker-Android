package com.adsamcik.tracker.tracker.di

import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjection
import com.adsamcik.tracker.tracker.source.projection.ExplicitTrackingJoinProjection
import com.adsamcik.tracker.tracker.source.projection.EventTrackingFrameProjection
import com.adsamcik.tracker.tracker.source.projection.LocationDomainProjection
import com.adsamcik.tracker.tracker.source.projection.Projection
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.coordinator.CanonicalWriter
import com.adsamcik.tracker.tracker.source.coordinator.ConsumerMigration
import com.adsamcik.tracker.tracker.source.coordinator.ConsumerMigrationRegistry
import com.adsamcik.tracker.tracker.source.projection.LateCorrectionPolicy
import com.adsamcik.tracker.tracker.source.projection.TrackingJoinSpecs
import com.adsamcik.tracker.tracker.source.runtime.AndroidBootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.ActivitySourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.PressureSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.LocationSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.CellSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.StepSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.WifiSourceRuntime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
object SourcePipelineModule {
	@Provides
	fun provideConsumerMigrationRegistry(): ConsumerMigrationRegistry = ConsumerMigrationRegistry(
		TrackingJoinSpecs.contracts.map { contract ->
			ConsumerMigration(
				id = contract.consumerId,
				currentInput = "TrackingCycle implicit latest-value correlation",
				targetInput = "JoinedFrame(${contract.spec.id})",
				canonicalWriter = CanonicalWriter.EVENT_PROJECTION,
				stableOutputIdentity = "${contract.consumerId}:primary-event-id",
				checkpointStore = "source_projection_checkpoint/${ExplicitTrackingJoinProjection.ID}",
				lateCorrectionPolicy = LateCorrectionPolicy.APPEND_ONLY_CORRECTION,
				retirementGate = when (contract.consumerId) {
					"wifi-interpolation" -> "Phase 7 Wi-Fi throttle/cache replay parity and domain-owner acceptance"
					"cell-presence" -> "Phase 7 cell callback/cache/multi-SIM replay parity and domain-owner acceptance"
					else -> "Phase 4 golden replay parity and domain-owner acceptance"
				},
			)
		} + listOf(
			ConsumerMigration(
				id = "route-location-persistence",
				currentInput = "LocationTrackerComponent accepted TrackingCycle location",
				targetInput = "${LocationDomainProjection.ID}:${LocationDomainProjection.ROUTE_EFFECT_KIND}",
				canonicalWriter = CanonicalWriter.EVENT_PROJECTION,
				stableOutputIdentity = "logical-session:location-event-id:revision",
				checkpointStore = "source_projection_checkpoint/${LocationDomainProjection.ID}",
				lateCorrectionPolicy = LateCorrectionPolicy.APPEND_ONLY_CORRECTION,
				retirementGate = "Phase 6 route/distance replay parity and domain-owner acceptance",
			),
			ConsumerMigration(
				id = "location-speed",
				currentInput = "LocationTrackerComponent calculated/smoothed speed",
				targetInput = "${LocationDomainProjection.ID}:${LocationDomainProjection.SPEED_EFFECT_KIND}",
				canonicalWriter = CanonicalWriter.EVENT_PROJECTION,
				stableOutputIdentity = "logical-session:location-event-id:revision",
				checkpointStore = "source_projection_checkpoint/${LocationDomainProjection.ID}",
				lateCorrectionPolicy = LateCorrectionPolicy.APPEND_ONLY_CORRECTION,
				retirementGate = "Phase 6 speed golden replay parity",
			),
			ConsumerMigration(
				id = "location-altitude",
				currentInput = "LocationTrackerComponent raw altitude and AltitudeProcessor fusion",
				targetInput = "${LocationDomainProjection.ID}:${LocationDomainProjection.ALTITUDE_EFFECT_KIND} + pressure-altitude-fusion join",
				canonicalWriter = CanonicalWriter.EVENT_PROJECTION,
				stableOutputIdentity = "logical-session:location-event-id:revision",
				checkpointStore = "source_projection_checkpoint/${LocationDomainProjection.ID}",
				lateCorrectionPolicy = LateCorrectionPolicy.APPEND_ONLY_CORRECTION,
				retirementGate = "Phase 6 raw-altitude parity; fused altitude remains gated on domain acceptance",
			),
			ConsumerMigration(
				id = "location-policy-evidence",
				currentInput = "TrackerPolicyFeeder location speed/displacement callbacks",
				targetInput = "${LocationDomainProjection.ID}:${LocationDomainProjection.POLICY_EFFECT_KIND}",
				canonicalWriter = CanonicalWriter.EVENT_PROJECTION,
				stableOutputIdentity = "logical-session:location-event-id:revision",
				checkpointStore = "source_projection_checkpoint/${LocationDomainProjection.ID}",
				lateCorrectionPolicy = LateCorrectionPolicy.APPEND_ONLY_CORRECTION,
				retirementGate = "Phase 6 policy replay parity and tracker/control owner acceptance",
			),
		),
	)

	@Provides
	fun provideBootClockDomainProvider(provider: AndroidBootClockDomainProvider): BootClockDomainProvider = provider

	@Provides
	@IntoSet
	fun provideActivityAutomationProjection(projection: ActivityAutomationProjection): Projection = projection

	@Provides
	@IntoSet
	fun provideLocationDomainProjection(projection: LocationDomainProjection): Projection = projection

	@Provides
	@IntoSet
	fun provideEventTrackingFrameProjection(projection: EventTrackingFrameProjection): Projection = projection

	@Provides
	@IntoSet
	fun provideExplicitTrackingJoinProjection(projection: ExplicitTrackingJoinProjection): Projection = projection

	@Provides
	@IntoSet
	fun provideActivitySourceRuntime(runtime: ActivitySourceRuntime): SourceRuntime<out SourcePlan> = runtime

	@Provides
	@IntoSet
	fun provideStepSourceRuntime(runtime: StepSourceRuntime): SourceRuntime<out SourcePlan> = runtime

	@Provides
	@IntoSet
	fun providePressureSourceRuntime(runtime: PressureSourceRuntime): SourceRuntime<out SourcePlan> = runtime

	@Provides
	@IntoSet
	fun provideLocationSourceRuntime(runtime: LocationSourceRuntime): SourceRuntime<out SourcePlan> = runtime

	@Provides
	@IntoSet
	fun provideWifiSourceRuntime(runtime: WifiSourceRuntime): SourceRuntime<out SourcePlan> = runtime

	@Provides
	@IntoSet
	fun provideCellSourceRuntime(runtime: CellSourceRuntime): SourceRuntime<out SourcePlan> = runtime
}
