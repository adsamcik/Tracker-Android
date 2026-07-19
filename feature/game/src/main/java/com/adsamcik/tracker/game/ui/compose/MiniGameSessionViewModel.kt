package com.adsamcik.tracker.game.ui.compose

import android.app.Application
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsDefaults
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsState
import com.adsamcik.tracker.game.goals.settings.RememberedGameSetup
import com.adsamcik.tracker.game.minigame.MiniGame
import com.adsamcik.tracker.game.minigame.MiniGameConfiguration
import com.adsamcik.tracker.game.minigame.MiniGameConfigurations
import com.adsamcik.tracker.game.minigame.MiniGameDifficulty
import com.adsamcik.tracker.game.minigame.MiniGameGoalUnit
import com.adsamcik.tracker.game.minigame.MiniGameRegistry
import com.adsamcik.tracker.game.minigame.MiniGameSnapshot
import com.adsamcik.tracker.game.minigame.FuseRunConfiguration
import com.adsamcik.tracker.game.minigame.OutrunConfiguration
import com.adsamcik.tracker.game.minigame.SwitchbackConfiguration
import com.adsamcik.tracker.game.minigame.TerritoryConfiguration
import com.adsamcik.tracker.game.minigame.ZenWalkConfiguration
import com.adsamcik.tracker.game.session.GameSessionController
import com.adsamcik.tracker.game.session.GameSessionFailureReason
import com.adsamcik.tracker.game.session.GameSessionResult
import com.adsamcik.tracker.game.session.GameSessionState
import com.adsamcik.tracker.shared.base.extension.hasPreciseLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasSelfPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Argument key used by Compose Navigation to populate the saved-state handle.
 */
internal const val MINIGAME_SESSION_GAME_ID_ARG: String = "gameId"

/**
 * Immutable set of setup choices a player can pick before starting a run.
 *
 * @param goalUnit the unit the goal is expressed in (meters, cells, minutes).
 * @param goalValues the selectable goal targets in display units.
 * @param difficulties the selectable difficulties; empty when the game has no
 *   meaningful difficulty (Territory).
 */
internal data class MiniGameSetupOptions(
	val gameId: String,
	val goalUnit: MiniGameGoalUnit,
	val goalValues: List<Int>,
	val difficulties: List<MiniGameDifficulty>,
) {
	val hasDifficulty: Boolean get() = difficulties.isNotEmpty()
}

/** The player's current, not-yet-started setup choice. */
internal data class MiniGameSetupSelection(
	val goalValue: Int,
	val difficulty: MiniGameDifficulty?,
)

/**
 * Screen state for the mini-game session. The ViewModel owns only the pre-start
 * setup and permission flow; every state from [GameSessionState.Starting]
 * onwards is a projection of the service-owned [GameSessionController] and is
 * never synthesized locally.
 */
internal sealed interface MiniGameSessionUiState {
	/** Player is choosing goal/difficulty before starting. */
	data class Setup(
		val options: MiniGameSetupOptions,
		val selection: MiniGameSetupSelection,
		val rememberSetup: Boolean,
	) : MiniGameSessionUiState

	/** Precise foreground location is required before the run can begin. */
	data class LocationPermission(
		val configuration: MiniGameConfiguration,
		val denied: Boolean,
	) : MiniGameSessionUiState

	/**
	 * Notification permission is being requested contextually (API 33+). The run
	 * starts regardless of the result, so this is a brief transient state.
	 */
	data class NotificationPermission(
		val configuration: MiniGameConfiguration,
	) : MiniGameSessionUiState

	/** Service is spinning up; no snapshot yet. */
	data class Starting(
		val configuration: MiniGameConfiguration,
	) : MiniGameSessionUiState

	/** Session started but still acquiring the first usable GPS fix. */
	data class Acquiring(
		val configuration: MiniGameConfiguration,
		val snapshot: MiniGameSnapshot,
	) : MiniGameSessionUiState

	/** Session is live and receiving samples. */
	data class Active(
		val configuration: MiniGameConfiguration,
		val snapshot: MiniGameSnapshot,
	) : MiniGameSessionUiState

	/** Session is explicitly paused; location released. */
	data class Paused(
		val configuration: MiniGameConfiguration,
		val snapshot: MiniGameSnapshot,
	) : MiniGameSessionUiState

	/** Finalizing and persisting the run. */
	data class Finishing(
		val configuration: MiniGameConfiguration,
		val snapshot: MiniGameSnapshot,
	) : MiniGameSessionUiState

	/** Run finished and score/points persisted. */
	data class Finished(
		val result: GameSessionResult,
	) : MiniGameSessionUiState

	/** Run failed without a recorded score. */
	data class Failed(
		val configuration: MiniGameConfiguration?,
		val reason: GameSessionFailureReason,
	) : MiniGameSessionUiState
}

/**
 * Drives the mini-game session screen.
 *
 * Responsibilities (deliberately narrow):
 *  - resolve the game from its nav id and expose its identity/units;
 *  - own the pre-start setup selection and remember-on-start behaviour;
 *  - gate the run behind precise foreground location and a contextual, never
 *    blocking notification-permission request on API 33+;
 *  - forward Start/Pause/Resume/Finish to the singleton [GameSessionController];
 *  - project [GameSessionController.state] into [MiniGameSessionUiState].
 *
 * It explicitly does NOT own a [com.adsamcik.tracker.game.minigame.MiniGameSession],
 * a GPS collector, an elapsed timer, persistence, pause-on-lifecycle behaviour,
 * or finalize logic — all of that lives in the foreground service and its
 * runtime. Navigating away leaves the service (and the run) untouched.
 */
@HiltViewModel
internal class MiniGameSessionViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	application: Application,
	registry: MiniGameRegistry,
	private val controller: GameSessionController,
	private val settingsRepository: GoalsSettingsRepository,
) : ViewModel() {

	private val app: Application = application

	/** Stable id of the game being played. Provided via nav arguments. */
	val gameId: String = checkNotNull(savedStateHandle.get<String>(MINIGAME_SESSION_GAME_ID_ARG)) {
		"MiniGameSessionViewModel requires '$MINIGAME_SESSION_GAME_ID_ARG' nav argument"
	}

	/** Resolved [MiniGame]; resolved eagerly so a bad id surfaces immediately. */
	val game: MiniGame = checkNotNull(registry.findById(gameId)) {
		"Unknown mini-game id: $gameId"
	}

	private val supportedConfigurations: List<MiniGameConfiguration> =
		MiniGameConfigurations.supportedFor(gameId)

	private val defaultConfiguration: MiniGameConfiguration =
		checkNotNull(MiniGameConfigurations.defaultFor(gameId)) {
			"Mini-game '$gameId' must declare a default configuration"
		}

	private val setupOptions: MiniGameSetupOptions = MiniGameSetupOptions(
		gameId = gameId,
		goalUnit = defaultConfiguration.goal.unit,
		goalValues = supportedConfigurations.map { it.goal.displayValue }.distinct(),
		difficulties = supportedConfigurations.mapNotNull { it.difficulty }.distinct(),
	)

	private val defaultSelection = MiniGameSetupSelection(
		goalValue = defaultConfiguration.goal.displayValue,
		difficulty = defaultConfiguration.difficulty,
	)

	/** `null` until seeded from settings; resolved through [defaultSelection]. */
	private val selection = MutableStateFlow<MiniGameSetupSelection?>(null)

	/** Pre-start permission gate; never enters after the service takes over. */
	private val launchGate = MutableStateFlow<LaunchGate>(LaunchGate.None)

	/** Forces the setup screen after a terminal state (Change setup). */
	private val forceSetup = MutableStateFlow(false)

	val uiState: StateFlow<MiniGameSessionUiState> = combine(
		controller.state,
		selection,
		settingsRepository.data,
		launchGate,
		forceSetup,
	) { controllerState, currentSelection, settings, gate, force ->
		buildUiState(controllerState, currentSelection, settings, gate, force)
	}.stateIn(
		scope = viewModelScope,
		started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
		initialValue = MiniGameSessionUiState.Setup(
			options = setupOptions,
			selection = defaultSelection,
			rememberSetup = GoalsSettingsDefaults.REMEMBER_LAST_SETUP,
		),
	)

	init {
		// Seed the setup selection from remembered/default the first time settings
		// resolve, and keep the pre-start gate honest whenever the service takes
		// control of the session.
		settingsRepository.data
			.onEach { settings ->
				if (selection.value == null) {
					selection.value = initialSelection(settings)
				}
			}
			.launchIn(viewModelScope)

		controller.state
			.onEach { state ->
				if (state.isServiceOwned()) {
					forceSetup.value = false
					launchGate.value = LaunchGate.None
				}
			}
			.launchIn(viewModelScope)
	}

	private fun buildUiState(
		controllerState: GameSessionState,
		currentSelection: MiniGameSetupSelection?,
		settings: GoalsSettingsState,
		gate: LaunchGate,
		force: Boolean,
	): MiniGameSessionUiState {
		val resolvedSelection = currentSelection ?: initialSelection(settings)
		val setup = MiniGameSessionUiState.Setup(
			options = setupOptions,
			selection = resolvedSelection,
			rememberSetup = settings.rememberLastSetup,
		)

		when (gate) {
			is LaunchGate.LocationPermission ->
				return MiniGameSessionUiState.LocationPermission(gate.configuration, gate.denied)
			is LaunchGate.NotificationPermission ->
				return MiniGameSessionUiState.NotificationPermission(gate.configuration)
			LaunchGate.None -> Unit
		}

		return when (controllerState) {
			GameSessionState.Idle -> setup
			is GameSessionState.Starting ->
				MiniGameSessionUiState.Starting(controllerState.configuration)
			is GameSessionState.Active -> {
				val snapshot = controllerState.snapshot
				if (snapshot.phase.isAcquiring()) {
					MiniGameSessionUiState.Acquiring(controllerState.configuration, snapshot)
				} else {
					MiniGameSessionUiState.Active(controllerState.configuration, snapshot)
				}
			}
			is GameSessionState.Paused ->
				MiniGameSessionUiState.Paused(controllerState.configuration, controllerState.snapshot)
			is GameSessionState.Finishing ->
				MiniGameSessionUiState.Finishing(controllerState.configuration, controllerState.snapshot)
			is GameSessionState.Finished ->
				if (force) setup else MiniGameSessionUiState.Finished(controllerState.result)
			is GameSessionState.Failed ->
				if (force) setup else MiniGameSessionUiState.Failed(controllerState.configuration, controllerState.reason)
		}
	}

	// --- Setup mutations -----------------------------------------------------

	fun selectGoal(goalValue: Int) {
		val coerced = if (goalValue in setupOptions.goalValues) goalValue else defaultSelection.goalValue
		selection.update { (it ?: defaultSelection).copy(goalValue = coerced) }
	}

	fun selectDifficulty(difficulty: MiniGameDifficulty) {
		if (!setupOptions.hasDifficulty) return
		if (difficulty !in setupOptions.difficulties) return
		selection.update { (it ?: defaultSelection).copy(difficulty = difficulty) }
	}

	fun setRememberSetup(enabled: Boolean) {
		viewModelScope.launch { settingsRepository.setRememberLastSetup(enabled) }
	}

	/** Show the setup screen again after a terminal state. */
	fun changeSetup() {
		forceSetup.value = true
	}

	// --- Launch flow ---------------------------------------------------------

	/** Start with the current setup selection. */
	fun start() {
		beginLaunch(buildConfiguration(selection.value ?: defaultSelection))
	}

	/** Restart with the exact configuration that was just played. */
	fun playAgain(configuration: MiniGameConfiguration) {
		selection.value = MiniGameSetupSelection(
			goalValue = configuration.goal.displayValue,
			difficulty = configuration.difficulty,
		)
		beginLaunch(configuration)
	}

	private fun beginLaunch(configuration: MiniGameConfiguration) {
		forceSetup.value = false
		rememberSetup(configuration)
		if (!app.hasPreciseLocationPermission) {
			launchGate.value = LaunchGate.LocationPermission(configuration, denied = false)
			return
		}
		requestNotificationThenStart(configuration)
	}

	private fun requestNotificationThenStart(configuration: MiniGameConfiguration) {
		if (needsNotificationPermission()) {
			launchGate.value = LaunchGate.NotificationPermission(configuration)
		} else {
			startNow(configuration)
		}
	}

	private fun startNow(configuration: MiniGameConfiguration) {
		launchGate.value = LaunchGate.None
		controller.start(configuration)
	}

	/**
	 * Called by the UI after the location permission prompt resolves. Only a
	 * precise (fine) grant unblocks the run; a coarse-only grant or denial keeps
	 * the player on the permission screen with a retry affordance.
	 */
	fun onLocationPermissionResult(granted: Boolean) {
		val gate = launchGate.value as? LaunchGate.LocationPermission ?: return
		if (granted && app.hasPreciseLocationPermission) {
			requestNotificationThenStart(gate.configuration)
		} else {
			launchGate.value = gate.copy(denied = true)
		}
	}

	/** Re-arm the location rationale after a denial so the player can retry. */
	fun retryLocationPermission() {
		val gate = launchGate.value as? LaunchGate.LocationPermission ?: return
		launchGate.value = gate.copy(denied = false)
	}

	/** Player dismissed the location rationale without granting; go back to setup. */
	fun cancelLocationPermission() {
		if (launchGate.value is LaunchGate.LocationPermission) {
			launchGate.value = LaunchGate.None
		}
	}

	/**
	 * Called after the notification permission prompt resolves. The run starts
	 * regardless of the outcome — a denied notification never fails the run.
	 */
	fun onNotificationPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean) {
		val gate = launchGate.value as? LaunchGate.NotificationPermission ?: return
		startNow(gate.configuration)
	}

	// --- Controller commands -------------------------------------------------

	fun pause() = controller.pause()

	fun resume() = controller.resume()

	fun finish() = controller.finish()

	// --- Helpers -------------------------------------------------------------

	private fun rememberSetup(configuration: MiniGameConfiguration) {
		// The repository itself no-ops (and clears) when remember-last-setup is
		// disabled, so we can call unconditionally without reading settings first.
		viewModelScope.launch {
			when (configuration) {
				is OutrunConfiguration -> settingsRepository.setRememberedOutrunSetup(
					goalMeters = configuration.goal.meters,
					difficulty = configuration.difficulty.name,
				)
				is TerritoryConfiguration -> settingsRepository.setRememberedTerritorySetup(
					goalCells = configuration.goal.cells,
					difficulty = null,
				)
				is ZenWalkConfiguration -> settingsRepository.setRememberedZenSetup(
					goalMinutes = configuration.goal.minutes,
					difficulty = configuration.difficulty.name,
				)
				is FuseRunConfiguration -> settingsRepository.setRememberedFuseRunSetup(
					goalCharges = configuration.goal.charges,
					difficulty = configuration.difficulty.name,
				)
				is SwitchbackConfiguration -> settingsRepository.setRememberedSwitchbackSetup(
					goalTurns = configuration.goal.turns,
					difficulty = configuration.difficulty.name,
				)
			}
		}
	}

	private fun initialSelection(settings: GoalsSettingsState): MiniGameSetupSelection {
		val remembered = settings.rememberedSetupFor(gameId)
		if (settings.rememberLastSetup && remembered != null) {
			return coerceSelection(
				goalValue = remembered.goalValue,
				difficultyName = remembered.difficulty,
			)
		}
		return defaultSelection
	}

	private fun coerceSelection(goalValue: Int, difficultyName: String?): MiniGameSetupSelection {
		val goal = if (goalValue in setupOptions.goalValues) goalValue else defaultSelection.goalValue
		val difficulty = if (!setupOptions.hasDifficulty) {
			null
		} else {
			setupOptions.difficulties.firstOrNull { it.name == difficultyName }
				?: defaultSelection.difficulty
		}
		return MiniGameSetupSelection(goal, difficulty)
	}

	private fun buildConfiguration(selection: MiniGameSetupSelection): MiniGameConfiguration =
		supportedConfigurations.firstOrNull {
			it.goal.displayValue == selection.goalValue && it.difficulty == selection.difficulty
		}
			?: supportedConfigurations.firstOrNull { it.goal.displayValue == selection.goalValue }
			?: defaultConfiguration

	private fun needsNotificationPermission(): Boolean =
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			!app.hasSelfPermission(POST_NOTIFICATIONS_PERMISSION)

	private fun GoalsSettingsState.rememberedSetupFor(gameId: String): RememberedGameSetup? =
		when (gameId) {
			OutrunConfiguration.GAME_ID -> rememberedOutrunSetup
			TerritoryConfiguration.GAME_ID -> rememberedTerritorySetup
			ZenWalkConfiguration.GAME_ID -> rememberedZenSetup
			FuseRunConfiguration.GAME_ID -> rememberedFuseRunSetup
			SwitchbackConfiguration.GAME_ID -> rememberedSwitchbackSetup
			else -> null
		}

	private fun GameSessionState.isServiceOwned(): Boolean = when (this) {
		is GameSessionState.Starting,
		is GameSessionState.Active,
		is GameSessionState.Paused,
		is GameSessionState.Finishing,
		-> true
		GameSessionState.Idle,
		is GameSessionState.Finished,
		is GameSessionState.Failed,
		-> false
	}

	private sealed interface LaunchGate {
		data object None : LaunchGate
		data class LocationPermission(
			val configuration: MiniGameConfiguration,
			val denied: Boolean,
		) : LaunchGate
		data class NotificationPermission(
			val configuration: MiniGameConfiguration,
		) : LaunchGate
	}

	private companion object {
		private const val STOP_TIMEOUT_MS: Long = 5_000L
		private const val POST_NOTIFICATIONS_PERMISSION: String =
			"android.permission.POST_NOTIFICATIONS"
	}
}

private fun com.adsamcik.tracker.game.minigame.MiniGamePhase.isAcquiring(): Boolean =
	this == com.adsamcik.tracker.game.minigame.MiniGamePhase.ACQUIRING_SIGNAL ||
		this == com.adsamcik.tracker.game.minigame.MiniGamePhase.WAITING_TO_START
