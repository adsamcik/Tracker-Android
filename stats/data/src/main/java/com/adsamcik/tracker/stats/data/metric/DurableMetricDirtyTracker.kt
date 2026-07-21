package com.adsamcik.tracker.stats.data.metric

import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker.Consumer
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker.DirtySnapshot
import com.adsamcik.tracker.stats.api.metric.PersistentDirtyState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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
 *  3. App restarts. In-memory bit is gone. `AchievementWorker.snapshotDirty`
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
 *  blocking the hot path for fsync is too expensive. This decorator eliminates
 *  destructive pre-commit removal, but cannot make an async mark durable before
 *  its disk write actually completes.
 *
 *  - Persistence operations are processed in FIFO order so an acknowledgement
 *    cannot overtake an earlier async mark.
 *
 * # Rehydration coordination
 *
 *  The async init completes [rehydrationComplete] once the persisted state has
 *  been loaded. [snapshotDirty] for the [Consumer.PERSISTENCE] consumer awaits
 *  this deferred so a worker that runs before rehydration finishes suspends
 *  rather than seeing an empty set and short-circuiting. After the first await
 *  the deferred returns instantly.
 */
class DurableMetricDirtyTracker(
	private val delegate: DefaultMetricDirtyTracker,
	private val persistentState: PersistentDirtyState,
	private val persistenceScope: CoroutineScope,
	// Required (not defaulted): direct `Dispatchers.IO` defaults are banned by
	// ArchitecturalFitnessTest. Hilt injects `@IoDispatcher CoroutineDispatcher`
	// via StatsDataModule; tests pass a `StandardTestDispatcher`.
	private val ioDispatcher: CoroutineDispatcher,
) : MetricDirtyTracker {
	private sealed interface PersistenceOperation {
		data class Add(val generations: Map<String, Long>) : PersistenceOperation
		data class Acknowledge(
			val generations: Map<String, Long>,
			val completion: CompletableDeferred<Unit>,
		) : PersistenceOperation
	}

	private val persistenceOperations = Channel<PersistenceOperation>(Channel.UNLIMITED)

	/**
	 * Signals that the async rehydration from [persistentState] has completed.
	 * [snapshotDirty] for [Consumer.PERSISTENCE] awaits this so workers never
	 * see a stale-empty set during the first few milliseconds after init.
	 */
	internal val rehydrationComplete = CompletableDeferred<Unit>()

	init {
		persistenceScope.launch(ioDispatcher) {
			try {
				val persisted = persistentState.load()
				if (persisted.isNotEmpty()) {
					delegate.restoreDirty(persisted)
				}
			} finally {
				rehydrationComplete.complete(Unit)
			}
			for (operation in persistenceOperations) {
				when (operation) {
					is PersistenceOperation.Add -> runCatching {
						persistentState.add(operation.generations)
					}
					is PersistenceOperation.Acknowledge -> runCatching {
						persistentState.acknowledge(operation.generations)
					}.fold(
						onSuccess = { operation.completion.complete(Unit) },
						onFailure = operation.completion::completeExceptionally,
					)
				}
			}
		}
	}

	override fun markDirty(table: String) {
		markDirty(setOf(table))
	}

	override fun markDirty(tables: Set<String>) {
		if (tables.isEmpty()) return
		val marked = delegate.markDirtyWithGeneration(tables)
		persistenceOperations.trySend(PersistenceOperation.Add(marked.generations))
	}

	override suspend fun snapshotDirty(consumer: Consumer): DirtySnapshot {
		if (consumer == Consumer.PERSISTENCE) {
			rehydrationComplete.await()
		}
		return delegate.snapshotDirty(consumer)
	}

	override suspend fun acknowledgeDirty(consumer: Consumer, snapshot: DirtySnapshot): Boolean {
		if (consumer == Consumer.PERSISTENCE && !snapshot.isEmpty) {
			val completion = CompletableDeferred<Unit>()
			persistenceOperations.send(
				PersistenceOperation.Acknowledge(snapshot.generations, completion),
			)
			completion.await()
		}
		return delegate.acknowledgeDirty(consumer, snapshot)
	}
}
