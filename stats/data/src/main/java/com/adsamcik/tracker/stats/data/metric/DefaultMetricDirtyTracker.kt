package com.adsamcik.tracker.stats.data.metric

import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker.Consumer
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker.DirtySnapshot
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default in-memory implementation of [MetricDirtyTracker].
 *
 * Thread-safety: backed by one [AtomicReference] per [Consumer] holding table
 * generations. [markDirty] fans out a new generation to every consumer.
 * [acknowledgeDirty] removes only generations covered by the acknowledged snapshot,
 * so writes racing an evaluation remain pending.
 *
 * Cost per markDirty: typically one CAS per consumer (≤100ns × N, where N is the
 * small fixed number of `Consumer` enum entries). Snapshot cost is one volatile read.
 * Idle flush short-circuit cost: one volatile read + isEmpty check (~5ns).
 */
@Singleton
class DefaultMetricDirtyTracker @Inject constructor() : MetricDirtyTracker {

	private val generation = AtomicLong(0L)
	private val states: Map<Consumer, AtomicReference<Map<String, Long>>> =
		Consumer.entries.associateWith { AtomicReference<Map<String, Long>>(emptyMap()) }

	override fun markDirty(table: String) {
		markDirtyWithGeneration(setOf(table))
	}

	override fun markDirty(tables: Set<String>) {
		markDirtyWithGeneration(tables)
	}

	internal fun markDirtyWithGeneration(tables: Set<String>): DirtySnapshot {
		val validTables = tables.filterTo(LinkedHashSet()) { it.isNotEmpty() }
		if (validTables.isEmpty()) return DirtySnapshot(emptyMap())
		val nextGeneration = generation.incrementAndGet()
		val marked = validTables.associateWith { nextGeneration }
		restoreDirty(marked)
		return DirtySnapshot(marked)
	}

	internal fun restoreDirty(generations: Map<String, Long>) {
		if (generations.isEmpty()) return
		generation.updateAndGet { current -> maxOf(current, generations.values.maxOrNull() ?: current) }
		for (state in states.values) {
			while (true) {
				val current = state.get()
				val next = current.toMutableMap()
				for ((table, markedGeneration) in generations) {
					if (table.isNotEmpty() && markedGeneration > (next[table] ?: Long.MIN_VALUE)) {
						next[table] = markedGeneration
					}
				}
				if (next == current) break
				if (state.compareAndSet(current, next)) break
			}
		}
	}

	override suspend fun snapshotDirty(consumer: Consumer): DirtySnapshot {
		return DirtySnapshot(states.getValue(consumer).get())
	}

	override suspend fun acknowledgeDirty(consumer: Consumer, snapshot: DirtySnapshot): Boolean {
		val state = states.getValue(consumer)
		while (true) {
			val current = state.get()
			val remaining = current.filter { (table, currentGeneration) ->
				currentGeneration > (snapshot.generations[table] ?: Long.MIN_VALUE)
			}
			if (remaining == current) return remaining.isEmpty()
			if (state.compareAndSet(current, remaining)) return remaining.isEmpty()
		}
	}
}
