package com.adsamcik.tracker.app.startup

import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

internal data class ApplicationStartupSnapshot(
	val generation: Long,
	val revision: Long,
	val result: TrackingStartupResult?,
)

internal class ApplicationStartupStateStore(
	initialGeneration: Long = -1L,
) {
	private val mutableSnapshots = MutableStateFlow(
		ApplicationStartupSnapshot(
			generation = initialGeneration,
			revision = 0L,
			result = null,
		),
	)
	val snapshots: StateFlow<ApplicationStartupSnapshot> = mutableSnapshots.asStateFlow()

	@Synchronized
	fun beginGeneration(generation: Long) {
		val current = mutableSnapshots.value
		if (generation <= current.generation) return
		mutableSnapshots.value = ApplicationStartupSnapshot(
			generation = generation,
			revision = current.revision + 1L,
			result = null,
		)
	}

	@Synchronized
	fun publish(
		generation: Long,
		result: TrackingStartupResult,
	): Boolean {
		val current = mutableSnapshots.value
		if (generation != current.generation) return false
		mutableSnapshots.value = current.copy(
			revision = current.revision + 1L,
			result = result,
		)
		return true
	}

	suspend fun awaitTerminal(): TrackingStartupResult = snapshots
		.mapNotNull { snapshot ->
			snapshot.result?.takeIf { result ->
				result is TrackingStartupResult.Ready || result is TrackingStartupResult.Blocked
			}
		}
		.first()
}
