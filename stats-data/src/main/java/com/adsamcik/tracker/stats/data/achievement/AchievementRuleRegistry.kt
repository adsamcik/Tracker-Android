package com.adsamcik.tracker.stats.data.achievement

import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import com.adsamcik.tracker.stats.api.rule.Rule
import com.adsamcik.tracker.stats.api.rule.RuleInstance
import com.adsamcik.tracker.stats.api.rule.RuleKind
import com.adsamcik.tracker.stats.api.rule.RuleRegistry
import com.adsamcik.tracker.stats.api.rule.RuleTarget
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AchievementRuleRegistry @Inject constructor(
	private val progressDao: AchievementProgressDao,
) : RuleRegistry {
	override suspend fun allInstances(): List<RuleInstance> = toInstances(AchievementCatalog.definitions, progressDao.getAll())

	override suspend fun instancesAffectedByTables(dirtyTables: Set<String>): List<RuleInstance> {
		if (dirtyTables.isEmpty()) return emptyList()
		val affected = MetricKey.entries.asSequence()
			.filter { metric -> metric.sourceTables.any { it in dirtyTables } }
			.flatMap { metric -> AchievementCatalog.byMetric(metric).asSequence() }
			.distinctBy { it.id }
			.toList()
		return if (affected.isEmpty()) emptyList() else toInstances(affected, progressDao.getAll())
	}

	private fun toInstances(definitions: List<AchievementDefinition>, rows: List<AchievementProgressEntity>): List<RuleInstance> {
		val progressByMetric = rows.mapNotNull { row -> MetricKey.fromStorageKey(row.metricKey)?.let { it to row } }.toMap()
		return definitions.map { definition ->
			val progress = progressByMetric[definition.metric]
			RuleInstance(
				rule = Rule(definition.id, RuleKind.Achievement, definition.metric, RuleTarget.Single(definition.threshold, definition.tier)),
				window = TimeWindow.Cumulative,
				previousValue = progress?.lastValue?.toLong(),
				previousTier = if ((progress?.lastTierIndex ?: -1) >= definition.tierIndex) definition.tier else null,
				attachment = definition,
			)
		}
	}
}
