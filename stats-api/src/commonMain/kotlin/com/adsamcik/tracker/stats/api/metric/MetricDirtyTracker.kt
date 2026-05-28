package com.adsamcik.tracker.stats.api.metric

/**
 * Tracks which pre-aggregated tables have been written to since the last consume.
 *
 * Battery-critical primitive of the unified rule engine (p6-1). The rule signal processor
 * calls [consumeDirty] at the start of every flush; if the result is empty, the processor
 * returns immediately without running any aggregate queries — which is the common case
 * on idle/AMBIENT tier. When non-empty, only rules whose metric depends on a dirty table
 * (resolved via [MetricKeys.sourceTables]) are re-evaluated.
 *
 * Writers ([DailySummaryAggregator], [ExplorationDomainEventConsumer],
 * [SessionSegmentWriter], etc.) call [markDirty] from within their write transactions.
 * The cost is one set insert per write batch — negligible compared to the work avoided
 * on the read side.
 *
 * Implementations MUST be thread-safe. The default in-memory implementation lives in
 * `:stats-data` because Kotlin/Native doesn't ship `synchronized` in commonMain.
 */
interface MetricDirtyTracker {
	/**
	 * Mark [table] as having been written to. Idempotent — multiple calls in the same
	 * window collapse to a single entry. Call from inside the write transaction so
	 * a rollback also rolls back the dirty flag observably.
	 */
	fun markDirty(table: String)

	/**
	 * Mark multiple tables in one shot. Equivalent to calling [markDirty] for each
	 * element but cheaper. No-op if the set is empty.
	 */
	fun markDirty(tables: Set<String>)

	/**
	 * Atomically swap the dirty set with an empty one and return what was swapped out.
	 * Subsequent [markDirty] calls accumulate into the NEW empty set, so concurrent
	 * writes during a flush are NEVER lost — they become part of the next flush window.
	 *
	 * Returns the empty set if nothing was marked since the last call.
	 */
	fun consumeDirty(): Set<String>

	/**
	 * Mark ALL known tables as dirty. Use sparingly — typically only on engine startup
	 * to force a one-shot reconciliation, or when an unknown writer is suspected.
	 */
	fun markAllDirty()
}
