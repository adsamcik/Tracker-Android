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
	 * window collapse to a single entry. Call from inside the write transaction so the
	 * dirty mark is observable to the next flush.
	 *
	 * NOTE: this is in-memory state, not transactional. If the surrounding SQLite
	 * transaction rolls back, the dirty mark stays set, and the next flush re-reads
	 * the (already-correct) value from the pre-aggregated table — wasted work, no
	 * incorrect unlock.
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
	 *
	 * Callers that fail mid-flush after consuming the set should re-mark via
	 * [markDirty] in their catch block so the next flush still observes the changes.
	 */
	fun consumeDirty(): Set<String>
}
