package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.metric.MetricSnapshot
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.rule.Rule
import com.adsamcik.tracker.stats.api.rule.RuleInstance
import com.adsamcik.tracker.stats.api.rule.RuleKind
import com.adsamcik.tracker.stats.api.rule.RuleRegistry
import com.adsamcik.tracker.stats.api.rule.RuleTarget
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AchievementProcessorPacingTest {
	private val definition = AchievementDefinition(
		id = "paced_distance",
		category = com.adsamcik.tracker.stats.api.AchievementCategory.DISTANCE,
		nameRes = "achievement_paced_distance_title",
		descriptionRes = "achievement_paced_distance_desc",
		metric = MetricKey.DISTANCE_TOTAL_M,
		threshold = 5_000.0,
		tier = AchievementTier.SILVER,
		tierIndex = 1,
		dependsOn = setOf(MetricKey.DISTANCE_TOTAL_M, MetricKey.ACTIVE_DAYS_TOTAL),
		minimumActiveDays = 2,
	)
	private val instance = RuleInstance(
		rule = Rule(
			id = definition.id,
			kind = RuleKind.Achievement,
			metric = definition.metric,
			target = RuleTarget.Single(definition.threshold, definition.tier),
		),
		window = TimeWindow.Cumulative,
		attachment = definition,
	)
	private val registry = object : RuleRegistry {
		override suspend fun allInstances(): List<RuleInstance> = listOf(instance)
		override suspend fun instancesAffectedByTables(dirtyTables: Set<String>): List<RuleInstance> = listOf(instance)
	}

	@Test
	fun unchangedPacedMetricDoesNotEmitRepeatedProgress() = runTest {
		var snapshot = MetricSnapshot.of(
			MetricKey.DISTANCE_TOTAL_M to 10_000,
			MetricKey.ACTIVE_DAYS_TOTAL to 1,
		)
		val processor = AchievementProcessor(registry, metricsProvider = { snapshot })
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

		assertTrue(processor.onFlush().isEmpty())
		snapshot = MetricSnapshot.of(
			MetricKey.DISTANCE_TOTAL_M to 10_000,
			MetricKey.ACTIVE_DAYS_TOTAL to 2,
		)
		val unlocks = processor.onFlush().filterIsInstance<DomainEvent.AchievementUnlocked>()
		assertEquals(1, unlocks.size)
		assertTrue(processor.onFlush().isEmpty())

		snapshot = MetricSnapshot.of(
			MetricKey.DISTANCE_TOTAL_M to 10_000,
			MetricKey.ACTIVE_DAYS_TOTAL to 3,
		)
		assertTrue(processor.onFlush().isEmpty())

		snapshot = MetricSnapshot.of(
			MetricKey.DISTANCE_TOTAL_M to 12_000,
			MetricKey.ACTIVE_DAYS_TOTAL to 4,
		)
		assertTrue(processor.onFlush().isEmpty())
	}
}
