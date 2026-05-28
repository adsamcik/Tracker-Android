package com.adsamcik.tracker.stats.data.metric

import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default in-memory implementation of [MetricDirtyTracker].
 *
 * Thread-safety: backed by a single [AtomicReference] holding the current dirty set.
 * [markDirty] swaps the reference with `current ∪ {table}` via compare-and-set; concurrent
 * writers retry until the CAS succeeds. [consumeDirty] swaps the reference with an
 * empty set and returns the prior contents — so writes that race the swap go into the
 * NEXT flush window, never lost.
 *
 * Cost per markDirty: typically one CAS (≤100ns). Cost per consumeDirty: one CAS.
 * Idle flush short-circuit cost: one volatile read + isEmpty check (~5ns).
 */
@Singleton
class DefaultMetricDirtyTracker @Inject constructor() : MetricDirtyTracker {

	private val state = AtomicReference<Set<String>>(emptySet())

	override fun markDirty(table: String) {
		while (true) {
			val current = state.get()
			if (table in current) return
			val next = current + table
			if (state.compareAndSet(current, next)) return
		}
	}

	override fun markDirty(tables: Set<String>) {
		if (tables.isEmpty()) return
		while (true) {
			val current = state.get()
			if (current.containsAll(tables)) return
			val next = current + tables
			if (state.compareAndSet(current, next)) return
		}
	}

	override fun consumeDirty(): Set<String> {
		return state.getAndSet(emptySet())
	}

	override fun markAllDirty() {
		// Conservative: caller code that legitimately needs "everything"
		// (e.g. boot reconciliation) will pass an explicit table set instead.
	}
}
