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
}
