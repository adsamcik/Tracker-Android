package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject

data class WakeupRequest(
	val id: String,
	val source: SourceKind,
	val earliestElapsedRealtimeMs: Long,
	val latestElapsedRealtimeMs: Long,
	val priority: WakeupPriority,
) {
	init {
		require(id.isNotBlank())
		require(earliestElapsedRealtimeMs <= latestElapsedRealtimeMs)
	}
}

enum class WakeupPriority { OPPORTUNISTIC, NORMAL, USER_VISIBLE }

data class PlannedWakeup(
	val executeAtElapsedRealtimeMs: Long,
	val requests: List<WakeupRequest>,
)

class WakeupPlanner @Inject constructor() {
	fun plan(requests: Collection<WakeupRequest>, nowElapsedRealtimeMs: Long): List<PlannedWakeup> {
		val remaining = requests.sortedWith(
			compareBy<WakeupRequest>(WakeupRequest::latestElapsedRealtimeMs)
				.thenByDescending(WakeupRequest::priority),
		).toMutableList()
		val result = mutableListOf<PlannedWakeup>()
		while (remaining.isNotEmpty()) {
			val anchor = remaining.removeAt(0)
			val executeAt = maxOf(nowElapsedRealtimeMs, anchor.earliestElapsedRealtimeMs)
				.coerceAtMost(anchor.latestElapsedRealtimeMs)
			val compatible = remaining.filter { request ->
				executeAt in request.earliestElapsedRealtimeMs..request.latestElapsedRealtimeMs
			}
			remaining.removeAll(compatible.toSet())
			result += PlannedWakeup(
				executeAtElapsedRealtimeMs = executeAt,
				requests = (listOf(anchor) + compatible).sortedBy(WakeupRequest::id),
			)
		}
		return result
	}
}
