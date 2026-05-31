package com.adsamcik.tracker.stats.data.metric

import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker.Consumer
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default in-memory implementation of [MetricDirtyTracker].
 *
 * Thread-safety: backed by one [AtomicReference] per [Consumer] holding that
 * consumer's current dirty set. [markDirty] fans out to every consumer, swapping
 * each reference with `current ∪ {table}` via compare-and-set; concurrent writers
 * retry until the CAS succeeds. [consumeDirty] swaps only the requested consumer's
 * reference with an empty set and returns the prior contents — so writes that race
 * the swap go into the NEXT flush window for that consumer, never lost, and other
 * consumers are unaffected.
 *
 * Cost per markDirty: typically one CAS per consumer (≤100ns × N, where N is the
 * small fixed number of `Consumer` enum entries). Cost per consumeDirty: one CAS.
 * Idle flush short-circuit cost: one volatile read + isEmpty check (~5ns).
 */
@Singleton
class DefaultMetricDirtyTracker @Inject constructor() : MetricDirtyTracker {

	private val states: Map<Consumer, AtomicReference<Set<String>>> =
		Consumer.entries.associateWith { AtomicReference<Set<String>>(emptySet()) }

	override fun markDirty(table: String) {
		for (state in states.values) {
			while (true) {
				val current = state.get()
				if (table in current) break
				val next = current + table
				if (state.compareAndSet(current, next)) break
			}
		}
	}

	override fun markDirty(tables: Set<String>) {
		if (tables.isEmpty()) return
		for (state in states.values) {
			while (true) {
				val current = state.get()
				if (current.containsAll(tables)) break
				val next = current + tables
				if (state.compareAndSet(current, next)) break
			}
		}
	}

	override fun consumeDirty(consumer: Consumer): Set<String> {
		return states.getValue(consumer).getAndSet(emptySet())
	}
}
