package com.adsamcik.tracker.tracker.source.catalog

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.source.ambient.steps.AndroidAmbientStepsCapabilityResolver
import com.adsamcik.tracker.tracker.source.coordinator.SemanticAcquisitionPlanFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.LocationDeviceStateProvider
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeRegistry
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSourceImplementationCatalog internal constructor(
	private val runtimeRegistry: SourceRuntimeRegistry,
	private val acquisitionRevisionFactory: SourceAcquisitionRevisionFactory,
	private val availabilityReaders: SourceProviderAvailabilityReaderFactory,
	private val productFactories: SourceProductFactories,
) : SourceImplementationCatalog {
	@Inject
	constructor(
		runtimeRegistry: SourceRuntimeRegistry,
		semanticAcquisitionPlanFactory: SemanticAcquisitionPlanFactory,
		ambientStepsCapabilityResolver: AndroidAmbientStepsCapabilityResolver,
		locationDeviceStateProvider: LocationDeviceStateProvider,
		productFactories: SourceProductFactories,
	) : this(
		runtimeRegistry,
		SourceAcquisitionRevisionFactory(semanticAcquisitionPlanFactory::create),
		DefaultSourceProviderAvailabilityReaderFactory(
			ambientStepsCapabilityResolver,
			locationDeviceStateProvider,
		),
		productFactories,
	)

	private val implementationsBySource: Map<TrackingSource, SourceImplementation>

	override val implementations: Set<SourceImplementation>
	override val bindings: Set<SourcePurposeBinding>

	init {
		val expectedRuntimeSources = SourceKind.entries.toSet()
		require(runtimeRegistry.registeredSources() == expectedRuntimeSources) {
			"Source catalog requires exactly the six runtime owners: $expectedRuntimeSources"
		}
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

	private fun createImplementation(source: TrackingSource): SourceImplementation {
		val runtimeSource = source.toRuntimeSourceKind()
		val runtime = requireNotNull(runtimeRegistry.runtime(runtimeSource)) {
			"Missing runtime owner for $runtimeSource"
		}
		return SourceImplementation(
			source = source,
			runtimeSource = runtimeSource,
			runtime = runtime,
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
							runtime,
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
