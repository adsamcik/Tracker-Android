package com.adsamcik.tracker.stats.data.achievement

import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
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
 * Static achievement [RuleRegistry] backed by [AchievementCatalog] and persisted progress rows.
 *
 * Each catalog definition becomes one cumulative achievement rule. The persisted
 * `achievement_progress` row supplies the previous value/tier used by RuleEvaluator callers
 * for change detection, while the catalog definition is carried in [RuleInstance.attachment]
 * so consumers can use the achievement-specific model without a second lookup.
 */
@Singleton
class AchievementRuleRegistry(
	private val achievementProgressDao: AchievementProgressDao,
	private val definitions: List<AchievementDefinition>,
) : RuleRegistry {

	@Inject
	constructor(
		achievementProgressDao: AchievementProgressDao,
	) : this(achievementProgressDao, AchievementCatalog.definitions)

	override suspend fun allInstances(): List<RuleInstance> {
		return toInstances(achievementProgressDao.getAll())
	}

	override suspend fun instancesAffectedByTables(
		dirtyTables: Set<String>,
	): List<RuleInstance> {
		if (dirtyTables.isEmpty()) return emptyList()
		val affectedDefinitions = definitions.filter { definition ->
			MetricKeys.sourceTables(definition.metric).any { it in dirtyTables }
		}
		if (affectedDefinitions.isEmpty()) return emptyList()
		return toInstances(achievementProgressDao.getAll(), affectedDefinitions)
	}

	private fun toInstances(
		rows: List<AchievementProgressEntity>,
		catalog: List<AchievementDefinition> = definitions,
	): List<RuleInstance> {
		val previousById = rows.associateBy { it.achievementId }
		return catalog.map { definition ->
			check(MetricKeys.isKnown(definition.metric)) {
				"Achievement ${definition.id} declares unknown metric '${definition.metric}' " +
					"(not in MetricKeys). Add it to MetricKeys.SOURCE_TABLES or fix the catalog."
			}
			val previous = previousById[definition.id]
			RuleInstance(
				rule = Rule(
					id = definition.id,
					kind = RuleKind.Achievement,
					metric = definition.metric,
					target = RuleTarget.Tiered(definition.tiers),
				),
				window = TimeWindow.Cumulative,
				previousValue = previous?.currentValue,
				previousTier = previous?.tier?.let { AchievementTier.entries.getOrNull(it) },
				attachment = definition,
			)
		}
	}
}
