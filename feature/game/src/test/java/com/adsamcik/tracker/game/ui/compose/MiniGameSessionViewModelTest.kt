package com.adsamcik.tracker.game.ui.compose

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsState
import com.adsamcik.tracker.game.goals.settings.RememberedGameSetup
import com.adsamcik.tracker.game.minigame.MiniGame
import com.adsamcik.tracker.game.minigame.MiniGameConfigurations
import com.adsamcik.tracker.game.minigame.MiniGameDifficulty
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGameGoalUnit
import com.adsamcik.tracker.game.minigame.MiniGamePersonalBest
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameRegistry
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameSignal
import com.adsamcik.tracker.game.minigame.MiniGameSnapshot
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.game.minigame.OutrunConfiguration
import com.adsamcik.tracker.game.minigame.OutrunGoal
import com.adsamcik.tracker.game.session.GameSessionCommand
import com.adsamcik.tracker.game.session.GameSessionController
import com.adsamcik.tracker.game.session.GameSessionFailureReason
import com.adsamcik.tracker.game.session.GameSessionId
import com.adsamcik.tracker.game.session.GameSessionResult
import com.adsamcik.tracker.game.session.GameSessionState
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * Unit tests for the rewritten [MiniGameSessionViewModel].
 *
 * The ViewModel is now a setup/permission/controller-observer only: it owns no
 * session, GPS collector, timer, persistence, or lifecycle behaviour. These
 * tests verify setup options, remember-on-start, precise-location gating, the
 * non-blocking notification prompt, controller command delegation, and the
 * projection of [GameSessionController] state into [MiniGameSessionUiState].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class MiniGameSessionViewModelTest {

	private lateinit var application: Application
	private lateinit var registry: MiniGameRegistry
	private lateinit var controller: FakeGameSessionController
	private lateinit var settings: FakeGoalsSettingsRepository
	private lateinit var dispatcher: TestDispatcher

	@Before
	fun setUp() {
		application = ApplicationProvider.getApplicationContext()
		denyLocation()
		denyNotifications()
		registry = MiniGameRegistry(setOf(FakeMiniGame(OutrunConfiguration.GAME_ID)))
		controller = FakeGameSessionController()
		settings = FakeGoalsSettingsRepository()
		dispatcher = StandardTestDispatcher()
		Dispatchers.setMain(dispatcher)
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun initialState_exposesGoalAndDifficultyOptions() = runTest(dispatcher) {
		val vm = newViewModel()
		collecting(vm) {
			advanceUntilIdle()
			val setup = vm.uiState.value.shouldBeInstanceOf<MiniGameSessionUiState.Setup>()
			setup.options.goalUnit shouldBe MiniGameGoalUnit.METERS
			setup.options.goalValues shouldBe listOf(25, 50, 100)
			setup.options.difficulties shouldBe listOf(
				MiniGameDifficulty.EASY,
				MiniGameDifficulty.NORMAL,
				MiniGameDifficulty.HARD,
			)
			setup.selection.goalValue shouldBe 50
			setup.selection.difficulty shouldBe MiniGameDifficulty.NORMAL
			setup.rememberSetup shouldBe false
		}
	}

	@Test
	fun selectGoalAndDifficulty_updateSelection() = runTest(dispatcher) {
		val vm = newViewModel()
		collecting(vm) {
			advanceUntilIdle()
			vm.selectGoal(100)
			vm.selectDifficulty(MiniGameDifficulty.HARD)
			advanceUntilIdle()
			val setup = vm.uiState.value.shouldBeInstanceOf<MiniGameSessionUiState.Setup>()
			setup.selection.goalValue shouldBe 100
			setup.selection.difficulty shouldBe MiniGameDifficulty.HARD
		}
	}

	@Test
	fun remembersSetup_onStart_whenEnabled() = runTest(dispatcher) {
		grantLocation()
		grantNotifications()
		val vm = newViewModel()
		collecting(vm) {
			advanceUntilIdle()
			vm.setRememberSetup(true)
			vm.selectGoal(100)
			vm.selectDifficulty(MiniGameDifficulty.HARD)
			advanceUntilIdle()

			vm.start()
			advanceUntilIdle()

			settings.data.first().rememberedOutrunSetup shouldBe
				RememberedGameSetup(goalValue = 100, difficulty = "HARD")
			controller.commands shouldContain GameSessionCommand.Start(
				OutrunConfiguration(OutrunGoal.METERS_100, MiniGameDifficulty.HARD),
			)
		}
	}

	@Test
	fun doesNotRememberSetup_whenDisabled() = runTest(dispatcher) {
		grantLocation()
		grantNotifications()
		val vm = newViewModel()
		collecting(vm) {
			advanceUntilIdle()
			vm.selectGoal(100)
			advanceUntilIdle()

			vm.start()
			advanceUntilIdle()

			settings.data.first().rememberedOutrunSetup.shouldBeNull()
			controller.commands shouldHaveSize 1
		}
	}

	@Test
	fun seedsSelection_fromRememberedSetup() = runTest(dispatcher) {
		settings = FakeGoalsSettingsRepository(
			GoalsSettingsState.defaults().copy(
				rememberLastSetup = true,
				rememberedOutrunSetup = RememberedGameSetup(goalValue = 25, difficulty = "EASY"),
			),
		)
		val vm = newViewModel()
		collecting(vm) {
			advanceUntilIdle()
			val setup = vm.uiState.value.shouldBeInstanceOf<MiniGameSessionUiState.Setup>()
			setup.selection.goalValue shouldBe 25
			setup.selection.difficulty shouldBe MiniGameDifficulty.EASY
			setup.rememberSetup shouldBe true
		}
	}

	@Test
	fun start_withoutLocationPermission_showsLocationPermission() = runTest(dispatcher) {
		val vm = newViewModel()
		collecting(vm) {
			advanceUntilIdle()
			vm.start()
			advanceUntilIdle()

			val state = vm.uiState.value
				.shouldBeInstanceOf<MiniGameSessionUiState.LocationPermission>()
			state.denied shouldBe false
			controller.commands.shouldHaveSize(0)
		}
	}

	@Test
	fun onLocationPermissionResult_denied_marksDenied() = runTest(dispatcher) {
		val vm = newViewModel()
		collecting(vm) {
			advanceUntilIdle()
			vm.start()
			advanceUntilIdle()

			vm.onLocationPermissionResult(false)
			advanceUntilIdle()

			val state = vm.uiState.value
				.shouldBeInstanceOf<MiniGameSessionUiState.LocationPermission>()
			state.denied shouldBe true
			controller.commands.shouldHaveSize(0)
		}
	}

	@Test
	fun notificationPermission_isRequestedButNeverBlocksTheRun() = runTest(dispatcher) {
		grantLocation()
		// Notifications remain denied (default on SDK 34).
		val vm = newViewModel()
		collecting(vm) {
			advanceUntilIdle()
			vm.start()
			advanceUntilIdle()

			vm.uiState.value.shouldBeInstanceOf<MiniGameSessionUiState.NotificationPermission>()
			controller.commands.shouldHaveSize(0)

			// Deny notifications: the run must still start.
			vm.onNotificationPermissionResult(false)
			advanceUntilIdle()

			controller.commands shouldHaveSize 1
			controller.commands.first().shouldBeInstanceOf<GameSessionCommand.Start>()
		}
	}

	@Test
	fun start_withAllPermissions_startsControllerImmediately() = runTest(dispatcher) {
		grantLocation()
		grantNotifications()
		val vm = newViewModel()
		collecting(vm) {
			advanceUntilIdle()
			vm.start()
			advanceUntilIdle()

			controller.commands shouldHaveSize 1
			controller.commands.first().shouldBeInstanceOf<GameSessionCommand.Start>()
		}
	}

	@Test
	fun pauseResumeFinish_delegateToController() = runTest(dispatcher) {
		val vm = newViewModel()
		collecting(vm) {
			advanceUntilIdle()
			vm.pause()
			vm.resume()
			vm.finish()
			advanceUntilIdle()

			controller.commands shouldBe listOf(
				GameSessionCommand.Pause,
				GameSessionCommand.Resume,
				GameSessionCommand.Finish,
			)
		}
	}

	@Test
	fun activeSnapshotWhileAcquiring_mapsToAcquiring() = runTest(dispatcher) {
		val vm = newViewModel()
		collecting(vm) {
			controller.set(
				GameSessionState.Active(
					sessionId = SESSION_ID,
					configuration = CONFIG,
					snapshot = snapshot(MiniGamePhase.ACQUIRING_SIGNAL),
				),
			)
			advanceUntilIdle()
			vm.uiState.value.shouldBeInstanceOf<MiniGameSessionUiState.Acquiring>()
		}
	}

	@Test
	fun activeSnapshot_mapsToActive() = runTest(dispatcher) {
		val vm = newViewModel()
		collecting(vm) {
			controller.set(
				GameSessionState.Active(
					sessionId = SESSION_ID,
					configuration = CONFIG,
					snapshot = snapshot(MiniGamePhase.ACTIVE),
				),
			)
			advanceUntilIdle()
			vm.uiState.value.shouldBeInstanceOf<MiniGameSessionUiState.Active>()
		}
	}

	@Test
	fun pausedFinishingStates_mapThrough() = runTest(dispatcher) {
		val vm = newViewModel()
		collecting(vm) {
			controller.set(
				GameSessionState.Paused(SESSION_ID, CONFIG, snapshot(MiniGamePhase.PAUSED)),
			)
			advanceUntilIdle()
			vm.uiState.value.shouldBeInstanceOf<MiniGameSessionUiState.Paused>()

			controller.set(
				GameSessionState.Finishing(SESSION_ID, CONFIG, snapshot(MiniGamePhase.ACTIVE)),
			)
			advanceUntilIdle()
			vm.uiState.value.shouldBeInstanceOf<MiniGameSessionUiState.Finishing>()
		}
	}

	@Test
	fun finishedState_mapsToFinished_andChangeSetupReturnsToSetup() = runTest(dispatcher) {
		val vm = newViewModel()
		collecting(vm) {
			controller.set(GameSessionState.Finished(RESULT))
			advanceUntilIdle()
			vm.uiState.value.shouldBeInstanceOf<MiniGameSessionUiState.Finished>()

			vm.changeSetup()
			advanceUntilIdle()
			vm.uiState.value.shouldBeInstanceOf<MiniGameSessionUiState.Setup>()
		}
	}

	@Test
	fun failedState_mapsToFailed_withoutSuccessScore() = runTest(dispatcher) {
		val vm = newViewModel()
		collecting(vm) {
			controller.set(
				GameSessionState.Failed(CONFIG, GameSessionFailureReason.LOCATION_UNAVAILABLE),
			)
			advanceUntilIdle()
			val failed = vm.uiState.value.shouldBeInstanceOf<MiniGameSessionUiState.Failed>()
			failed.reason shouldBe GameSessionFailureReason.LOCATION_UNAVAILABLE
		}
	}

	@Test
	fun playAgain_startsControllerWithGivenConfiguration() = runTest(dispatcher) {
		grantLocation()
		grantNotifications()
		val vm = newViewModel()
		collecting(vm) {
			advanceUntilIdle()
			vm.playAgain(CONFIG)
			advanceUntilIdle()

			controller.commands shouldContain GameSessionCommand.Start(CONFIG)
		}
	}

	// --- helpers -------------------------------------------------------------

	private fun newViewModel(): MiniGameSessionViewModel = MiniGameSessionViewModel(
		savedStateHandle = SavedStateHandle(
			mapOf(MINIGAME_SESSION_GAME_ID_ARG to OutrunConfiguration.GAME_ID),
		),
		application = application,
		registry = registry,
		controller = controller,
		settingsRepository = settings,
	)

	private fun grantLocation() {
		shadowOf(application).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
	}

	private fun denyLocation() {
		shadowOf(application).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
		shadowOf(application).denyPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
	}

	private fun grantNotifications() {
		shadowOf(application).grantPermissions(POST_NOTIFICATIONS)
	}

	private fun denyNotifications() {
		shadowOf(application).denyPermissions(POST_NOTIFICATIONS)
	}

	private companion object {
		private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
		private val CONFIG = MiniGameConfigurations.DEFAULT_OUTRUN
		private val SESSION_ID = GameSessionId("session-1")
		private val RESULT = GameSessionResult(
			sessionId = SESSION_ID,
			configuration = CONFIG,
			finalScore = 42.0,
			pointsAwarded = 7,
			completedAtMs = 1_000L,
			completionOutcome =
				com.adsamcik.tracker.game.minigame.MiniGameCompletionOutcome.FirstRun,
		)

		private fun snapshot(phase: MiniGamePhase): MiniGameSnapshot = MiniGameSnapshot(
			phase = phase,
			signal = MiniGameSignal.UNKNOWN,
			elapsedActiveTimeMs = 0L,
			goalProgress = MiniGameGoalProgress.NotConfigured,
			personalBest = MiniGamePersonalBest.compare(0.0, null),
			latestFeedback = null,
			visualPayload = MiniGameVisualPayload.Pending,
		)
	}
}

/**
 * Runs [block] while a background collector keeps the ViewModel's shared
 * `uiState` active.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun kotlinx.coroutines.test.TestScope.collecting(
	vm: MiniGameSessionViewModel,
	block: suspend () -> Unit,
) {
	val job = backgroundScope.launchCollector(vm)
	block()
	job.cancel()
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun CoroutineScope.launchCollector(vm: MiniGameSessionViewModel) =
	launch { vm.uiState.collect { } }

private class FakeGameSessionController : GameSessionController {
	private val mutableState = MutableStateFlow<GameSessionState>(GameSessionState.Idle)
	override val state: StateFlow<GameSessionState> = mutableState
	val commands = mutableListOf<GameSessionCommand>()

	fun set(newState: GameSessionState) {
		mutableState.value = newState
	}

	override fun start(configuration: com.adsamcik.tracker.game.minigame.MiniGameConfiguration) {
		commands += GameSessionCommand.Start(configuration)
	}

	override fun pause() {
		commands += GameSessionCommand.Pause
	}

	override fun resume() {
		commands += GameSessionCommand.Resume
	}

	override fun finish() {
		commands += GameSessionCommand.Finish
	}
}

private class FakeGoalsSettingsRepository(
	initial: GoalsSettingsState = GoalsSettingsState.defaults(),
) : GoalsSettingsRepository {
	private val state = MutableStateFlow(initial)
	override val data: Flow<GoalsSettingsState> = state

	override suspend fun setRememberLastSetup(enabled: Boolean) {
		state.update {
			if (enabled) {
				it.copy(rememberLastSetup = true)
			} else {
				it.copy(
					rememberLastSetup = false,
					rememberedOutrunSetup = null,
					rememberedTerritorySetup = null,
					rememberedZenSetup = null,
					rememberedFuseRunSetup = null,
					rememberedSwitchbackSetup = null,
				)
			}
		}
	}

	override suspend fun setRememberedOutrunSetup(goalMeters: Int, difficulty: String?) {
		state.update {
			if (!it.rememberLastSetup) {
				it.copy(rememberedOutrunSetup = null)
			} else {
				it.copy(rememberedOutrunSetup = RememberedGameSetup(goalMeters, difficulty))
			}
		}
	}

	override suspend fun setRememberedTerritorySetup(goalCells: Int, difficulty: String?) {
		state.update {
			if (!it.rememberLastSetup) {
				it.copy(rememberedTerritorySetup = null)
			} else {
				it.copy(rememberedTerritorySetup = RememberedGameSetup(goalCells, difficulty))
			}
		}
	}

	override suspend fun setRememberedZenSetup(goalMinutes: Int, difficulty: String?) {
		state.update {
			if (!it.rememberLastSetup) {
				it.copy(rememberedZenSetup = null)
			} else {
				it.copy(rememberedZenSetup = RememberedGameSetup(goalMinutes, difficulty))
			}
		}
	}

	override suspend fun setRememberedFuseRunSetup(goalCharges: Int, difficulty: String?) {
		state.update {
			if (!it.rememberLastSetup) {
				it.copy(rememberedFuseRunSetup = null)
			} else {
				it.copy(rememberedFuseRunSetup = RememberedGameSetup(goalCharges, difficulty))
			}
		}
	}

	override suspend fun setRememberedSwitchbackSetup(goalTurns: Int, difficulty: String?) {
		state.update {
			if (!it.rememberLastSetup) {
				it.copy(rememberedSwitchbackSetup = null)
			} else {
				it.copy(rememberedSwitchbackSetup = RememberedGameSetup(goalTurns, difficulty))
			}
		}
	}

	override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
	override suspend fun setDailyStepGoal(steps: Int) = Unit
	override suspend fun setWeeklyStepGoal(steps: Int) = Unit
	override suspend fun setWeeklyDailyLimit(fraction: Float) = Unit
	override suspend fun setGameHapticsEnabled(enabled: Boolean) = Unit
	override suspend fun setQuietCoachingEnabled(enabled: Boolean) = Unit
	override suspend fun setDailyGoalReachedPeriod(period: Int?) = Unit
	override suspend fun setWeeklyGoalReachedPeriod(period: Int?) = Unit
}

private class FakeMiniGame(override val id: String) : MiniGame {
	override val nameRes: Int = android.R.string.ok
	override val descriptionRes: Int = android.R.string.ok
	override val unlockLevel: Int = 1
	override fun createSession(): MiniGameSession =
		throw UnsupportedOperationException("Session creation is owned by the service")
}
