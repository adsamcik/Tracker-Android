package com.adsamcik.tracker.stats.api.metric

/**
 * Tracks which pre-aggregated tables have been written to since the last consume.
 *
 * Battery-critical primitive of the unified rule engine. The achievement signal
 * processor calls [consumeDirty] at the start of every flush; if the result is
 * empty, the processor returns immediately without running any aggregate queries
 * — which is the common case on idle/AMBIENT tier. When non-empty, dirty-aware
 * rule registries (see `com.adsamcik.tracker.stats.api.rule.RuleRegistry.instancesAffectedByTables`)
 * resolve metric → tables via [MetricKeys.sourceTables] and only the affected
 * rules are re-evaluated.
 *
 * Writers ([DailySummaryAggregator], [ExplorationDomainEventConsumer],
 * [SessionSegmentWriter], etc.) call [markDirty] after their write commits. The
 * cost is one atomic set update per consumer per write batch — negligible compared
 * to the work avoided on the read side.
 *
 * # Multiple consumers — starvation safety
 *
 * The tracker maintains a SEPARATE dirty set per [Consumer]. Writes via [markDirty]
 * fan out to ALL consumers' sets; [consumeDirty] only drains the requested
 * consumer's set. This prevents the round-6 R1+R2 starvation bug where the
 * in-session `AchievementProcessor.runEvaluation` and the periodic
 * `AchievementWorker.doWork` raced on a single shared set — whoever consumed first
 * silently wiped the other's view, dropping persisted progress on process death.
 *
 * Implementations MUST be thread-safe. The default in-memory implementation lives in
 * `:stats-data` because Kotlin/Native doesn't ship `synchronized` in commonMain.
 */
interface MetricDirtyTracker {
	/**
	 * Identifies an independent dirty-set consumer. Each consumer's set is drained
	 * independently by [consumeDirty]; writes are fanned out to all consumers.
	 *
	 * - [LIVE]: in-session, foreground, low-latency evaluator (e.g. the 60s
	 *   `AchievementProcessor.onFlush` from the signal pipeline). Wants to know
	 *   what changed during the active session window.
	 * - [PERSISTENCE]: background, durable, all-history evaluator (e.g. the
	 *   periodic `AchievementWorker` scheduled by WorkManager). Wants to know what
	 *   changed since its last successful persist.
	 */
	enum class Consumer { LIVE, PERSISTENCE }

	/**
	 * Mark [table] as having been written to. Idempotent — multiple calls in the same
	 * window collapse to a single entry. Call after the surrounding write commits;
	 * the mark is in-memory state, NOT transactional, so calling it before commit
	 * could leave the dirty bit set even if the write rolls back. Mark-after-commit
	 * is the safe ordering; the wasted re-evaluation on transient failure is cheap.
	 *
	 * The mark is added to EVERY consumer's set in [Consumer.entries] — writers do
	 * not need to know which consumers exist.
	 */
	fun markDirty(table: String)

	/**
	 * Mark multiple tables in one shot. Equivalent to calling [markDirty] for each
	 * element but cheaper. No-op if the set is empty. Fans out to every consumer.
	 */
	fun markDirty(tables: Set<String>)

	/**
	 * Atomically swap [consumer]'s dirty set with an empty one and return what was
	 * swapped out. Subsequent [markDirty] calls accumulate into the NEW empty set
	 * for [consumer] (other consumers' sets are not touched), so concurrent writes
	 * during a flush are NEVER lost — they become part of the next flush window.
	 *
	 * Returns the empty set if nothing was marked for [consumer] since its last call.
	 *
	 * Callers that fail mid-flush after consuming the set should re-mark via
	 * [markDirty] in their catch block so the next flush still observes the changes.
	 * (Re-marking with [markDirty] re-fans-out to every consumer; if you only want
	 * the failed consumer to retry, you accept some redundant work on others —
	 * usually acceptable, since the other consumer's flush will short-circuit on
	 * unchanged values anyway.)
	 */
	fun consumeDirty(consumer: Consumer): Set<String>
}
