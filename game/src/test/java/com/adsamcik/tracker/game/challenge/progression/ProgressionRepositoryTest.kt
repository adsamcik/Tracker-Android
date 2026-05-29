package com.adsamcik.tracker.game.challenge.progression

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.game.challenge.data.XpSource
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ChallengeDifficulty
import com.adsamcik.tracker.shared.base.database.data.ChallengeEntity
import com.adsamcik.tracker.shared.base.database.data.ChallengeType
import com.adsamcik.tracker.shared.base.database.data.XpLedgerEntity
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
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
class ProgressionRepositoryTest {

	private lateinit var context: Application
	private lateinit var database: AppDatabase
	private lateinit var repository: ProgressionRepository

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		repository = ProgressionRepository(
			xpCalculator = XpCalculator(),
			streakManager = StreakManager(),
			challengeDatabase = database,
		)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `xp ledger ignores duplicate source awards`() = runTest {
		val dao = database.xpLedgerDao()
		val entry = XpLedgerEntity(
			amount = 75,
			source = XpSource.SESSION.name,
			sourceId = 42L,
			earnedAt = Time.nowMillis,
		)

		dao.insertOrIgnore(entry) shouldBe 1L
		dao.insertOrIgnore(entry.copy(id = 0)) shouldBe -1L

		dao.getTotalXp() shouldBe 75L
	}

	@Test
	fun `tracking session xp is deduped and capped per day`() = runTest {
		repository.onTrackingSession(highValueSession(id = 1L))
		repository.onTrackingSession(highValueSession(id = 1L))
		repository.onTrackingSession(highValueSession(id = 2L))
		repository.onTrackingSession(highValueSession(id = 3L))

		database.xpLedgerDao().getTotalXp() shouldBe XpCalculator.DAILY_CAP.toLong()
		database.xpLedgerDao().getRecent(limit = 10).size shouldBe 2
	}

	@Test
	fun `expiry claim is idempotent and deletes processed active rows`() = runTest {
		val now = Time.nowMillis
		val id = database.challengeDao().insert(expiredChallenge(now))

		val first = repository.onActiveChallengesExpiredAt(now)
		val second = repository.onActiveChallengesExpiredAt(now)

		first.expiredCount shouldBe 1
		first.streakBroken shouldBe true
		second.expiredCount shouldBe 0
		database.challengeDao().get(id) shouldBe null
		val history = database.challengeHistoryDao().getAll()
		history.size shouldBe 1
		history.single().originalChallengeId shouldBe id
		history.single().outcome shouldBe "EXPIRED"
	}

	@Test
	fun `expiry claim ignores challenge completed before transaction read`() = runTest {
		val now = Time.nowMillis
		val id = database.challengeDao().insert(expiredChallenge(now))
		val completed = database.challengeDao().get(id)!!.copy(isCompleted = true, currentValue = 100.0)
		database.challengeDao().update(completed)

		val result = repository.onActiveChallengesExpiredAt(now)

		result.expiredCount shouldBe 0
		database.challengeHistoryDao().getAll() shouldBe emptyList()
		database.challengeDao().get(id)!!.isCompleted shouldBe true
	}

	private fun expiredChallenge(now: Long): ChallengeEntity = ChallengeEntity(
		type = ChallengeType.Step,
		startTime = now - 10_000L,
		endTime = now - 1_000L,
		difficulty = ChallengeDifficulty.MEDIUM,
		requiredValue = 100.0,
		currentValue = 10.0,
		isCompleted = false,
	)

	private fun highValueSession(id: Long): TrackerSession = TrackerSession(
		id = id,
		start = Time.nowMillis - 60_000L,
		end = Time.nowMillis,
		isUserInitiated = true,
		collections = 1,
		distanceInM = 6_000f,
		distanceOnFootInM = 6_000f,
		distanceInVehicleInM = 0f,
		steps = 0,
	)
}
