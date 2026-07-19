package com.adsamcik.tracker.game.session

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.game.minigame.MiniGameConfiguration
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun interface GameSessionStatePublisher {
	fun publish(state: GameSessionState)
}

@Singleton
internal class GameSessionStateStore @Inject constructor() : GameSessionStatePublisher {
	private val mutableState = MutableStateFlow<GameSessionState>(GameSessionState.Idle)
	val state: StateFlow<GameSessionState> = mutableState.asStateFlow()

	override fun publish(state: GameSessionState) {
		mutableState.value = state
	}
}

/**
 * Application-scoped intent façade. Runtime state can enter the exposed flow
 * only through [GameSessionStatePublisher]; command methods never synthesize
 * active or finished state.
 */
@Singleton
internal class DefaultGameSessionController @Inject constructor(
	@ApplicationContext context: Context,
	private val stateStore: GameSessionStateStore,
) : GameSessionController {
	private val applicationContext = context.applicationContext

	override val state: StateFlow<GameSessionState> = stateStore.state

	override fun start(configuration: MiniGameConfiguration) {
		stateStore.publish(GameSessionState.Starting(configuration))
		runCatching {
			ContextCompat.startForegroundService(
				applicationContext,
				createIntent(GameSessionCommand.Start(configuration)),
			)
		}.onFailure {
			stateStore.publish(
				GameSessionState.Failed(
					configuration = configuration,
					reason = GameSessionFailureReason.INTERNAL_ERROR,
				),
			)
		}
	}

	override fun pause() {
		sendCommand(GameSessionCommand.Pause)
	}

	override fun resume() {
		sendCommand(GameSessionCommand.Resume)
	}

	override fun finish() {
		sendCommand(GameSessionCommand.Finish)
	}

	private fun sendCommand(command: GameSessionCommand) {
		runCatching {
			applicationContext.startService(createIntent(command))
		}.onFailure {
			stateStore.publish(
				GameSessionState.Failed(
					configuration = state.value.configurationOrNull(),
					reason = GameSessionFailureReason.INTERNAL_ERROR,
				),
			)
		}
	}

	private fun createIntent(command: GameSessionCommand): Intent {
		val encoded = GameSessionIntentCodec.encode(command)
		return Intent(applicationContext, GameSessionService::class.java).apply {
			action = encoded.action
			encoded.gameId?.let { putExtra(GameSessionIntentContract.EXTRA_GAME_ID, it) }
			encoded.goalValue?.let { putExtra(GameSessionIntentContract.EXTRA_GOAL_VALUE, it) }
			encoded.difficulty?.let { putExtra(GameSessionIntentContract.EXTRA_DIFFICULTY, it) }
		}
	}

	private fun GameSessionState.configurationOrNull(): MiniGameConfiguration? = when (this) {
		GameSessionState.Idle -> null
		is GameSessionState.Starting -> configuration
		is GameSessionState.Active -> configuration
		is GameSessionState.Paused -> configuration
		is GameSessionState.Finishing -> configuration
		is GameSessionState.Finished -> result.configuration
		is GameSessionState.Failed -> configuration
	}
}
