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
 * - [load] is called once at app startup. It returns whatever marks the
 *   previous process left behind. Implementations MUST handle a missing or
 *   corrupt backing store by returning an empty set — never throw.
 * - [add] persists newly-marked tables. Implementations SHOULD be cheap
 *   enough to run on every [MetricDirtyTracker.markDirty] without
 *   measurable hot-path cost; the default implementation appends to a
 *   tiny newline-delimited file.
 * - [remove] is called when the PERSISTENCE consumer drains its in-memory
 *   set. The marks removed are no longer needed for crash recovery.
 *
 * # Crash-safety limitations
 *
 * Implementations are NOT required to be transactional w.r.t. crashes.
 * [add] and [remove] are called asynchronously after the in-memory state
 * has already changed. If the OS hard-kills the process between the
 * in-memory mutation and the enqueued disk write, the disk mutation is
 * lost. This is a deliberate design trade-off — blocking the hot path for
 * fsync is too expensive for a best-effort recovery mechanism.
 *
 * The safety net is the source-watermark fallback in `AchievementWorker`:
 * if a mark is lost, the worker re-evaluates from the database on the
 * next run. The durable tracker converts "lost forever" into "lost for at
 * most one worker window per crash".
 */
interface PersistentDirtyState {
	/**
	 * Read the previously-persisted dirty set. Returns an empty set if the
	 * backing store is empty / missing / unreadable.
	 */
	suspend fun load(): Set<String>

	/**
	 * Persist that [tables] have been added to the PERSISTENCE consumer's
	 * view. Idempotent — re-persisting a table already on disk is a no-op.
	 */
	suspend fun add(tables: Set<String>)

	/**
	 * Mark [tables] as no longer needed for crash recovery (the consumer
	 * has drained them and is processing or has processed). Subsequent
	 * [load] calls should not return them unless they're re-marked via
	 * [add].
	 */
	suspend fun remove(tables: Set<String>)
}
