package com.adsamcik.tracker.game.challenge.engine

import androidx.room.withTransaction
import com.adsamcik.tracker.game.challenge.catalog.ChallengeCatalog
import com.adsamcik.tracker.game.challenge.data.ChallengeInstanceNew
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.game.challenge.progression.ProgressionRepository
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import com.adsamcik.tracker.stats.api.repository.WindowedMetricsProvider
import com.adsamcik.tracker.stats.api.rule.Rule
import com.adsamcik.tracker.stats.api.rule.RuleEvaluationResult
import com.adsamcik.tracker.stats.api.rule.RuleEvaluator
import com.adsamcik.tracker.stats.api.rule.RuleInstance
import com.adsamcik.tracker.stats.api.rule.RuleKind
import com.adsamcik.tracker.stats.api.rule.RuleTarget
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Declarative challenge evaluator.
 *
 * Replaces the per-type
 * [com.adsamcik.tracker.game.challenge.processor.ChallengeProcessor]
 * imperative loop with: lookup
 * [com.adsamcik.tracker.game.challenge.catalog.ChallengeDefinition] -> call
 * [WindowedMetricsProvider] -> compare to target via [RuleEvaluator] -> emit updated entity.
 * All write side-effects (entity updates + progression history / xp / streak / personal
 * records via [ProgressionRepository]) commit inside ONE Room transaction so a mid-flight
 * crash leaves the database fully consistent.
 *
 * **Unified evaluation path (p6-8):** comparison logic lives in [RuleEvaluator] — the same
 * stateless evaluator the achievement signal processor uses. This file owns only the
 * session-end "load active rows → propose updates → commit" choreography; the actual
 * "did a metric cross a target" decision is one method call away.
 */
@Singleton
class ChallengeEngine @Inject constructor(
	private val challengeDatabase: ChallengeDatabase,
	private val metrics: WindowedMetricsProvider,
	private val progression: ProgressionRepository,
	private val ruleEvaluator: RuleEvaluator = RuleEvaluator(),
) {

	/**
	 * Apply a finished [session] to all currently active, not-yet-completed challenges.
	 *
	 * Returns the set of challenges that transitioned to `isCompleted = true` during this
	 * call (each only once), so the caller can fire notifications. The transition itself,
	 * along with all derived progression writes, is already committed by the time this
	 * method returns.
	 */
	suspend fun applySession(
		session: TrackerSession,
	): EngineResult {
		val now = Time.nowMillis
		val challengeDao = challengeDatabase.challengeDao()
		val active: List<ChallengeEntity> = challengeDao.getActive(now)

		if (active.isEmpty()) {
			// Still award passive session XP — gated internally by the daily cap.
			progression.onTrackingSession(session)
			return EngineResult(updates = emptyList(), newlyCompleted = emptyList())
		}

		// Precompute deltas OUTSIDE the write transaction so metric reads don't hold a
		// transaction open longer than needed. The metric provider only reads pre-aggregated
		// tables that won't change during the brief write window.
		val proposedUpdates: List<ProposedUpdate> = active.mapNotNull { entity ->
			evaluateOne(entity, now)
		}

		if (proposedUpdates.isEmpty()) {
			progression.onTrackingSession(session)
			return EngineResult(updates = emptyList(), newlyCompleted = emptyList())
		}

		val completedEntities = mutableListOf<ChallengeEntity>()

		challengeDatabase.withTransaction {
			for (update in proposedUpdates) {
				challengeDao.update(update.updated)
				if (update.justCompleted) {
					completedEntities += update.updated
				}
			}

			// Progression for each completion (history, xp, streak, PR) — its inner
			// withTransaction blocks join THIS outer transaction (Room is reentrant).
			for (entity in completedEntities) {
				val def = ChallengeCatalog.byType(entity.type)
				val instance = ChallengeInstanceNew.fromDefinition(entity, def)
				progression.onChallengeCompleted(instance)
			}
		}

		// Award passive session XP AFTER the completion transaction so the daily cap
		// interacts predictably with completion XP.
		progression.onTrackingSession(session)

		return EngineResult(
			updates = proposedUpdates.map { it.updated },
			newlyCompleted = completedEntities,
		)
	}

	/**
	 * Build a [RuleInstance] for the given challenge row + query its metric for the row's
	 * `[startTime, min(now, endTime)]` window, then ask [RuleEvaluator] whether anything
	 * changed. Returns null when no DB write is needed (no progress AND no completion flip).
	 *
	 * The metric collected is the ABSOLUTE value over the window, not a delta — so
	 * `previousValue` for the instance is what's already stored in the DB row.
	 */
	private suspend fun evaluateOne(
		entity: ChallengeEntity,
		now: Long,
	): ProposedUpdate? {
		val def = ChallengeCatalog.byType(entity.type)

		val windowEnd = minOf(now, entity.endTime)
		if (windowEnd <= entity.startTime) return null

		val window = TimeWindow.Interval(entity.startTime, windowEnd)
		val collected = metrics.collect(def.metric, window)

		// Adapt the catalog row to a unified RuleInstance. The contextId carries the
		// entity id so the signal-processor variant (future) can correlate evaluations
		// back to the underlying row without a side table.
		val rule = Rule(
			id = "challenge:${entity.id}",
			kind = RuleKind.Challenge,
			metric = def.metric,
			target = RuleTarget.Single(entity.requiredValue),
		)
		val instance = RuleInstance(
			rule = rule,
			window = window,
			previousValue = entity.currentValue.toLong(),
			contextId = entity.id,
		)

		return when (val result = ruleEvaluator.evaluate(instance, collected)) {
			is RuleEvaluationResult.Unchanged -> {
				// Value didn't move; nothing to write. But if the persisted completion
				// flag is out of sync with the (unchanged) value vs target (e.g. we're
				// re-applying after a partial crash), re-sync the flag.
				val shouldBeCompleted = entity.currentValue >= entity.requiredValue
				if (shouldBeCompleted != entity.isCompleted) {
					val resync = entity.copy(isCompleted = shouldBeCompleted)
					ProposedUpdate(resync, justCompleted = shouldBeCompleted && !entity.isCompleted)
				} else {
					null
				}
			}
			is RuleEvaluationResult.Completed -> {
				val updated = entity.copy(
					currentValue = result.currentValue.toDouble(),
					isCompleted = true,
				)
				ProposedUpdate(updated, justCompleted = !entity.isCompleted)
			}
			is RuleEvaluationResult.ChallengeProgress -> {
				val updated = entity.copy(
					currentValue = result.currentValue.toDouble(),
					// isCompleted stays whatever it was — challenge progress without crossing target.
					isCompleted = entity.isCompleted,
				)
				ProposedUpdate(updated, justCompleted = false)
			}
			// Achievement-kind results are not possible here — the catalog only produces
			// RuleKind.Challenge rules. Defensive branch keeps the when exhaustive.
			is RuleEvaluationResult.TierUnlocked,
			is RuleEvaluationResult.ProgressUpdated -> error(
				"Unexpected achievement-kind result for challenge rule ${rule.id}"
			)
		}
	}

	private data class ProposedUpdate(val updated: ChallengeEntity, val justCompleted: Boolean)

	/**
	 * Outcome of a single [applySession] call.
	 *
	 * @property updates Every entity whose row changed (progress and/or completion flag).
	 * @property newlyCompleted Subset of [updates] that transitioned from incomplete to
	 *   completed during this call. Callers fire completion notifications for these.
	 */
	data class EngineResult(
		val updates: List<ChallengeEntity>,
		val newlyCompleted: List<ChallengeEntity>,
	)
}
