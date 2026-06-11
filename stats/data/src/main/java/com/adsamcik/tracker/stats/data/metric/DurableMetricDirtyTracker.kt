package com.adsamcik.tracker.stats.data.metric

import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker.Consumer
import com.adsamcik.tracker.stats.api.metric.PersistentDirtyState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
 * every [markDirty]. On app startup the persisted state is asynchronously
 * loaded (on [ioDispatcher]) and back-filled into the in-memory delegate so
 * both LIVE and PERSISTENCE consumer views see the rehydrated bits.
 *
 * # Performance contract
 *
 *  - In-memory CAS (fast path) is untouched — every call still goes through
 *    the wrapped delegate first.
 *  - Disk writes are dispatched on [persistenceScope] with [ioDispatcher]
 *    so they NEVER block the markDirty caller and never starve Default
 *    dispatcher threads with file I/O.
 *  - LIVE consumer marks are intentionally NOT persisted — they exist only
 *    for the current session and there's no recovery story.
 *
 * # Crash safety — important caveats
 *
 *  **[markDirty] is best-effort async**: the in-memory bit is set immediately,
 *  but the disk write is enqueued via `persistenceScope.launch`. If the OS
 *  hard-kills the process in the microsecond window between the in-memory
 *  set and the disk commit, that mark is lost. This is a deliberate trade-off:
 *  blocking the hot path for fsync is too expensive. The recovery path is
 *  `AchievementWorker`'s source-watermark fallback, which re-evaluates from
 *  the database if dirty bits are missing.
 *
 *  In practice, [DurableMetricDirtyTracker] converts "dirty bits lost forever
 *  on process death" into "dirty bits lost for at most one worker window per
 *  crash". It **reduces** but does **not eliminate** dirty-loss risk.
 *
 *  - consumeDirty + disk remove race: in-memory drained; disk still has bits
 *    that get loaded on next startup, causing one redundant evaluation. Safe.
 *
 * # Rehydration coordination
 *
 *  The async init completes [rehydrationComplete] once the persisted state has
 *  been loaded. [consumeDirty] for the [Consumer.PERSISTENCE] consumer awaits
 *  this deferred (via [runBlocking]) so a worker that runs before rehydration
 *  finishes will block briefly on the worker thread (never the main thread)
 *  rather than seeing an empty set and short-circuiting. After the first
 *  await the deferred returns instantly.
 */
class DurableMetricDirtyTracker(
	private val delegate: MetricDirtyTracker,
	private val persistentState: PersistentDirtyState,
	private val persistenceScope: CoroutineScope,
	// Required (not defaulted): direct `Dispatchers.IO` defaults are banned by
	// ArchitecturalFitnessTest. Hilt injects `@IoDispatcher CoroutineDispatcher`
	// via StatsDataModule; tests pass a `StandardTestDispatcher`.
	private val ioDispatcher: CoroutineDispatcher,
) : MetricDirtyTracker {

	/**
	 * Signals that the async rehydration from [persistentState] has completed.
	 * [consumeDirty] for [Consumer.PERSISTENCE] awaits this so workers never
	 * see a stale-empty set during the first few milliseconds after init.
	 */
	internal val rehydrationComplete = CompletableDeferred<Unit>()

	init {
		// Asynchronously rehydrate PERSISTENCE marks from disk. Launched on
		// ioDispatcher so the Hilt singleton init never blocks the calling
		// thread (which may be main). Workers that call consumeDirty before
		// this completes will block on rehydrationComplete in consumeDirty,
		// which is safe because workers always run on background threads.
		persistenceScope.launch(ioDispatcher) {
			try {
				val persisted = persistentState.load()
				if (persisted.isNotEmpty()) {
					delegate.markDirty(persisted)
				}
			} finally {
				rehydrationComplete.complete(Unit)
			}
		}
	}

	override fun markDirty(table: String) {
		delegate.markDirty(table)
		persistenceScope.launch(ioDispatcher) { persistentState.add(setOf(table)) }
	}

	override fun markDirty(tables: Set<String>) {
		if (tables.isEmpty()) return
		delegate.markDirty(tables)
		persistenceScope.launch(ioDispatcher) { persistentState.add(tables) }
	}

	override fun consumeDirty(consumer: Consumer): Set<String> {
		if (consumer == Consumer.PERSISTENCE) {
			// Block until rehydration finishes so the worker never sees an
			// empty set because the init coroutine hasn't loaded yet. This
			// runBlocking is acceptable because:
			//   - PERSISTENCE consumers are WorkManager workers on background threads.
			//   - After the first call the deferred is already complete (instant).
			runBlocking { rehydrationComplete.await() }
		}
		val consumed = delegate.consumeDirty(consumer)
		if (consumer == Consumer.PERSISTENCE && consumed.isNotEmpty()) {
			persistenceScope.launch(ioDispatcher) { persistentState.remove(consumed) }
		}
		return consumed
	}
}
