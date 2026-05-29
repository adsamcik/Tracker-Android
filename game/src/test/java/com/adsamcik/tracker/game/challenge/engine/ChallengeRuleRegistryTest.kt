package com.adsamcik.tracker.game.challenge.engine

import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ChallengeDao
import com.adsamcik.tracker.shared.base.database.data.ChallengeEntity
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import com.adsamcik.tracker.stats.api.rule.RuleKind
import com.adsamcik.tracker.stats.api.rule.RuleTarget
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ChallengeRuleRegistry")
class ChallengeRuleRegistryTest {

	private lateinit var database: AppDatabase
	private lateinit var challengeDao: ChallengeDao
	private lateinit var registry: ChallengeRuleRegistry

	@BeforeEach
	fun setup() {
		database = mockk(relaxed = true)
		challengeDao = mockk(relaxed = true)
		every { database.challengeDao() } returns challengeDao
		registry = ChallengeRuleRegistry(database)
	}

	@Test
	fun `allInstances returns one instance per active row`() = runTest {
		coEvery { challengeDao.getActive(any()) } returns listOf(
			stepEntity(id = 1L, required = 50_000.0),
			stepEntity(id = 2L, required = 100_000.0),
		)
		registry.allInstances() shouldHaveSize 2
	}

	@Test
	fun `allInstances builds RuleInstance with stable challenge id`() = runTest {
		coEvery { challengeDao.getActive(any()) } returns listOf(
			stepEntity(id = 42L, required = 5_000.0),
		)
		val instances = registry.allInstances()
		instances shouldHaveSize 1
		val instance = instances.single()
		instance.rule.id shouldBe "challenge:42"
		instance.rule.kind shouldBe RuleKind.Challenge
		instance.rule.metric shouldBe MetricKeys.STEPS
		instance.rule.target shouldBe RuleTarget.Single(5_000.0)
		instance.contextId shouldBe 42L
		(instance.window is TimeWindow.Interval) shouldBe true
	}

	@Test
	fun `instancesAffectedByTables returns only rules reading dirty tables`() = runTest {
		coEvery { challengeDao.getActive(any()) } returns listOf(
			stepEntity(id = 1L, required = 1_000.0),                             // metric = STEPS (daily_summary)
			consistencyEntity(id = 2L, required = 7.0),                          // metric = ACTIVE_DAYS (daily_summary)
			explorerEntity(id = 3L, required = 5.0),                             // metric = CELLS_DISCOVERED (exploration_cell)
		)

		// Daily summary dirty: catches step and consistency, both daily-summary-backed.
		registry.instancesAffectedByTables(setOf(MetricKeys.TABLE_DAILY_SUMMARY))
			.map { it.contextId }
			.toSet() shouldBe setOf(1L, 2L)

		// Exploration cell dirty: catches only explorer.
		registry.instancesAffectedByTables(setOf(MetricKeys.TABLE_EXPLORATION_CELL))
			.map { it.contextId }
			.toSet() shouldBe setOf(3L)

		// Aggregator state dirty: no current challenge metric is backed by the live
		// snapshot (STEPS is daily-summary-only — only the cumulative TOTAL_STEPS
		// is dual-sourced, and no challenge uses TOTAL_STEPS).
		registry.instancesAffectedByTables(setOf(MetricKeys.TABLE_AGGREGATOR_STATE))
			.shouldBeEmpty()
	}

	@Test
	fun `instancesAffectedByTables returns empty when dirty set is empty`() = runTest {
		coEvery { challengeDao.getActive(any()) } returns listOf(
			stepEntity(id = 1L, required = 1_000.0),
		)
		registry.instancesAffectedByTables(emptySet()).shouldBeEmpty()
	}

	@Test
	fun `instancesAffectedByTables returns empty when no rule reads any dirty table`() = runTest {
		coEvery { challengeDao.getActive(any()) } returns listOf(
			stepEntity(id = 1L, required = 1_000.0),  // not backed by export_log
		)
		registry.instancesAffectedByTables(setOf(MetricKeys.TABLE_EXPORT_LOG)).shouldBeEmpty()
	}

	@Test
	fun `allInstances returns empty when DAO has no active rows`() = runTest {
		coEvery { challengeDao.getActive(any()) } returns emptyList()
		registry.allInstances().shouldBeEmpty()
	}

	@Test
	fun `instancesAffectedByTables returns empty when DAO has no active rows even with dirty tables`() = runTest {
		coEvery { challengeDao.getActive(any()) } returns emptyList()
		registry.instancesAffectedByTables(setOf(MetricKeys.TABLE_DAILY_SUMMARY)).shouldBeEmpty()
	}

	@Test
	fun `instance carries entity attachment so engine avoids per-rule refetch`() = runTest {
		val entity = stepEntity(id = 99L, required = 5_000.0)
		coEvery { challengeDao.getActive(any()) } returns listOf(entity)

		val instance = registry.allInstances().single()
		instance.attachment shouldBe entity
	}

	@Test
	fun `ruleId is stable for repeated calls`() {
		ChallengeRuleRegistry.ruleId(42L) shouldBe "challenge:42"
		ChallengeRuleRegistry.ruleId(42L) shouldBe "challenge:42"
	}

	// ── helpers ────────────────────────────────────────────────────────────────

	private fun stepEntity(id: Long, required: Double): ChallengeEntity {
		val now = Time.nowMillis
		return ChallengeEntity(
			id = id,
			type = ChallengeType.Step,
			startTime = now - 60_000L,
			endTime = now + 60_000L,
			difficulty = ChallengeDifficulty.MEDIUM,
			requiredValue = required,
			currentValue = 0.0,
			isCompleted = false,
		)
	}

	private fun consistencyEntity(id: Long, required: Double): ChallengeEntity {
		val now = Time.nowMillis
		return ChallengeEntity(
			id = id,
			type = ChallengeType.Consistency,
			startTime = now - 60_000L,
			endTime = now + 60_000L,
			difficulty = ChallengeDifficulty.MEDIUM,
			requiredValue = required,
			currentValue = 0.0,
			isCompleted = false,
		)
	}

	private fun explorerEntity(id: Long, required: Double): ChallengeEntity {
		val now = Time.nowMillis
		return ChallengeEntity(
			id = id,
			type = ChallengeType.Explorer,
			startTime = now - 60_000L,
			endTime = now + 60_000L,
			difficulty = ChallengeDifficulty.MEDIUM,
			requiredValue = required,
			currentValue = 0.0,
			isCompleted = false,
		)
	}
}
