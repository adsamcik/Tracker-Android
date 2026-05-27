package com.adsamcik.tracker.game.challenge.engine

import androidx.room.withTransaction
import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.dao.ChallengeDao
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.game.challenge.progression.ProgressionRepository
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.repository.WindowedMetricsProvider
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ChallengeEngine")
class ChallengeEngineTest {

	private lateinit var engine: ChallengeEngine
	private lateinit var database: ChallengeDatabase
	private lateinit var challengeDao: ChallengeDao
	private lateinit var metrics: WindowedMetricsProvider
	private lateinit var progression: ProgressionRepository

	@BeforeEach
	fun setup() {
		database = mockk(relaxed = true)
		challengeDao = mockk(relaxed = true)
		metrics = mockk(relaxed = true)
		progression = mockk(relaxed = true)
		every { database.challengeDao() } returns challengeDao
		mockkStatic("androidx.room.RoomDatabaseKt")
		coEvery {
			database.withTransaction(any<suspend () -> Any?>())
		} coAnswers {
			@Suppress("UNCHECKED_CAST")
			(secondArg<suspend () -> Any?>()).invoke()
		}
		engine = ChallengeEngine(database, metrics, progression)
	}

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	@Test
	fun `empty active set returns empty result and still awards session xp`() = runTest {
		coEvery { challengeDao.getActive(any()) } returns emptyList()

		val result = engine.applySession(sampleSession())

		result.updates shouldBe emptyList()
		result.newlyCompleted shouldBe emptyList()
		coVerify(exactly = 1) { progression.onTrackingSession(any()) }
		coVerify(exactly = 0) { progression.onChallengeCompleted(any()) }
	}

	@Test
	fun `windowed metric below target updates currentValue but does not complete`() = runTest {
		val entity = stepEntity(required = 50_000.0, current = 0.0)
		coEvery { challengeDao.getActive(any()) } returns listOf(entity)
		coEvery { metrics.collect(MetricKeys.STEPS, any()) } returns 30_000L

		val result = engine.applySession(sampleSession())

		result.updates.size shouldBe 1
		result.updates[0].currentValue shouldBe 30_000.0
		result.updates[0].isCompleted shouldBe false
		result.newlyCompleted shouldBe emptyList()
		coVerify(exactly = 0) { progression.onChallengeCompleted(any()) }
		coVerify(exactly = 1) { challengeDao.update(any<ChallengeEntity>()) }
	}

	@Test
	fun `windowed metric crossing target marks completed and triggers progression`() = runTest {
		val entity = stepEntity(required = 50_000.0, current = 49_999.0)
		coEvery { challengeDao.getActive(any()) } returns listOf(entity)
		coEvery { metrics.collect(MetricKeys.STEPS, any()) } returns 50_500L

		val result = engine.applySession(sampleSession())

		result.newlyCompleted.size shouldBe 1
		result.newlyCompleted[0].isCompleted shouldBe true
		coVerify(exactly = 1) { progression.onChallengeCompleted(any()) }
	}

	@Test
	fun `consistency uses ACTIVE_DAYS metric via WindowedMetricsProvider`() = runTest {
		val entity = consistencyEntity(required = 7.0, current = 2.0)
		coEvery { challengeDao.getActive(any()) } returns listOf(entity)
		coEvery { metrics.collect(MetricKeys.ACTIVE_DAYS, any()) } returns 4L

		val result = engine.applySession(sampleSession())

		result.updates.size shouldBe 1
		result.updates[0].currentValue shouldBe 4.0
		result.updates[0].isCompleted shouldBe false
		coVerify(exactly = 1) { metrics.collect(MetricKeys.ACTIVE_DAYS, any()) }
	}

	@Test
	fun `no-change entity is not written`() = runTest {
		val entity = stepEntity(required = 50_000.0, current = 10_000.0)
		coEvery { challengeDao.getActive(any()) } returns listOf(entity)
		coEvery { metrics.collect(MetricKeys.STEPS, any()) } returns 10_000L

		val result = engine.applySession(sampleSession())

		result.updates shouldBe emptyList()
		coVerify(exactly = 0) { challengeDao.update(any<ChallengeEntity>()) }
		coVerify(exactly = 0) { progression.onChallengeCompleted(any()) }
		// Even when nothing advances, passive session xp still runs (gated by daily cap).
		coVerify(exactly = 1) { progression.onTrackingSession(any()) }
	}

	@Test
	fun `consistency unchanged entity is not written`() = runTest {
		val entity = consistencyEntity(required = 7.0, current = 2.0)
		coEvery { challengeDao.getActive(any()) } returns listOf(entity)
		coEvery { metrics.collect(MetricKeys.ACTIVE_DAYS, any()) } returns 2L

		val result = engine.applySession(sampleSession())

		result.updates shouldBe emptyList()
		coVerify(exactly = 0) { challengeDao.update(any<ChallengeEntity>()) }
	}

	@Test
	fun `multiple active challenges are evaluated independently`() = runTest {
		val a = stepEntity(id = 1L, required = 50_000.0, current = 0.0)
		val b = stepEntity(id = 2L, required = 100_000.0, current = 0.0)
		coEvery { challengeDao.getActive(any()) } returns listOf(a, b)
		coEvery { metrics.collect(MetricKeys.STEPS, any()) } returns 60_000L

		val result = engine.applySession(sampleSession())

		// Both advanced; only `a` crossed its lower target.
		result.updates.size shouldBe 2
		result.newlyCompleted.size shouldBe 1
		result.newlyCompleted[0].id shouldBe 1L
		coVerify(exactly = 1) { progression.onChallengeCompleted(any()) }
	}

	// ── helpers ────────────────────────────────────────────────────────────────

	private fun stepEntity(
		id: Long = 1L,
		required: Double,
		current: Double,
	): ChallengeEntity {
		val now = Time.nowMillis
		return ChallengeEntity(
			id = id,
			type = ChallengeType.Step,
			startTime = now - 60_000L,
			endTime = now + 60_000L,
			difficulty = ChallengeDifficulty.MEDIUM,
			requiredValue = required,
			currentValue = current,
			isCompleted = false,
		)
	}

	private fun consistencyEntity(
		id: Long = 10L,
		required: Double,
		current: Double,
	): ChallengeEntity {
		val now = Time.nowMillis
		return ChallengeEntity(
			id = id,
			type = ChallengeType.Consistency,
			startTime = now - 86_400_000L,
			endTime = now + 86_400_000L,
			difficulty = ChallengeDifficulty.MEDIUM,
			requiredValue = required,
			currentValue = current,
			isCompleted = false,
		)
	}

	private fun sampleSession(): TrackerSession = TrackerSession(
		id = 1L,
		start = Time.nowMillis - 60_000L,
		end = Time.nowMillis,
		isUserInitiated = true,
		collections = 5,
		distanceInM = 1_000f,
		distanceOnFootInM = 1_000f,
		distanceInVehicleInM = 0f,
		steps = 1_500,
	)
}
