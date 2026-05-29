package com.adsamcik.tracker.game.challenge

import android.content.Context
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ChallengeDatabaseFold
import com.adsamcik.tracker.shared.base.database.ChallengeDatabaseFoldResult
import com.adsamcik.tracker.shared.base.database.dao.ChallengeDao
import com.adsamcik.tracker.game.challenge.engine.ChallengeEngine
import com.adsamcik.tracker.game.challenge.progression.ExpiryResult
import com.adsamcik.tracker.game.challenge.progression.ProgressionRepository
import com.adsamcik.tracker.game.challenge.worker.ChallengeExpiredWorker
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ChallengeManager state machine")
class ChallengeManagerStateMachineTest {

	private lateinit var progressionRepository: ProgressionRepository
	private lateinit var challengeDatabase: AppDatabase
	private lateinit var challengeDao: ChallengeDao
	private lateinit var engine: ChallengeEngine
	private lateinit var challengeDatabaseFold: ChallengeDatabaseFold
	private lateinit var mockContext: Context

	@BeforeEach
	fun setUpMocks() {
		mockContext = mockk(relaxed = true)
		progressionRepository = mockk(relaxed = true)
		challengeDao = mockk(relaxed = true)
		challengeDatabase = mockk(relaxed = true) {
			every { challengeDao() } returns challengeDao
		}
		engine = mockk(relaxed = true)
		challengeDatabaseFold = mockk(relaxed = true)
		coEvery { challengeDatabaseFold.awaitComplete() } returns ChallengeDatabaseFoldResult.NO_LEGACY_DATABASE
		coEvery { progressionRepository.onActiveChallengesExpiredAt(any()) } returns ExpiryResult(0, false, false, 0)
		// Activation now goes through the catalog (post p2-4), so the manager fills
		// empty slots and schedules a WorkManager job. Stub the static schedule call
		// so tests don't need a real WorkManager.
		mockkObject(ChallengeExpiredWorker.Companion)
		every { ChallengeExpiredWorker.schedule(any(), any()) } just runs
	}

	@org.junit.jupiter.api.AfterEach
	fun tearDown() {
		unmockkObject(ChallengeExpiredWorker.Companion)
	}

	private fun makeManager(dispatcher: CoroutineDispatcher) = ChallengeManager(
		progressionRepository = progressionRepository,
		dispatchers = TestDispatchersProvider(dispatcher),
		challengeDatabase = challengeDatabase,
		engine = engine,
		challengeDatabaseFold = challengeDatabaseFold,
	)

	private fun makeSession() = TrackerSession(
		id = 1L,
		start = 1_000L,
		end = 2_000L,
		isUserInitiated = true,
		collections = 1,
		distanceInM = 100f,
		steps = 0,
	)

	@Test
	@DisplayName("awaitReady loads from DB once even under concurrent callers")
	fun `awaitReady loads from DB once even under concurrent callers`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val manager = makeManager(dispatcher)

		val callCount = AtomicInteger(0)
		coEvery { challengeDao.getActive(any()) } coAnswers {
			callCount.incrementAndGet()
			emptyList()
		}

		// Launch 5 concurrent awaitReady calls
		val jobs = (1..5).map {
			launch { manager.awaitReady(mockContext) }
		}
		advanceUntilIdle()
		jobs.forEach { it.join() }

		callCount.get() shouldBe 1
	}

	@Test
	@DisplayName("processSession blocks until awaitReady completes")
	fun `processSession blocks until awaitReady completes`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val manager = makeManager(dispatcher)

		val gate = CompletableDeferred<Unit>()
		coEvery { challengeDao.getActive(any()) } coAnswers {
			gate.await()
			emptyList()
		}

		var processSessionCompleted = false
		val job = launch {
			manager.processSession(mockContext, makeSession()) {}
			processSessionCompleted = true
		}

		// Advance until the coroutine is waiting on gate.await inside the DAO
		advanceUntilIdle()
		processSessionCompleted shouldBe false

		// Release the gate to unblock the DAO
		gate.complete(Unit)
		advanceUntilIdle()
		job.join()

		processSessionCompleted shouldBe true
	}

	@Test
	@DisplayName("failed initialization transitions to Failed; next awaitReady re-attempts")
	fun `failed initialization transitions to Failed and re-attempts on next awaitReady`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val manager = makeManager(dispatcher)

		var callCount = 0
		coEvery { challengeDao.getActive(any()) } coAnswers {
			callCount++
			if (callCount == 1) throw IOException("DB error on first attempt")
			emptyList()
		}

		// First call: DAO throws → state transitions to Failed → awaitReady should throw
		var caughtError: Throwable? = null
		try {
			manager.awaitReady(mockContext)
		} catch (e: IOException) {
			caughtError = e
		}
		advanceUntilIdle()
		caughtError shouldNotBe null

		// Second call: DAO succeeds → state transitions to Ready → awaitReady returns normally
		var secondCallError: Throwable? = null
		try {
			manager.awaitReady(mockContext)
		} catch (e: Throwable) {
			secondCallError = e
		}
		advanceUntilIdle()
		secondCallError shouldBe null

		callCount shouldBe 2
	}

	@Test
	@DisplayName("subsequent awaitReady calls after Ready return immediately without hitting DB")
	fun `subsequent awaitReady calls after Ready return immediately without hitting DB`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val manager = makeManager(dispatcher)

		coEvery { challengeDao.getActive(any()) } returns emptyList()

		// First call loads from DB
		manager.awaitReady(mockContext)
		advanceUntilIdle()

		// Second and third calls should be instant (state is Ready)
		manager.awaitReady(mockContext)
		manager.awaitReady(mockContext)
		advanceUntilIdle()

		// DB should only be queried once across all calls
		coVerify(exactly = 1) { challengeDao.getActive(any()) }
	}

	@Test
	@DisplayName("awaitReady waits for challenge database fold")
	fun `awaitReady waits for challenge database fold`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val manager = makeManager(dispatcher)

		val gate = CompletableDeferred<Unit>()
		coEvery { challengeDatabaseFold.awaitComplete() } coAnswers {
			gate.await()
			ChallengeDatabaseFoldResult.NO_LEGACY_DATABASE
		}
		coEvery { challengeDao.getActive(any()) } returns emptyList()

		var awaitReadyCompleted = false
		val job = launch {
			manager.awaitReady(mockContext)
			awaitReadyCompleted = true
		}

		advanceUntilIdle()
		awaitReadyCompleted shouldBe false
		coVerify(exactly = 0) { challengeDao.getActive(any()) }

		gate.complete(Unit)
		advanceUntilIdle()
		job.join()

		awaitReadyCompleted shouldBe true
		coVerify(exactly = 1) { challengeDao.getActive(any()) }
	}

	@Test
	@DisplayName("checkExpiredChallenges calls awaitReady before checking expiry")
	fun `checkExpiredChallenges calls awaitReady before checking expiry`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val manager = makeManager(dispatcher)

		val gate = CompletableDeferred<Unit>()
		coEvery { challengeDao.getActive(any()) } coAnswers {
			gate.await()
			emptyList()
		}

		var checkCompleted = false
		val job = launch {
			manager.checkExpiredChallenges(mockContext)
			checkCompleted = true
		}

		advanceUntilIdle()
		checkCompleted shouldBe false

		gate.complete(Unit)
		advanceUntilIdle()
		job.join()

		checkCompleted shouldBe true
	}
}
