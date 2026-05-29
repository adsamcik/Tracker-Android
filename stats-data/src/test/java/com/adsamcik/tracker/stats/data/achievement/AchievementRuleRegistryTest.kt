package com.adsamcik.tracker.stats.data.achievement

import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.AchievementCategory
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import com.adsamcik.tracker.stats.api.rule.RuleKind
import com.adsamcik.tracker.stats.api.rule.RuleTarget
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AchievementRuleRegistryTest {

	private val stepsDefinition = AchievementDefinition(
		id = "steps_total",
		category = AchievementCategory.STEPS,
		titleRes = "steps_title",
		descriptionRes = "steps_desc",
		metric = MetricKeys.TOTAL_STEPS,
		tiers = mapOf(
			AchievementTier.BRONZE to 10_000L,
			AchievementTier.SILVER to 25_000L,
		),
	)

	private val tripsDefinition = AchievementDefinition(
		id = "trips_total",
		category = AchievementCategory.MILESTONES,
		titleRes = "trips_title",
		descriptionRes = "trips_desc",
		metric = MetricKeys.TOTAL_TRIPS,
		tiers = mapOf(AchievementTier.BRONZE to 1L),
	)

	private val definitions = listOf(stepsDefinition, tripsDefinition)

	@Test
	fun `empty progress table emits one instance per achievement definition`() = runTest {
		val dao = daoReturning(emptyList())
		val registry = AchievementRuleRegistry(dao, definitions)

		val instances = registry.allInstances()

		instances shouldHaveSize 2
		instances.map { it.rule.id } shouldBe listOf("steps_total", "trips_total")
		instances.forEach { instance ->
			instance.previousValue shouldBe null
			instance.previousTier shouldBe null
		}
	}

	@Test
	fun `multi-row progress table hydrates previous value and tier per definition`() = runTest {
		val dao = daoReturning(
			listOf(
				progress("steps_total", currentValue = 12_000L, tier = AchievementTier.BRONZE),
				progress("trips_total", currentValue = 4L, tier = null),
			),
		)
		val registry = AchievementRuleRegistry(dao, definitions)

		val instances = registry.allInstances().associateBy { it.rule.id }

		instances["steps_total"]!!.previousValue shouldBe 12_000L
		instances["steps_total"]!!.previousTier shouldBe AchievementTier.BRONZE
		instances["trips_total"]!!.previousValue shouldBe 4L
		instances["trips_total"]!!.previousTier shouldBe null
	}

	@Test
	fun `instance shape matches achievement rule contract`() = runTest {
		val dao = daoReturning(emptyList())
		val registry = AchievementRuleRegistry(dao, listOf(stepsDefinition))

		val instance = registry.allInstances().single()

		instance.rule.id shouldBe stepsDefinition.id
		instance.rule.kind shouldBe RuleKind.Achievement
		instance.rule.metric shouldBe stepsDefinition.metric
		instance.rule.target shouldBe RuleTarget.Tiered(stepsDefinition.tiers)
		instance.window shouldBe TimeWindow.Cumulative
		instance.contextId shouldBe null
	}

	@Test
	fun `attachment carries original achievement definition`() = runTest {
		val dao = daoReturning(emptyList())
		val registry = AchievementRuleRegistry(dao, listOf(stepsDefinition))

		val instance = registry.allInstances().single()

		instance.attachment shouldBe stepsDefinition
	}

	@Test
	fun `instancesAffectedByTables filters by metric source tables`() = runTest {
		val dao = daoReturning(emptyList())
		val registry = AchievementRuleRegistry(dao, definitions)

		val instances = registry.instancesAffectedByTables(setOf(MetricKeys.TABLE_DAILY_SUMMARY))

		instances.map { it.rule.id } shouldBe listOf("steps_total", "trips_total")
		registry.instancesAffectedByTables(setOf(MetricKeys.TABLE_EXPORT_LOG)).shouldHaveSize(0)
	}

	private fun daoReturning(rows: List<AchievementProgressEntity>): AchievementProgressDao {
		val dao: AchievementProgressDao = mockk()
		coEvery { dao.getAll() } returns rows
		return dao
	}

	private fun progress(
		achievementId: String,
		currentValue: Long,
		tier: AchievementTier?,
	): AchievementProgressEntity = AchievementProgressEntity(
		achievementId = achievementId,
		currentValue = currentValue,
		targetValue = currentValue,
		tier = tier?.ordinal,
		updatedAt = 1L,
	)
}
