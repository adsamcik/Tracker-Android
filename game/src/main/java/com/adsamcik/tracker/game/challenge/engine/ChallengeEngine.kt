package com.adsamcik.tracker.game.challenge.engine

import androidx.room.withTransaction
import com.adsamcik.tracker.game.challenge.catalog.ChallengeCatalog
import com.adsamcik.tracker.game.challenge.data.ChallengeInstanceNew
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.game.challenge.progression.ProgressionRepository
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.stats.api.repository.WindowedMetricsProvider
import com.adsamcik.tracker.stats.api.rule.RuleEvaluationResult
import com.adsamcik.tracker.stats.api.rule.RuleEvaluator
import com.adsamcik.tracker.stats.api.rule.RuleInstance
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Declarative challenge evaluator.
 *
 * Composes three building blocks:
 *  - [ChallengeRuleRegistry] enumerates the active challenge rows as [RuleInstance]s.
 *  - [WindowedMetricsProvider] collects the current metric value over each rule's window.
 *  - [RuleEvaluator] (stateless, shared with the achievement engine) compares the
 *    collected value to the rule's target and returns a typed result.
 *
 * Engine adds the choreography: load instances → query metrics → evaluate → batch entity
 * updates + progression writes inside ONE Room transaction. Comparison logic lives in
 * [RuleEvaluator]; storage choreography lives here.
 *
 * **Atomicity:** entity updates, history rows, XP ledger, profile, streak, and personal
 * records all commit inside a single `withTransaction`. Inner transactions in
 * [ProgressionRepository.onChallengeCompleted] join the outer one (Room is reentrant).
 *
 * **Reads outside the write transaction:** the metric provider only reads
 * pre-aggregated tables that don't change during the brief write window, so keeping the
 * transaction short for jank-sensitive jobs is the higher-value trade-off.
 */
@Singleton
class ChallengeEngine @Inject constructor(
	private val challengeDatabase: ChallengeDatabase,
	private val registry: ChallengeRuleRegistry,
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
	 *
	 * Always invokes [ProgressionRepository.onTrackingSession] at the end so passive
	 * session XP is awarded even when no challenge moved — that path is internally gated
	 * by the daily XP cap.
	 */
	suspend fun applySession(
		session: TrackerSession,
	): EngineResult {
		val instances = registry.allInstances()

		if (instances.isEmpty()) {
			progression.onTrackingSession(session)
			return EngineResult(updates = emptyList(), newlyCompleted = emptyList())
		}

		val proposedUpdates: List<ProposedUpdate> = instances.mapNotNull { evaluateOne(it) }

		if (proposedUpdates.isEmpty()) {
			progression.onTrackingSession(session)
			return EngineResult(updates = emptyList(), newlyCompleted = emptyList())
		}

		val completedEntities = mutableListOf<ChallengeEntity>()
		val challengeDao = challengeDatabase.challengeDao()

		challengeDatabase.withTransaction {
			for (update in proposedUpdates) {
				challengeDao.update(update.updated)
				if (update.justCompleted) {
					completedEntities += update.updated
				}
			}
			for (entity in completedEntities) {
				val def = ChallengeCatalog.byType(entity.type)
				val instance = ChallengeInstanceNew.fromDefinition(entity, def)
				progression.onChallengeCompleted(instance)
			}
		}

		progression.onTrackingSession(session)

		return EngineResult(
			updates = proposedUpdates.map { it.updated },
			newlyCompleted = completedEntities,
		)
	}

	/**
	 * Collect the metric for [instance]'s window and delegate the value-vs-target decision
	 * to [RuleEvaluator]. Returns null when no DB write is needed (no progress AND no
	 * completion-flag flip).
	 */
	private suspend fun evaluateOne(instance: RuleInstance): ProposedUpdate? {
		val entityId = instance.contextId
			?: error("ChallengeRuleRegistry must populate RuleInstance.contextId with the entity id")
		val entity = challengeDatabase.challengeDao().get(entityId)
			?: return null  // row vanished between registry enumeration and now — nothing to do

		val collected = metrics.collect(instance.rule.metric, instance.window)

		return when (val result = ruleEvaluator.evaluate(instance, collected)) {
			is RuleEvaluationResult.Unchanged -> {
				// Re-sync completion flag if persisted state drifted from value-vs-target
				// (e.g. partial crash before flag write).
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
					isCompleted = entity.isCompleted,
				)
				ProposedUpdate(updated, justCompleted = false)
			}
			is RuleEvaluationResult.TierUnlocked,
			is RuleEvaluationResult.ProgressUpdated -> error(
				"Unexpected achievement-kind result for challenge rule ${instance.rule.id}"
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
