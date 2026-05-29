package com.adsamcik.tracker.stats.api.achievement

import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.metric.MetricSnapshot

sealed interface AchievementUnlockEvent {
	val definition: AchievementDefinition
	val value: Double
	data class Unlocked(override val definition: AchievementDefinition, override val value: Double) : AchievementUnlockEvent
}

class AchievementEvaluator(private val catalog: AchievementCatalog = AchievementCatalog) {
	fun evaluate(changedMetrics: Set<MetricKey>, snapshot: MetricSnapshot, lastUnlockedTierByMetric: Map<MetricKey, Int>): List<AchievementUnlockEvent> {
		if (changedMetrics.isEmpty()) return emptyList()
		var events: MutableList<AchievementUnlockEvent>? = null
		for (metric in changedMetrics) {
			val definitions = catalog.byMetric(metric)
			var index = (lastUnlockedTierByMetric[metric] ?: -1) + 1
			val value = snapshot.valueOf(metric)
			while (index < definitions.size) {
				val definition = definitions[index]
				if (definition.isCompound || value < definition.threshold) break
				if (events == null) events = ArrayList()
				events.add(AchievementUnlockEvent.Unlocked(definition, value))
				index++
			}
		}
		for (definition in catalog.compoundRules) {
			if (!definition.dependsOnAnyOf(changedMetrics)) continue
			if ((lastUnlockedTierByMetric[definition.metric] ?: -1) >= definition.tierIndex) continue
			val value = snapshot.valueOf(definition.metric)
			if (value >= definition.threshold) {
				if (events == null) events = ArrayList()
				events.add(AchievementUnlockEvent.Unlocked(definition, value))
			}
		}
		return events ?: emptyList()
	}
}
