package com.adsamcik.tracker.stats.api.metric

/**
 * Tracks which pre-aggregated tables have been written to since the last acknowledgement.
 *
 * Battery-critical primitive of the unified rule engine. The achievement signal
 * processor calls [snapshotDirty] at the start of every flush; if the result is
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
 * fan out to ALL consumers' sets; [acknowledgeDirty] only advances the requested
 * consumer's state. This prevents the round-6 R1+R2 starvation bug where the
 * in-session `AchievementProcessor.runEvaluation` and the periodic
 * `AchievementWorker.doWork` raced on a single shared set — whoever consumed first
 * silently wiped the other's view, dropping persisted progress on process death.
 *
 * Implementations MUST be thread-safe. The default in-memory implementation lives in
 * `:stats-data` because Kotlin/Native doesn't ship `synchronized` in commonMain.
 */
interface MetricDirtyTracker {
	/**
	 * A generation-aware view of dirty tables.
	 *
	 * Generations let [acknowledgeDirty] remove only the writes represented by this
	 * snapshot. If the same table is marked again while evaluation is running, its
	 * newer generation remains dirty after the older snapshot is acknowledged.
	 */
	data class DirtySnapshot(
		val generations: Map<String, Long>,
	) {
		val tables: Set<String> get() = generations.keys
		val isEmpty: Boolean get() = generations.isEmpty()
	}

	/**
	 * Identifies an independent dirty-set consumer. Each consumer acknowledges
	 * independently; writes are fanned out to all consumers.
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
	 * Return a stable generation-aware snapshot without clearing [consumer]'s dirty
	 * state. Repeated calls return pending work until [acknowledgeDirty] confirms
	 * successful application.
	 *
	 * Returns an empty snapshot if nothing is pending for [consumer].
	 */
	suspend fun snapshotDirty(consumer: Consumer): DirtySnapshot

	/**
	 * Confirm that [snapshot] was applied successfully for [consumer].
	 *
	 * Only generations at or below the acknowledged generation are cleared. A
	 * concurrent [markDirty] for the same table therefore survives for the next pass.
	 * Callers MUST NOT acknowledge failed or cancelled work.
	 *
	 * Returns `true` when no dirty generation is pending for [consumer] immediately
	 * after the acknowledgement.
	 */
	suspend fun acknowledgeDirty(consumer: Consumer, snapshot: DirtySnapshot): Boolean
}
