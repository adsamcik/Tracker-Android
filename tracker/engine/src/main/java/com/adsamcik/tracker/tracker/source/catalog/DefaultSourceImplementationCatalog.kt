package com.adsamcik.tracker.tracker.source.catalog

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.ambient.steps.AndroidAmbientStepsCapabilityResolver
import com.adsamcik.tracker.tracker.source.coordinator.SemanticAcquisitionPlanFactory
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanEnvironment
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.AndroidConnectivityDeviceStateProvider
import com.adsamcik.tracker.tracker.source.runtime.LocationDeviceStateProvider
import com.adsamcik.tracker.tracker.source.runtime.LocationPrerequisiteEvaluator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSourceImplementationCatalog internal constructor(
	private val acquisitionRevisionFactory: SourceAcquisitionRevisionFactory,
	private val availabilityReaders: SourceProviderAvailabilityReaderFactory,
	private val productFactories: SourceProductFactories,
) : SourceImplementationCatalog {
	@Inject
	internal constructor(
		semanticAcquisitionPlanFactory: SemanticAcquisitionPlanFactory,
		ambientStepsCapabilityResolver: AndroidAmbientStepsCapabilityResolver,
		locationDeviceStateProvider: LocationDeviceStateProvider,
		locationPrerequisiteEvaluator: LocationPrerequisiteEvaluator,
		activityRecognitionSourceStateProvider: AndroidActivityRecognitionSourceStateProvider,
		sensorSourceStateProvider: AndroidSensorSourceStateProvider,
		connectivityDeviceStateProvider: AndroidConnectivityDeviceStateProvider,
		productFactories: SourceProductFactories,
	) : this(
		SourceAcquisitionRevisionFactory(semanticAcquisitionPlanFactory::create),
		DefaultSourceProviderAvailabilityReaderFactory(
			ambientStepsCapabilityResolver,
			locationDeviceStateProvider,
			locationPrerequisiteEvaluator,
			activityRecognitionSourceStateProvider,
			sensorSourceStateProvider,
			connectivityDeviceStateProvider,
		),
		productFactories,
	)

	private val implementationsBySource: Map<TrackingSource, SourceImplementation>

	override val implementations: Set<SourceImplementation>
	override val bindings: Set<SourcePurposeBinding>

	init {
		implementationsBySource = TrackingSource.entries.associateWith(::createImplementation)
		require(implementationsBySource.size == SourceKind.entries.size)
		implementations = implementationsBySource.values.toSet()
		require(implementations.size == SourceKind.entries.size)
		bindings = implementations.flatMap { it.purposeBindings.values }.toSet()
		require(bindings.map { SourcePurposeKey(it.source, it.purpose) }.toSet().size ==
			TrackingSource.entries.size * TrackingPurpose.entries.size)
	}

	override fun implementation(source: TrackingSource): SourceImplementation =
		implementationsBySource.getValue(source)

	override fun create(
		settings: TrackingParamsState,
		revision: Long,
		createdAtMs: Long,
		environment: SourcePlanEnvironment,
	): AcquisitionPlanRevision =
		acquisitionRevisionFactory.create(settings, revision, createdAtMs, environment)

	private fun createImplementation(source: TrackingSource): SourceImplementation {
		val runtimeSource = source.toRuntimeSourceKind()
		return SourceImplementation(
			source = source,
			runtimeSource = runtimeSource,
			acquisitionPlans = SourceAcquisitionPlanProvider(
				runtimeSource,
				acquisitionRevisionFactory,
			),
			purposeBindings = TrackingPurpose.entries.associateWith { purpose ->
				if (source.supports(purpose)) {
					val identity = source.forPurpose(purpose)
					SourcePurposeBinding.Executable(
						identity = identity,
						activationDefault = purpose.defaultActivation(),
						providerAvailability = availabilityReaders.create(
							source,
							purpose,
						),
						products = productFactories.create(identity),
					)
				} else {
					SourcePurposeBinding.Unsupported(
						source,
						purpose,
						UnsupportedSourcePurposeReason.NOT_CANONICALLY_SUPPORTED,
					)
				}
			},
		)
	}
}

private fun TrackingPurpose.defaultActivation(): SourceActivationDefault = when (this) {
	TrackingPurpose.SESSION_CAPTURE -> SourceActivationDefault.SESSION_DEMAND_DRIVEN
	TrackingPurpose.CONTROL -> SourceActivationDefault.CONTROL_OFF
	TrackingPurpose.AMBIENT_PRODUCT -> SourceActivationDefault.AMBIENT_OFF
}
