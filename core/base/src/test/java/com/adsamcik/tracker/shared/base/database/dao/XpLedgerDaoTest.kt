package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XpLedgerDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: XpLedgerDao

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		dao = database.xpLedgerDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `revisioned Steps goal XP retains a zero receipt against stale replay`() = runTest {
		val effectKey = "steps-goal-v1:DAY:20000"
		dao.applyStepsGoalEffect(effectKey, effectRevision = 3L, amount = 50, earnedAt = 1_000L)
			.shouldBe(true)
		dao.applyStepsGoalEffect(effectKey, effectRevision = 4L, amount = 0, earnedAt = 1_000L)
			.shouldBe(true)
		dao.applyStepsGoalEffect(effectKey, effectRevision = 3L, amount = 50, earnedAt = 1_000L)
			.shouldBe(false)

		val stored = dao.getRevisionedEffect("GOAL", effectKey)
		checkNotNull(stored)
		stored.amount shouldBe 0
		stored.sourceRevision shouldBe 4L
		dao.getTotalXp() shouldBe 0L
		dao.getRecent(limit = 10) shouldBe emptyList()
		dao.countDistinctSources() shouldBe 0L
		dao.getEarnedAtBySource("GOAL") shouldBe emptyList()
	}
	@Test
	fun `exact same revision XP replay leaves its saved receipt unchanged`() = runTest {
		val key = "steps-goal-v1:DAY:20000"
		dao.applyStepsGoalEffect(key, 3L, 50, 1_000L) shouldBe true
		val before = checkNotNull(dao.getRevisionedEffect("GOAL", key))

		dao.applyStepsGoalEffect(key, 3L, 50, 1_000L) shouldBe true
		dao.getRevisionedEffect("GOAL", key) shouldBe before
		dao.updateRevisionedEffect("GOAL", key, 3L, 0, 2_000L) shouldBe 0
		dao.getRevisionedEffect("GOAL", key) shouldBe before
		dao.getTotalXp() shouldBe 50L
	}

	@Test
	fun `same revision XP cannot change value or earned time`() = runTest {
		val key = "steps-goal-v1:DAY:20000"
		dao.applyStepsGoalEffect(key, 3L, 50, 1_000L)
		val before = checkNotNull(dao.getRevisionedEffect("GOAL", key))

		dao.applyStepsGoalEffect(key, 3L, 0, 1_000L) shouldBe false
		dao.applyStepsGoalEffect(key, 3L, 50, 1_001L) shouldBe false
		dao.getRevisionedEffect("GOAL", key) shouldBe before
	}

	@Test
	fun `zero XP receipt requires a newer revision before reward can return`() = runTest {
		val key = "steps-goal-v1:DAY:20000"
		dao.applyStepsGoalEffect(key, 4L, 0, 1_000L)
		val zero = checkNotNull(dao.getRevisionedEffect("GOAL", key))

		dao.applyStepsGoalEffect(key, 4L, 50, 1_000L) shouldBe false
		dao.applyStepsGoalEffect(key, 4L, 0, 1_001L) shouldBe false
		dao.getRevisionedEffect("GOAL", key) shouldBe zero
		dao.getTotalXp() shouldBe 0L

		dao.applyStepsGoalEffect(key, 5L, 50, 1_000L) shouldBe true
		val corrected = checkNotNull(dao.getRevisionedEffect("GOAL", key))
		corrected.id shouldBe zero.id
		corrected.sourceRevision shouldBe 5L
		corrected.amount shouldBe 50
		dao.getTotalXp() shouldBe 50L
	}

	@Test
	fun `same revision XP rejects a receipt with foreign legacy ownership`() = runTest {
		val key = "steps-goal-v1:DAY:20000"
		dao.applyStepsGoalEffect(key, 3L, 50, 1_000L)
		val foreign = checkNotNull(dao.getRevisionedEffect("GOAL", key)).copy(sourceId = 77L)
		dao.update(foreign)

		dao.applyStepsGoalEffect(key, 3L, 50, 1_000L) shouldBe false
		dao.getRevisionedEffect("GOAL", key) shouldBe foreign
	}

	@Test
	fun `higher goal revision cannot replace XP with legacy recording ownership`() = runTest {
		val key = "steps-goal-v1:DAY:20000"
		dao.applyStepsGoalEffect(key, 3L, 50, 1_000L)
		val legacy = checkNotNull(dao.getRevisionedEffect("GOAL", key)).copy(sourceId = 77L)
		dao.update(legacy)

		dao.applyStepsGoalEffect(key, 4L, 0, 2_000L) shouldBe false
		dao.getRevisionedEffect("GOAL", key) shouldBe legacy
		dao.getTotalXp() shouldBe 50L
		dao.getRecent(10) shouldBe listOf(legacy)
	}

	@Test
	fun `direct higher revision SQL cannot replace legacy owned XP`() = runTest {
		val key = "steps-goal-v1:DAY:20000"
		dao.applyStepsGoalEffect(key, 3L, 50, 1_000L)
		val legacy = checkNotNull(dao.getRevisionedEffect("GOAL", key)).copy(sourceId = 77L)
		dao.update(legacy)

		dao.updateRevisionedEffect("GOAL", key, 4L, 0, 2_000L) shouldBe 0
		dao.getRevisionedEffect("GOAL", key) shouldBe legacy
		dao.getTotalXp() shouldBe 50L
	}

}
