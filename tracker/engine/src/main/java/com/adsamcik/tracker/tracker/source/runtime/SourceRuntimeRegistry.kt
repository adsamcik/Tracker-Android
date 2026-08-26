package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceRuntimeRegistry @Inject constructor(
	runtimes: Set<@JvmSuppressWildcards ClaimedSourceRuntime<out SourcePlan>>,
) {
	private val runtimesBySource = runtimes.associateBy(SourceRuntime<*>::source)

	init {
		require(runtimesBySource.size == runtimes.size) { "Only one physical runtime may own each source" }
	}

	fun runtime(source: SourceKind): ClaimedSourceRuntime<out SourcePlan>? = runtimesBySource[source]

	fun registeredSources(): Set<SourceKind> = runtimesBySource.keys

	@Suppress("UNCHECKED_CAST")
	suspend fun start(
		claim: SourceRuntimeClaim,
		plan: SourcePlan,
		sink: SourceEventSink,
	): SourceStartResult {
		require(claim.source == plan.source)
		val runtime = requireNotNull(runtimesBySource[plan.source]) {
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
		val runtime = requireNotNull(runtimesBySource[plan.source]) {
			"No source runtime is registered for ${plan.source}"
		} as ClaimedSourceRuntime<SourcePlan>
		return runtime.reconfigure(claim, plan, sink)
	}

	suspend fun quiesce(source: SourceKind, cutoff: SessionCutoff): SourceStopAck =
		requireNotNull(runtimesBySource[source]) { "No source runtime is registered for $source" }
			.quiesce(cutoff)

	suspend fun close(source: SourceKind) {
		requireNotNull(runtimesBySource[source]) { "No source runtime is registered for $source" }.close()
	}

	suspend fun shutdownIfOwned(
		claim: SourceRuntimeClaim,
		cutoff: SessionCutoff,
	): OwnedSourceShutdown = requireNotNull(runtimesBySource[claim.source]) {
		"No source runtime is registered for ${claim.source}"
	}.shutdownIfOwned(claim, cutoff)
}
