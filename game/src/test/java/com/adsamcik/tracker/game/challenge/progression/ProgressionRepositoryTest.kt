package com.adsamcik.tracker.game.challenge.progression

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.game.challenge.data.XpSource
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.entity.XpLedgerEntity
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
	private lateinit var database: ChallengeDatabase
	private lateinit var repository: ProgressionRepository

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = ChallengeDatabase.testDatabase(context)
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
