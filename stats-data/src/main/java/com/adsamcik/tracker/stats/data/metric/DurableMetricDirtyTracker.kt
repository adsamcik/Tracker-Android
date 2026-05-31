package com.adsamcik.tracker.stats.data.metric

import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker.Consumer
import com.adsamcik.tracker.stats.api.metric.PersistentDirtyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Decorator over a [MetricDirtyTracker] that mirrors the PERSISTENCE consumer's
 * marks to durable storage via [PersistentDirtyState].
 *
 * # Why
 *
 * The in-memory [DefaultMetricDirtyTracker] loses dirty bits when the OS kills
 * the app process. Scenario (R2 round-7 finding):
 *
 *  1. `AggregatorProcessor` commits a new daily_summary row and calls
 *     `markDirty("daily_summary")` — in-memory bit set.
 *  2. Process is killed before `AchievementWorker` runs.
 *  3. App restarts. In-memory bit is gone. `AchievementWorker.consumeDirty`
 *     returns empty. The worker short-circuits and never re-evaluates, so
 *     achievement_progress stays stale.
 *
 * This decorator closes that gap by also writing PERSISTENCE marks to disk on
 * every [markDirty]. On app startup [load] is called once (typically from
 * `Application.onCreate`) to backfill the in-memory PERSISTENCE consumer view
 * with anything left over from the previous process.
 *
 * # Performance contract
 *
 *  - In-memory CAS (fast path) is untouched — every call still goes through
 *    the wrapped delegate first.
 *  - Disk writes are dispatched on [persistenceScope] (typically the app
 *    scope on IO dispatcher) so they NEVER block the markDirty caller.
 *  - LIVE consumer marks are intentionally NOT persisted — they exist only
 *    for the current session and there's no recovery story.
 *
 * # Crash safety
 *
 *  - markDirty + disk write race: in-memory bit set; disk write enqueued but
 *    may not have run when process dies. The next markDirty on the same
 *    table re-enqueues. Worst case: a single SourceTable's bit is lost for
 *    one worker window, AchievementWorker's source-watermark fallback (its
 *    own follow-up todo) catches it on the next run.
 *  - consumeDirty + disk remove race: in-memory drained; disk still has bits
 *    that get loaded on next startup, causing one redundant evaluation. Safe.
 */
class DurableMetricDirtyTracker(
	private val delegate: MetricDirtyTracker,
	private val persistentState: PersistentDirtyState,
	private val persistenceScope: CoroutineScope,
) : MetricDirtyTracker {

	init {
		// Synchronously rehydrate PERSISTENCE marks from disk so any worker
		// that calls consumeDirty on the singleton's first use sees what the
		// previous process left behind. runBlocking is safe here because:
		//   - This is singleton init (off the main thread under Hilt).
		//   - load() is a small disk read (one file, < 1 KB typically).
		//   - The cost is bounded and one-time per process.
		val persisted = runBlocking { persistentState.load() }
		if (persisted.isNotEmpty()) {
			// Mark into the delegate so BOTH LIVE and PERSISTENCE consumer
			// views see the rehydrated state (parity with what the original
			// markDirty calls did before the process died). The LIVE
			// consumer's first flush will see the bits, evaluate live state,
			// and proceed normally; the PERSISTENCE consumer's first
			// consumeDirty will return them so the worker re-evaluates.
			delegate.markDirty(persisted)
		}
	}

	override fun markDirty(table: String) {
		delegate.markDirty(table)
		// Persist only the PERSISTENCE-bound subset. The delegate fans out to
		// all consumers in-memory, but on disk we only care about marks the
		// worker needs to recover after a crash.
		persistenceScope.launch { persistentState.add(setOf(table)) }
	}

	override fun markDirty(tables: Set<String>) {
		if (tables.isEmpty()) return
		delegate.markDirty(tables)
		persistenceScope.launch { persistentState.add(tables) }
	}

	override fun consumeDirty(consumer: Consumer): Set<String> {
		val consumed = delegate.consumeDirty(consumer)
		if (consumer == Consumer.PERSISTENCE && consumed.isNotEmpty()) {
			// PERSISTENCE drained — remove these from durable storage so a
			// subsequent crash doesn't re-process them. The LIVE consumer's
			// drains do NOT touch disk; their bits remain durable for the
			// worker until it consumes them itself.
			persistenceScope.launch { persistentState.remove(consumed) }
		}
		return consumed
	}
}
