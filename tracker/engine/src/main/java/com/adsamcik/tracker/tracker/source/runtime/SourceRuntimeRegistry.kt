package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceRuntimeRegistry @Inject constructor(
	runtimes: Set<@JvmSuppressWildcards SourceRuntime<out SourcePlan>>,
) {
	private val runtimesBySource = runtimes.associateBy(SourceRuntime<*>::source)

	init {
		require(runtimesBySource.size == runtimes.size) { "Only one physical runtime may own each source" }
	}

	fun runtime(source: SourceKind): SourceRuntime<out SourcePlan>? = runtimesBySource[source]

	fun registeredSources(): Set<SourceKind> = runtimesBySource.keys

	@Suppress("UNCHECKED_CAST")
	suspend fun start(plan: SourcePlan, sink: SourceEventSink): SourceStartResult {
		val runtime = requireNotNull(runtimesBySource[plan.source]) {
			"No source runtime is registered for ${plan.source}"
		} as SourceRuntime<SourcePlan>
		return runtime.start(plan, sink)
	}

	@Suppress("UNCHECKED_CAST")
	suspend fun reconfigure(plan: SourcePlan): SourceApplyResult {
		val runtime = requireNotNull(runtimesBySource[plan.source]) {
			"No source runtime is registered for ${plan.source}"
		} as SourceRuntime<SourcePlan>
		return runtime.reconfigure(plan)
	}

	suspend fun quiesce(source: SourceKind, cutoff: SessionCutoff): SourceStopAck =
		requireNotNull(runtimesBySource[source]) { "No source runtime is registered for $source" }
			.quiesce(cutoff)

	suspend fun close(source: SourceKind) {
		requireNotNull(runtimesBySource[source]) { "No source runtime is registered for $source" }.close()
	}
}
