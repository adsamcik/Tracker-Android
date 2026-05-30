package com.adsamcik.tracker.game.ui.compose

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.adsamcik.tracker.game.minigame.MiniGame
import com.adsamcik.tracker.game.minigame.MiniGameRegistry
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameState
import com.adsamcik.tracker.game.minigame.location.MiniGameLocationSample
import com.adsamcik.tracker.game.minigame.location.MiniGameLocationSource
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.game.repository.PlayerProfileUi
import com.adsamcik.tracker.game.repository.StepsSummaryData
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity
import com.adsamcik.tracker.testing.TestDispatchersProvider
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for [MiniGameSessionViewModel].
 *
 * Uses Robolectric so we can grant ACCESS_FINE_LOCATION via the shadow
 * permission system — the VM gates start() on Context.hasLocationPermission,
 * which cannot be faked without a real Application.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class MiniGameSessionViewModelTest {

	private lateinit var application: Application
	private lateinit var locationSource: ControllableLocationSource
	private lateinit var scoreDao: RecordingScoreDao
	private lateinit var gameRepository: RecordingGameRepository
	private lateinit var registry: MiniGameRegistry
	private lateinit var dispatcher: TestDispatcher
	private lateinit var dispatchers: TestDispatchersProvider

	@Before
	fun setUp() {
		application = ApplicationProvider.getApplicationContext()
		shadowOf(application).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
		shadowOf(application).denyPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)

		locationSource = ControllableLocationSource()
		scoreDao = RecordingScoreDao()
		gameRepository = RecordingGameRepository()
		registry = MiniGameRegistry(setOf(FakeMiniGame(GAME_ID)))
		dispatcher = StandardTestDispatcher()
		dispatchers = TestDispatchersProvider(dispatcher)
		Dispatchers.setMain(dispatcher)
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun start_withoutPermission_emitsPermissionNeeded() = runTest(dispatcher) {
		val vm = newViewModel()

		vm.start()
		advanceUntilIdle()

		vm.uiState.value.shouldBeInstanceOf<MiniGameUiState.PermissionNeeded>()
		locationSource.subscribers shouldBe 0
	}

	@Test
	fun start_withPermission_emitsActiveAndForwardsSamples() = runTest(dispatcher) {
		grantLocationPermission()
		val vm = newViewModel()

		vm.uiState.test {
			awaitItem().shouldBeInstanceOf<MiniGameUiState.Idle>()
			vm.start()
			advanceUntilIdle()
			awaitItem().shouldBeInstanceOf<MiniGameUiState.Active>()

			locationSource.emit(SAMPLE)
			advanceUntilIdle()
			val active = awaitItem().shouldBeInstanceOf<MiniGameUiState.Active>()
			active.score shouldBe SAMPLE.speedMps.toDouble()
			active.state shouldBe MiniGameState.RUNNING
		}
	}

	@Test
	fun stop_persistsScoreAndCreditsXp_thenEmitsFinished() = runTest(dispatcher) {
		grantLocationPermission()
		val vm = newViewModel()

		vm.start()
		advanceUntilIdle()
		locationSource.emit(SAMPLE)
		advanceUntilIdle()

		vm.stop()
		advanceUntilIdle()

		scoreDao.inserted shouldHaveSize 1
		val row = scoreDao.inserted.single()
		row.gameId shouldBe GAME_ID
		row.score shouldBe SAMPLE.speedMps.toDouble()
		row.xpAwarded shouldBe FakeMiniGame.XP

		gameRepository.creditedXp shouldHaveSize 1
		val credit = gameRepository.creditedXp.single()
		credit.first shouldBe GAME_ID
		credit.second shouldBe FakeMiniGame.XP

		val finished = vm.uiState.value.shouldBeInstanceOf<MiniGameUiState.Finished>()
		finished.finalScore shouldBe SAMPLE.speedMps.toDouble()
		finished.xpEarned shouldBe FakeMiniGame.XP
	}

	@Test
	fun stop_withoutActiveFrame_returnsToIdleWithoutWriting() = runTest(dispatcher) {
		grantLocationPermission()
		val vm = newViewModel()

		vm.stop()
		advanceUntilIdle()

		scoreDao.inserted.shouldHaveSize(0)
		gameRepository.creditedXp.shouldHaveSize(0)
		vm.uiState.value.shouldBeInstanceOf<MiniGameUiState.Idle>()
	}

	@Test
	fun reset_returnsToIdleWithFreshSession() = runTest(dispatcher) {
		grantLocationPermission()
		val vm = newViewModel()
		vm.start()
		advanceUntilIdle()
		locationSource.emit(SAMPLE)
		advanceUntilIdle()
		vm.stop()
		advanceUntilIdle()

		vm.reset()
		advanceUntilIdle()
		vm.uiState.value.shouldBeInstanceOf<MiniGameUiState.Idle>()
	}

	@Test
	fun onPermissionResult_granted_autoStartsSession() = runTest(dispatcher) {
		val vm = newViewModel()
		vm.start()
		advanceUntilIdle()
		vm.uiState.value.shouldBeInstanceOf<MiniGameUiState.PermissionNeeded>()

		grantLocationPermission()
		vm.onPermissionResult(true)
		advanceUntilIdle()

		vm.uiState.value.shouldBeInstanceOf<MiniGameUiState.Active>()
		locationSource.subscribers shouldBe 1
	}

	@Test
	fun onPermissionResult_denied_staysInPermissionNeeded() = runTest(dispatcher) {
		val vm = newViewModel()
		vm.onPermissionResult(false)
		advanceUntilIdle()
		vm.uiState.value.shouldBeInstanceOf<MiniGameUiState.PermissionNeeded>()
	}

	private fun newViewModel(): MiniGameSessionViewModel = MiniGameSessionViewModel(
		savedStateHandle = SavedStateHandle(mapOf(MINIGAME_SESSION_GAME_ID_ARG to GAME_ID)),
		application = application,
		miniGameRegistry = registry,
		scoreDao = scoreDao,
		gameRepository = gameRepository,
		locationSource = locationSource,
		dispatchers = dispatchers,
	)

	private fun grantLocationPermission() {
		shadowOf(application).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
	}

	private companion object {
		private const val GAME_ID = "fake-game"
		private val SAMPLE = MiniGameLocationSample(
			latitude = 51.0,
			longitude = 14.0,
			speedMps = 7.5f,
			accuracyM = 5f,
			timestampMs = 1_000L,
		)
	}
}

/**
 * Minimal MiniGame whose session reflects the last-seen speed as its score so
 * tests can deterministically assert what made it through the pipeline.
 */
private class FakeMiniGame(override val id: String) : MiniGame {
	override val nameRes: Int = android.R.string.ok
	override val descriptionRes: Int = android.R.string.ok
	override val unlockLevel: Int = 1
	override fun createSession(): MiniGameSession = FakeSession()

	companion object { const val XP = 42 }

	private class FakeSession : MiniGameSession() {
		override var state: MiniGameState = MiniGameState.IDLE
			private set
		override var score: Double = 0.0
			private set
		override var statusText: String = ""
			private set

		override fun onLocationUpdate(
			latitude: Double,
			longitude: Double,
			speedMps: Float,
			accuracyM: Float,
			timestampMs: Long,
		) {
			state = MiniGameState.RUNNING
			score = speedMps.toDouble()
			statusText = "running"
		}

		override fun onSessionEnd() { state = MiniGameState.FINISHED }
		override fun calculateXp(): Int = XP
	}
}

private class ControllableLocationSource : MiniGameLocationSource {
	private val sharedFlow = MutableSharedFlow<MiniGameLocationSample>(extraBufferCapacity = 16)
	@Volatile
	var subscribers: Int = 0
		private set

	override fun samples(): Flow<MiniGameLocationSample> = flow {
		subscribers += 1
		try {
			sharedFlow.collect { emit(it) }
		} finally {
			subscribers -= 1
		}
	}

	suspend fun emit(sample: MiniGameLocationSample) {
		sharedFlow.emit(sample)
	}
}

private class RecordingScoreDao : MiniGameScoreDao {
	val inserted = mutableListOf<MiniGameScoreEntity>()
	private val byGame = mutableMapOf<String, MutableStateFlow<List<MiniGameScoreEntity>>>()
	private val recent = MutableStateFlow<List<MiniGameScoreEntity>>(emptyList())

	override fun getScoresByGame(gameId: String): Flow<List<MiniGameScoreEntity>> =
		byGame.getOrPut(gameId) { MutableStateFlow(emptyList()) }.asStateFlow()

	override fun getHighScore(gameId: String): Double? =
		byGame[gameId]?.value?.maxOfOrNull { it.score }

	override fun getRecent(limit: Int): Flow<List<MiniGameScoreEntity>> = recent.asStateFlow()

	override fun deleteAll() {
		inserted.clear()
		byGame.clear()
		recent.value = emptyList()
	}

	override suspend fun insert(obj: MiniGameScoreEntity): Long {
		inserted += obj
		val list = byGame.getOrPut(obj.gameId) { MutableStateFlow(emptyList()) }
		list.value = list.value + obj
		recent.value = (recent.value + obj).takeLast(200)
		return inserted.size.toLong()
	}

	override suspend fun insert(obj: Collection<MiniGameScoreEntity>): List<Long> =
		obj.map { insert(it) }

	override suspend fun update(obj: MiniGameScoreEntity) = Unit
	override suspend fun update(obj: Collection<MiniGameScoreEntity>) = Unit
	override suspend fun delete(obj: MiniGameScoreEntity) = Unit
	override suspend fun delete(obj: Collection<MiniGameScoreEntity>) = Unit
}

private class RecordingGameRepository : GameRepository {
	val creditedXp = mutableListOf<Triple<String, Int, Long>>()
	override fun getPointsToday(): Flow<Int> = flowOf(0)
	override fun getStepsSummary(): StateFlow<StepsSummaryData?> = MutableStateFlow(null).asStateFlow()
	override fun getPlayerProfile(): Flow<PlayerProfileUi?> = flowOf(null)
	override suspend fun creditMiniGameXp(gameId: String, xp: Int, earnedAtMs: Long) {
		creditedXp += Triple(gameId, xp, earnedAtMs)
	}
}
