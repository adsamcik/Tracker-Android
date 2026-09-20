package com.adsamcik.tracker.tracker.source.catalog

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanEnvironment
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan

internal fun interface SourceAcquisitionRevisionFactory {
	fun create(
		settings: TrackingParamsState,
		revision: Long,
		createdAtMs: Long,
		environment: SourcePlanEnvironment,
	): AcquisitionPlanRevision
}

fun interface SourceAcquisitionPlanFactory {
	fun create(
		settings: TrackingParamsState,
		revision: Long,
		createdAtMs: Long,
		environment: SourcePlanEnvironment,
	): AcquisitionPlanRevision
}

class SourceAcquisitionPlanProvider internal constructor(
	private val source: SourceKind,
	private val revisionFactory: SourceAcquisitionRevisionFactory,
) {
	fun create(
		settings: TrackingParamsState,
		revision: Long,
		createdAtMs: Long,
		environment: SourcePlanEnvironment,
	): SourcePlan = revisionFactory.create(
		settings,
		revision,
		createdAtMs,
		environment,
	).plans.getValue(source)
}

data class SourceImplementation(
	val source: TrackingSource,
	val runtimeSource: SourceKind,
	val acquisitionPlans: SourceAcquisitionPlanProvider,
	val purposeBindings: Map<TrackingPurpose, SourcePurposeBinding>,
) {
	init {
		require(runtimeSource == source.toRuntimeSourceKind())
		require(purposeBindings.keys == TrackingPurpose.entries.toSet())
		require(purposeBindings.all { (purpose, binding) ->
			binding.source == source && binding.purpose == purpose
		})
	}

	fun binding(purpose: TrackingPurpose): SourcePurposeBinding =
		purposeBindings.getValue(purpose)
}

/**
 * Read-only assembly of source ownership and product contracts.
 *
 * Lookup never creates demand, registers a provider, grants retention, or activates a writer.
 */
interface SourceImplementationCatalog : SourceAcquisitionPlanFactory {
	val implementations: Set<SourceImplementation>
	val bindings: Set<SourcePurposeBinding>

	fun implementation(source: TrackingSource): SourceImplementation

	fun implementation(source: SourceKind): SourceImplementation =
		implementation(source.toCanonicalTrackingSource())

	fun binding(
		source: TrackingSource,
		purpose: TrackingPurpose,
	): SourcePurposeBinding = implementation(source).binding(purpose)

	fun binding(
		source: SourceKind,
		purpose: TrackingPurpose,
	): SourcePurposeBinding = implementation(source).binding(purpose)

	fun binding(key: SourcePurposeKey): SourcePurposeBinding =
		binding(key.source, key.purpose)

	suspend fun availability(request: SourceAvailabilityRequest): SourceCatalogAvailability =
		when (val binding = binding(request.source, request.purpose)) {
			is SourcePurposeBinding.Executable -> SourceCatalogAvailability.Executable(
				binding.providerAvailability.read(request),
			)
			is SourcePurposeBinding.Unsupported -> SourceCatalogAvailability.Unsupported(
				source = binding.source,
				purpose = binding.purpose,
				reason = binding.reason,
			)
		}
}

internal fun TrackingSource.toRuntimeSourceKind(): SourceKind = when (this) {
	TrackingSource.LOCATION -> SourceKind.LOCATION
	TrackingSource.ACTIVITY -> SourceKind.ACTIVITY
	TrackingSource.STEPS -> SourceKind.STEPS
	TrackingSource.PRESSURE -> SourceKind.PRESSURE
	TrackingSource.WIFI -> SourceKind.WIFI
	TrackingSource.CELL -> SourceKind.CELL
}

internal fun SourceKind.toCanonicalTrackingSource(): TrackingSource = when (this) {
	SourceKind.LOCATION -> TrackingSource.LOCATION
	SourceKind.ACTIVITY -> TrackingSource.ACTIVITY
	SourceKind.STEPS -> TrackingSource.STEPS
	SourceKind.PRESSURE -> TrackingSource.PRESSURE
	SourceKind.WIFI -> TrackingSource.WIFI
	SourceKind.CELL -> TrackingSource.CELL
}
