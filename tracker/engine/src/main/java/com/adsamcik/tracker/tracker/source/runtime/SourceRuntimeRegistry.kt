package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.catalog.SourceAvailabilityRequest
import com.adsamcik.tracker.tracker.source.catalog.SourceCatalogAvailability
import com.adsamcik.tracker.tracker.source.catalog.SourceImplementationCatalog
import com.adsamcik.tracker.tracker.source.catalog.SourceProviderAvailability
import com.adsamcik.tracker.tracker.source.catalog.UnsupportedSourcePurposeReason
import com.adsamcik.tracker.tracker.source.catalog.toCanonicalTrackingSource
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class SourceRuntimeRegistry private constructor(
	private val directory: SourceRuntimeDirectory,
) {
	@Inject
	internal constructor(
		runtimeProviders: Map<
			SourceKind,
			@JvmSuppressWildcards Provider<ClaimedSourceRuntime<out SourcePlan>>,
		>,
		catalog: SourceImplementationCatalog,
	) : this(ProviderSourceRuntimeDirectory(runtimeProviders, catalog, requireAllSources = true))

	/** Compatibility constructor for isolated runtime tests; production injection is catalog-backed. */
	internal constructor(
		runtimes: Set<@JvmSuppressWildcards ClaimedSourceRuntime<out SourcePlan>>,
	) : this(SetSourceRuntimeDirectory(runtimes))

	internal constructor(
		runtimeProviders: Map<
			SourceKind,
			@JvmSuppressWildcards Provider<ClaimedSourceRuntime<out SourcePlan>>,
		>,
		availabilityCatalog: SourceImplementationCatalog,
		requireAllSources: Boolean,
	) : this(
		ProviderSourceRuntimeDirectory(runtimeProviders, availabilityCatalog, requireAllSources),
	)

	fun runtime(source: SourceKind): ClaimedSourceRuntime<out SourcePlan>? = directory.runtime(source)

	fun registeredSources(): Set<SourceKind> = directory.registeredSources()

	suspend fun availability(request: SourceAvailabilityRequest): SourceCatalogAvailability =
		directory.availability(request)

	@Suppress("UNCHECKED_CAST")
	suspend fun start(
		claim: SourceRuntimeClaim,
		plan: SourcePlan,
		sink: SourceEventSink,
	): SourceStartResult {
		require(claim.source == plan.source)
		val runtime = requireNotNull(directory.runtime(plan.source)) {
			"No source runtime is registered for ${plan.source}"
		} as ClaimedSourceRuntime<SourcePlan>
		return runtime.start(claim, plan, sink)
	}

	@Suppress("UNCHECKED_CAST")
	suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: SourcePlan,
		sink: SourceEventSink,
	): SourceApplyResult {
		require(claim.source == plan.source)
		val runtime = requireNotNull(directory.runtime(plan.source)) {
			"No source runtime is registered for ${plan.source}"
		} as ClaimedSourceRuntime<SourcePlan>
		return runtime.reconfigure(claim, plan, sink)
	}

	suspend fun quiesce(source: SourceKind, cutoff: SessionCutoff): SourceStopAck =
		requireNotNull(directory.runtime(source)) { "No source runtime is registered for $source" }
			.quiesce(cutoff)

	suspend fun close(source: SourceKind) {
		requireNotNull(directory.runtime(source)) { "No source runtime is registered for $source" }.close()
	}

	suspend fun shutdownIfOwned(
		claim: SourceRuntimeClaim,
		cutoff: SessionCutoff,
	): OwnedSourceShutdown = requireNotNull(directory.runtime(claim.source)) {
		"No source runtime is registered for ${claim.source}"
	}.shutdownIfOwned(claim, cutoff)
}

private interface SourceRuntimeDirectory {
	fun runtime(source: SourceKind): ClaimedSourceRuntime<out SourcePlan>?
	fun registeredSources(): Set<SourceKind>
	suspend fun availability(request: SourceAvailabilityRequest): SourceCatalogAvailability
}

private class ProviderSourceRuntimeDirectory(
	private val runtimeProviders: Map<
		SourceKind,
		Provider<ClaimedSourceRuntime<out SourcePlan>>,
	>,
	private val availabilityCatalog: SourceImplementationCatalog,
	requireAllSources: Boolean,
) : SourceRuntimeDirectory {
	init {
		if (requireAllSources) {
			require(runtimeProviders.keys == SourceKind.entries.toSet()) {
				"Runtime registry requires exactly one lazy owner for every source"
			}
		}
	}

	override fun runtime(source: SourceKind): ClaimedSourceRuntime<out SourcePlan>? =
		runtimeProviders[source]?.get()?.also { runtime ->
			require(runtime.source == source) {
				"Runtime provider for $source returned ${runtime.source}"
			}
		}

	override fun registeredSources(): Set<SourceKind> = runtimeProviders.keys

	override suspend fun availability(request: SourceAvailabilityRequest): SourceCatalogAvailability =
		availabilityCatalog.availability(request)
}

private class SetSourceRuntimeDirectory(
	runtimes: Set<ClaimedSourceRuntime<out SourcePlan>>,
) : SourceRuntimeDirectory {
	private val runtimesBySource = runtimes.associateBy(SourceRuntime<*>::source)

	init {
		require(runtimesBySource.size == runtimes.size) { "Only one physical runtime may own each source" }
	}

	override fun runtime(source: SourceKind): ClaimedSourceRuntime<out SourcePlan>? =
		runtimesBySource[source]

	override fun registeredSources(): Set<SourceKind> = runtimesBySource.keys

	override suspend fun availability(request: SourceAvailabilityRequest): SourceCatalogAvailability {
		val canonicalSource = request.source.toCanonicalTrackingSource()
		if (!canonicalSource.supports(request.purpose)) {
			return SourceCatalogAvailability.Unsupported(
				canonicalSource,
				request.purpose,
				UnsupportedSourcePurposeReason.NOT_CANONICALLY_SUPPORTED,
			)
		}
		return SourceCatalogAvailability.Executable(SourceProviderAvailability.Available())
	}
}
