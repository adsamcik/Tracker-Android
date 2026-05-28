package com.adsamcik.tracker.game.challenge.engine

import com.adsamcik.tracker.game.challenge.catalog.ChallengeCatalog
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import com.adsamcik.tracker.stats.api.rule.Rule
import com.adsamcik.tracker.stats.api.rule.RuleInstance
import com.adsamcik.tracker.stats.api.rule.RuleKind
import com.adsamcik.tracker.stats.api.rule.RuleRegistry
import com.adsamcik.tracker.stats.api.rule.RuleTarget
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dynamic [RuleRegistry] backed by the active rows in `challenge`.
 *
 * Each row that is still inside its `[startTime, endTime]` window becomes one
 * [RuleInstance] with:
 *  - `rule.id = "challenge:${entity.id}"`
 *  - `rule.kind = RuleKind.Challenge`
 *  - `rule.metric = ChallengeCatalog.byType(entity.type).metric`
 *  - `rule.target = RuleTarget.Single(entity.requiredValue)`
 *  - `window = TimeWindow.Interval(entity.startTime, min(now, entity.endTime))`
 *  - `previousValue = entity.currentValue.toLong()` (what's already persisted)
 *  - `contextId = entity.id` (so the consumer can correlate evaluations back to the row)
 *
 * Used by [ChallengeEngine] at session-end and by the unified rule signal processor
 * (future) for live in-session evaluation. Both consumers feed the resulting
 * `RuleInstance` to the shared [com.adsamcik.tracker.stats.api.rule.RuleEvaluator].
 *
 * [instancesAffectedByTables] is the battery-fast lookup: when only one or two
 * pre-aggregated tables have changed since the last flush, only the subset of
 * challenges whose metric reads one of those tables is returned — the rest of the
 * catalog is skipped.
 */
@Singleton
class ChallengeRuleRegistry @Inject constructor(
	private val challengeDatabase: ChallengeDatabase,
) : RuleRegistry {

	override suspend fun allInstances(): List<RuleInstance> {
		val now = Time.nowMillis
		return challengeDatabase.challengeDao()
			.getActive(now)
			.mapNotNull { entity -> toInstance(entity, now) }
	}

	override suspend fun instancesAffectedByTables(
		dirtyTables: Set<String>,
	): List<RuleInstance> {
		if (dirtyTables.isEmpty()) return emptyList()
		val now = Time.nowMillis
		return challengeDatabase.challengeDao()
			.getActive(now)
			.mapNotNull { entity ->
				val def = ChallengeCatalog.byType(entity.type)
				val sources = MetricKeys.sourceTables(def.metric)
				if (sources.any { it in dirtyTables }) {
					toInstance(entity, now)
				} else {
					null
				}
			}
	}

	/**
	 * Build a [RuleInstance] for the given row. Returns null if the window has degenerate
	 * bounds (would happen if `now <= startTime`, which the DAO query already filters out,
	 * but defending here keeps the contract self-consistent).
	 */
	private fun toInstance(entity: ChallengeEntity, now: Long): RuleInstance? {
		val windowEnd = minOf(now, entity.endTime)
		if (windowEnd <= entity.startTime) return null

		val def = ChallengeCatalog.byType(entity.type)
		val rule = Rule(
			id = ruleId(entity.id),
			kind = RuleKind.Challenge,
			metric = def.metric,
			target = RuleTarget.Single(entity.requiredValue),
		)
		return RuleInstance(
			rule = rule,
			window = TimeWindow.Interval(entity.startTime, windowEnd),
			previousValue = entity.currentValue.toLong(),
			contextId = entity.id,
		)
	}

	companion object {
		/**
		 * Stable id format for challenge-kind rules. Stable across re-loads because it's
		 * derived from the persisted entity id. Consumers can parse the suffix back to a
		 * challenge entity id with [parseEntityId].
		 */
		fun ruleId(entityId: Long): String = "challenge:$entityId"

		/** Inverse of [ruleId]. Returns null if [id] isn't a challenge-rule id. */
		fun parseEntityId(id: String): Long? =
			id.removePrefix("challenge:").takeIf { it.length < id.length }?.toLongOrNull()
	}
}
