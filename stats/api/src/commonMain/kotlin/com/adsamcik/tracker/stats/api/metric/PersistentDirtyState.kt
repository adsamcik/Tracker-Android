package com.adsamcik.tracker.stats.api.metric

/**
 * Durable backing store for the PERSISTENCE consumer's view of
 * [MetricDirtyTracker]. Survives process death so dirty bits marked in one
 * process are visible to the next, closing the round-7 R2 finding that
 * `AchievementWorker` could silently skip a window after the OS killed
 * the app between [MetricDirtyTracker.markDirty] and the next scheduled
 * worker run.
 *
 * # Contract
 *
 * - [load] is called once at app startup. It returns whatever generations the
 *   previous process left behind. Implementations MUST handle a missing or
 *   corrupt backing store by returning an empty set — never throw.
 * - [add] persists newly-marked table generations. Implementations SHOULD be cheap
 *   enough to run on every [MetricDirtyTracker.markDirty] without
 *   measurable hot-path cost; the default implementation appends to a
 *   tiny newline-delimited file.
 * - [acknowledge] is called only after the PERSISTENCE consumer successfully
 *   applies a snapshot. Newer generations for the same table must survive.
 *
 * # Crash-safety limitations
 *
 * [add] is asynchronous because blocking the write hot path for fsync is too
 * expensive. A hard kill before that queued write completes can therefore lose
 * the newest mark. [acknowledge], however, is awaited by the evaluator and must
 * not remove a newer generation for the same table.
 */
interface PersistentDirtyState {
	/**
	 * Read the previously-persisted dirty set. Returns an empty set if the
	 * backing store is empty / missing / unreadable.
	 */
	suspend fun load(): Map<String, Long>

	/**
	 * Persist that [tables] have been added to the PERSISTENCE consumer's
	 * view. Idempotent — re-persisting a table already on disk is a no-op.
	 */
	suspend fun add(generations: Map<String, Long>)

	/**
	 * Acknowledge successfully applied [generations]. An entry is removed only
	 * when the stored generation is not newer than the acknowledged generation.
	 */
	suspend fun acknowledge(generations: Map<String, Long>)
}
