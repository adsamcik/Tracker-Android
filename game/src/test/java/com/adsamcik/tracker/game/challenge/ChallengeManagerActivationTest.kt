package com.adsamcik.tracker.game.challenge

import android.content.Context
import com.adsamcik.tracker.game.challenge.catalog.ChallengeCatalog
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.dao.ChallengeDao
import com.adsamcik.tracker.game.challenge.database.dao.ChallengeHistoryDao
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.game.challenge.engine.ChallengeEngine
import com.adsamcik.tracker.game.challenge.progression.ProgressionRepository
import com.adsamcik.tracker.game.challenge.worker.ChallengeExpiredWorker
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Verifies that activation is now driven by [ChallengeCatalog] (post p2-4) rather
 * than the deleted `ChallengeTypeRegistry` multibinding.
 *
 * `activateRandomChallenge` is private; we exercise it indirectly via
 * `awaitReady` → `loadInitial` → `fillEmptyChallengeSlotsLocked`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ChallengeManager activation (catalog-driven)")
class ChallengeManagerActivationTest {

	private lateinit var progressionRepository: ProgressionRepository
	private lateinit var challengeDatabase: ChallengeDatabase
	private lateinit var challengeDao: ChallengeDao
	private lateinit var historyDao: ChallengeHistoryDao
	private lateinit var engine: ChallengeEngine
	private lateinit var mockContext: Context
	private lateinit var inserted: MutableList<ChallengeEntity>

	@BeforeEach
	fun setUpMocks() {
		mockContext = mockk(relaxed = true)
		progressionRepository = mockk(relaxed = true)
		engine = mockk(relaxed = true)
		inserted = mutableListOf()
		val idSeq = AtomicLong(1L)

		challengeDao = mockk(relaxed = true)
		historyDao = mockk(relaxed = true) {
			coEvery { getRecentCompletionRate(any()) } returns null
		}

		val entitySlot = slot<ChallengeEntity>()
		coEvery { challengeDao.insert(capture(entitySlot)) } coAnswers {
			val id = idSeq.getAndIncrement()
			inserted += entitySlot.captured.copy(id = id)
			id
		}

		challengeDatabase = mockk(relaxed = true) {
			every { challengeDao() } returns challengeDao
			every { challengeHistoryDao() } returns historyDao
		}
		mockkObject(ChallengeExpiredWorker.Companion)
		every { ChallengeExpiredWorker.schedule(any(), any()) } just runs
	}

	@org.junit.jupiter.api.AfterEach
	fun tearDown() {
		unmockkObject(ChallengeExpiredWorker.Companion)
	}

	private fun makeManager(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) = ChallengeManager(
		progressionRepository = progressionRepository,
		dispatchers = TestDispatchersProvider(StandardTestDispatcher(scheduler)),
		challengeDatabase = challengeDatabase,
		engine = engine,
	)

	@Test
	@DisplayName("fills empty slots from the catalog with distinct types")
	fun `fills empty slots from the catalog with distinct types`() = runTest {
		coEvery { challengeDao.getActive(any()) } returns emptyList()
		val manager = makeManager(testScheduler)

		manager.awaitReady(mockContext)
		advanceUntilIdle()

		val active = manager.activeChallenges.value
		active shouldHaveSize ChallengeManager.MAX_CHALLENGE_COUNT
		active.map { it.entity.type }.toSet() shouldHaveSize ChallengeManager.MAX_CHALLENGE_COUNT
		// Every chosen type must come from the catalog.
		val catalogTypes = ChallengeCatalog.definitions.map { it.type }.toSet()
		active.forEach { catalogTypes shouldBe (catalogTypes + it.entity.type) }
	}

	@Test
	@DisplayName("requiredValue uses target × difficulty multipliers, not duration multiplier")
	fun `requiredValue uses target and difficulty multipliers not duration multiplier`() = runTest {
		coEvery { challengeDao.getActive(any()) } returns emptyList()
		// Force MEDIUM band so difficultyMult is 1.0 for the assertions below
		coEvery { historyDao.getRecentCompletionRate(any()) } returns 0.5
		val manager = makeManager(testScheduler)

		manager.awaitReady(mockContext)
		advanceUntilIdle()

		val active = manager.activeChallenges.value
		active.forEach { instance ->
			val def = ChallengeCatalog.byType(instance.entity.type)
			// New formula: requiredValue ∈ [defaultRequiredValue * minTargetMultiplier,
			// defaultRequiredValue * maxTargetMultiplier] at MEDIUM difficulty.
			// Independent of durationMult — that's the whole point of p2-8.
			val expectedMin = def.defaultRequiredValue * def.minTargetMultiplier
			val expectedMax = def.defaultRequiredValue * def.maxTargetMultiplier
			(instance.entity.requiredValue >= expectedMin) shouldBe true
			(instance.entity.requiredValue <= expectedMax) shouldBe true
		}
	}

	@Test
	@DisplayName("skips already-active types when filling new slots")
	fun `skips already-active types when filling new slots`() = runTest {
		// Pre-populate the DB with MAX-1 catalog types so exactly one slot remains and
		// the activator must pick from the other (untouched) catalog types.
		val catalogTypes = ChallengeCatalog.definitions.map { it.type }
		val occupiedTypes = catalogTypes.take(ChallengeManager.MAX_CHALLENGE_COUNT - 1)
		val untouchedTypes = catalogTypes - occupiedTypes.toSet()
		val now = 1_000L
		val preExisting = occupiedTypes.mapIndexed { idx, type ->
			ChallengeEntity(
				id = (100 + idx).toLong(),
				type = type,
				difficulty = ChallengeDifficulty.MEDIUM,
				startTime = now,
				endTime = now + 86_400_000L,
				requiredValue = 100.0,
				currentValue = 0.0,
				isCompleted = false,
			)
		}
		coEvery { challengeDao.getActive(any()) } returns preExisting

		val manager = makeManager(testScheduler)
		manager.awaitReady(mockContext)
		advanceUntilIdle()

		val active = manager.activeChallenges.value
		active shouldHaveSize ChallengeManager.MAX_CHALLENGE_COUNT

		val newlyActiveTypes = active.map { it.entity.type }.toSet() - occupiedTypes.toSet()
		newlyActiveTypes shouldHaveSize 1
		(newlyActiveTypes.first() in untouchedTypes) shouldBe true
	}

	@Test
	@DisplayName("returns no new activations when every catalog type is already active")
	fun `returns no new activations when every catalog type is already active`() = runTest {
		val now = 5_000L
		val preExisting = ChallengeCatalog.definitions.mapIndexed { idx, def ->
			ChallengeEntity(
				id = (200 + idx).toLong(),
				type = def.type,
				difficulty = ChallengeDifficulty.MEDIUM,
				startTime = now,
				endTime = now + 86_400_000L,
				requiredValue = def.defaultRequiredValue,
				currentValue = 0.0,
				isCompleted = false,
			)
		}
		coEvery { challengeDao.getActive(any()) } returns preExisting

		val manager = makeManager(testScheduler)
		manager.awaitReady(mockContext)
		advanceUntilIdle()

		val active = manager.activeChallenges.value
		// Already over MAX, so no insert should have happened.
		inserted shouldHaveSize 0
		active shouldHaveSize preExisting.size
		active.map { it.entity.type }.toSet() shouldBe ChallengeCatalog.definitions.map { it.type }.toSet()
	}

	@Test
	@DisplayName("requiredValue scales with difficulty band and target randomness, decoupled from duration")
	fun `requiredValue scales with difficulty band and target randomness, decoupled from duration`() = runTest {
		coEvery { challengeDao.getActive(any()) } returns emptyList()
		// Force completion rate to map to MEDIUM (0.4 ≤ rate < 0.6 → MEDIUM)
		coEvery { historyDao.getRecentCompletionRate(any()) } returns 0.5
		val manager = makeManager(testScheduler)

		manager.awaitReady(mockContext)
		advanceUntilIdle()

		val active = manager.activeChallenges.value
		active.forEach { instance ->
			val def = ChallengeCatalog.byType(instance.entity.type)
			val difficultyMult = def.difficultyTargetMultiplier(instance.entity.difficulty)
			val expectedMin = def.defaultRequiredValue * def.minTargetMultiplier * difficultyMult
			val expectedMax = def.defaultRequiredValue * def.maxTargetMultiplier * difficultyMult
			// requiredValue must fall in the [min,max] band defined by the random multiplier × difficulty multiplier.
			(instance.entity.requiredValue >= expectedMin) shouldBe true
			(instance.entity.requiredValue <= expectedMax) shouldBe true
		}
	}

	@Test
	@DisplayName("Speed difficulty curve stays in [0.7, 1.45] for all bands")
	fun `Speed difficulty curve stays in 0_7 to 1_45 for all bands`() {
		val def = ChallengeCatalog.byType(ChallengeType.Speed)
		ChallengeDifficulty.values().forEach { band ->
			val m = def.difficultyTargetMultiplier(band)
			(m in 0.7..1.45) shouldBe true
		}
		def.difficultyTargetMultiplier(ChallengeDifficulty.MEDIUM) shouldBe 1.0
	}

	@Test
	@DisplayName("Consistency difficulty curve caps at 1.4 so a 10-day window is never impossible")
	fun `Consistency difficulty curve caps at 1_4`() {
		val def = ChallengeCatalog.byType(ChallengeType.Consistency)
		val veryHard = def.difficultyTargetMultiplier(ChallengeDifficulty.VERY_HARD)
		(veryHard <= 1.4) shouldBe true
		def.difficultyTargetMultiplier(ChallengeDifficulty.MEDIUM) shouldBe 1.0
	}

	@Test
	@DisplayName("default difficulty curve returns 1.0 at MEDIUM for non-customized types")
	fun `default difficulty curve returns 1_0 at MEDIUM for non-customized types`() {
		// Step, WalkDistance, ActiveTime use the default curve. Explorer/Speed/Consistency override.
		listOf(ChallengeType.Step, ChallengeType.WalkDistance, ChallengeType.ActiveTime).forEach { type ->
			val def = ChallengeCatalog.byType(type)
			def.difficultyTargetMultiplier(ChallengeDifficulty.MEDIUM) shouldBe 1.0
		}
	}

	@Test
	@DisplayName("activated entities expose a non-null catalog-backed processor for UI")
	fun `activated entities expose a non-null catalog-backed processor for UI`() = runTest {
		coEvery { challengeDao.getActive(any()) } returns emptyList()
		val manager = makeManager(testScheduler)

		manager.awaitReady(mockContext)
		advanceUntilIdle()

		manager.activeChallenges.value.forEach { instance ->
			instance.processor.shouldNotBeNull()
			val def = ChallengeCatalog.byType(instance.entity.type)
			instance.processor.titleRes shouldBe def.titleRes
		}
	}

	@Test
	@DisplayName("only catalog types are ever activated")
	fun `only catalog types are ever activated`() = runTest {
		coEvery { challengeDao.getActive(any()) } returns emptyList()
		val manager = makeManager(testScheduler)

		manager.awaitReady(mockContext)
		advanceUntilIdle()

		val catalogTypes: Set<ChallengeType> = ChallengeCatalog.definitions.map { it.type }.toSet()
		manager.activeChallenges.value.forEach {
			(it.entity.type in catalogTypes) shouldBe true
		}
	}
}
