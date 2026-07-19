package com.adsamcik.tracker.game.session

import com.adsamcik.tracker.game.minigame.MiniGameCompletionOutcome
import com.adsamcik.tracker.game.minigame.MiniGameConfiguration
import com.adsamcik.tracker.game.minigame.MiniGameDifficulty
import com.adsamcik.tracker.game.minigame.MiniGameSnapshot
import com.adsamcik.tracker.game.minigame.OutrunConfiguration
import com.adsamcik.tracker.game.minigame.OutrunGoal
import com.adsamcik.tracker.game.minigame.TerritoryConfiguration
import com.adsamcik.tracker.game.minigame.TerritoryGoal
import com.adsamcik.tracker.game.minigame.ZenGoal
import com.adsamcik.tracker.game.minigame.ZenWalkConfiguration
import com.adsamcik.tracker.game.minigame.FuseRunConfiguration
import com.adsamcik.tracker.game.minigame.FuseRunGoal
import com.adsamcik.tracker.game.minigame.SwitchbackConfiguration
import com.adsamcik.tracker.game.minigame.SwitchbackGoal

internal sealed interface GameSessionState {
	data object Idle : GameSessionState

	data class Starting(
		val configuration: MiniGameConfiguration,
	) : GameSessionState

	data class Active(
		val sessionId: GameSessionId,
		val configuration: MiniGameConfiguration,
		val snapshot: MiniGameSnapshot,
	) : GameSessionState

	data class Paused(
		val sessionId: GameSessionId,
		val configuration: MiniGameConfiguration,
		val snapshot: MiniGameSnapshot,
	) : GameSessionState

	data class Finishing(
		val sessionId: GameSessionId,
		val configuration: MiniGameConfiguration,
		val snapshot: MiniGameSnapshot,
	) : GameSessionState

	data class Finished(
		val result: GameSessionResult,
	) : GameSessionState

	data class Failed(
		val configuration: MiniGameConfiguration?,
		val reason: GameSessionFailureReason,
	) : GameSessionState
}

@JvmInline
internal value class GameSessionId(
	val value: String,
) {
	init {
		require(value.isNotBlank()) { "Session id cannot be blank" }
	}
}

internal data class GameSessionResult(
	val sessionId: GameSessionId,
	val configuration: MiniGameConfiguration,
	val finalScore: Double,
	val pointsAwarded: Int,
	val completedAtMs: Long,
	val completionOutcome: MiniGameCompletionOutcome,
) {
	init {
		require(finalScore.isFinite() && finalScore >= 0.0) {
			"Final score must be finite and non-negative"
		}
		require(pointsAwarded >= 0) { "Awarded points cannot be negative" }
		require(completedAtMs >= 0L) { "Completion timestamp cannot be negative" }
	}
}

internal enum class GameSessionFailureReason {
	PERMISSION_REQUIRED,
	LOCATION_UNAVAILABLE,
	INVALID_COMMAND,
	PERSISTENCE_FAILED,
	INTERNAL_ERROR,
}

internal sealed interface GameSessionCommand {
	data class Start(
		val configuration: MiniGameConfiguration,
	) : GameSessionCommand

	data object Pause : GameSessionCommand
	data object Resume : GameSessionCommand
	data object Finish : GameSessionCommand
}

internal object GameSessionIntentContract {
	const val ACTION_START: String = "com.adsamcik.tracker.game.action.START_SESSION"
	const val ACTION_PAUSE: String = "com.adsamcik.tracker.game.action.PAUSE_SESSION"
	const val ACTION_RESUME: String = "com.adsamcik.tracker.game.action.RESUME_SESSION"
	const val ACTION_FINISH: String = "com.adsamcik.tracker.game.action.FINISH_SESSION"

	const val EXTRA_GAME_ID: String = "com.adsamcik.tracker.game.extra.GAME_ID"
	const val EXTRA_GOAL_VALUE: String = "com.adsamcik.tracker.game.extra.GOAL_VALUE"
	const val EXTRA_DIFFICULTY: String = "com.adsamcik.tracker.game.extra.DIFFICULTY"
}

/**
 * Android-free primitive payload. A service can map these fields directly to
 * Intent action/string/int extras without Parcelable or Serializable models.
 */
internal data class EncodedGameSessionIntent(
	val action: String,
	val gameId: String? = null,
	val goalValue: Int? = null,
	val difficulty: String? = null,
)

internal sealed interface GameSessionIntentDecodeResult {
	data class Decoded(
		val command: GameSessionCommand,
	) : GameSessionIntentDecodeResult

	data class Invalid(
		val reason: GameSessionIntentInvalidReason,
	) : GameSessionIntentDecodeResult
}

internal enum class GameSessionIntentInvalidReason {
	UNKNOWN_ACTION,
	UNEXPECTED_EXTRAS,
	MISSING_GAME_ID,
	MISSING_GOAL,
	MISSING_DIFFICULTY,
	UNKNOWN_GAME,
	UNSUPPORTED_GOAL,
	UNSUPPORTED_DIFFICULTY,
	UNEXPECTED_DIFFICULTY,
}

internal object GameSessionIntentCodec {
	fun encode(command: GameSessionCommand): EncodedGameSessionIntent = when (command) {
		is GameSessionCommand.Start -> EncodedGameSessionIntent(
			action = GameSessionIntentContract.ACTION_START,
			gameId = command.configuration.gameId,
			goalValue = command.configuration.goal.displayValue,
			difficulty = command.configuration.difficulty?.name,
		)
		GameSessionCommand.Pause -> EncodedGameSessionIntent(GameSessionIntentContract.ACTION_PAUSE)
		GameSessionCommand.Resume -> EncodedGameSessionIntent(GameSessionIntentContract.ACTION_RESUME)
		GameSessionCommand.Finish -> EncodedGameSessionIntent(GameSessionIntentContract.ACTION_FINISH)
	}

	fun decode(
		action: String?,
		gameId: String?,
		goalValue: Int?,
		difficulty: String?,
	): GameSessionIntentDecodeResult = when (action) {
		GameSessionIntentContract.ACTION_START -> decodeStart(gameId, goalValue, difficulty)
		GameSessionIntentContract.ACTION_PAUSE -> decodeWithoutExtras(
			gameId,
			goalValue,
			difficulty,
			GameSessionCommand.Pause,
		)
		GameSessionIntentContract.ACTION_RESUME -> decodeWithoutExtras(
			gameId,
			goalValue,
			difficulty,
			GameSessionCommand.Resume,
		)
		GameSessionIntentContract.ACTION_FINISH -> decodeWithoutExtras(
			gameId,
			goalValue,
			difficulty,
			GameSessionCommand.Finish,
		)
		else -> GameSessionIntentDecodeResult.Invalid(
			GameSessionIntentInvalidReason.UNKNOWN_ACTION,
		)
	}

	private fun decodeStart(
		gameId: String?,
		goalValue: Int?,
		difficulty: String?,
	): GameSessionIntentDecodeResult {
		if (gameId == null) {
			return GameSessionIntentDecodeResult.Invalid(
				GameSessionIntentInvalidReason.MISSING_GAME_ID,
			)
		}
		if (goalValue == null) {
			return GameSessionIntentDecodeResult.Invalid(
				GameSessionIntentInvalidReason.MISSING_GOAL,
			)
		}

		val configuration = when (gameId) {
			OutrunConfiguration.GAME_ID -> {
				val goal = OutrunGoal.entries.firstOrNull { it.meters == goalValue }
					?: return unsupportedGoal()
				val parsedDifficulty = parseRequiredDifficulty(difficulty)
				if (parsedDifficulty is DifficultyDecode.Invalid) {
					return GameSessionIntentDecodeResult.Invalid(parsedDifficulty.reason)
				}
				OutrunConfiguration(goal, (parsedDifficulty as DifficultyDecode.Valid).value)
			}
			TerritoryConfiguration.GAME_ID -> {
				if (difficulty != null) {
					return GameSessionIntentDecodeResult.Invalid(
						GameSessionIntentInvalidReason.UNEXPECTED_DIFFICULTY,
					)
				}
				val goal = TerritoryGoal.entries.firstOrNull { it.cells == goalValue }
					?: return unsupportedGoal()
				TerritoryConfiguration(goal)
			}
			ZenWalkConfiguration.GAME_ID -> {
				val goal = ZenGoal.entries.firstOrNull { it.minutes == goalValue }
					?: return unsupportedGoal()
				val parsedDifficulty = parseRequiredDifficulty(difficulty)
				if (parsedDifficulty is DifficultyDecode.Invalid) {
					return GameSessionIntentDecodeResult.Invalid(parsedDifficulty.reason)
				}
				ZenWalkConfiguration(goal, (parsedDifficulty as DifficultyDecode.Valid).value)
			}
			FuseRunConfiguration.GAME_ID -> {
				val goal = FuseRunGoal.entries.firstOrNull { it.charges == goalValue }
					?: return unsupportedGoal()
				val parsedDifficulty = parseRequiredDifficulty(difficulty)
				if (parsedDifficulty is DifficultyDecode.Invalid) {
					return GameSessionIntentDecodeResult.Invalid(parsedDifficulty.reason)
				}
				FuseRunConfiguration(goal, (parsedDifficulty as DifficultyDecode.Valid).value)
			}
			SwitchbackConfiguration.GAME_ID -> {
				val goal = SwitchbackGoal.entries.firstOrNull { it.turns == goalValue }
					?: return unsupportedGoal()
				val parsedDifficulty = parseRequiredDifficulty(difficulty)
				if (parsedDifficulty is DifficultyDecode.Invalid) {
					return GameSessionIntentDecodeResult.Invalid(parsedDifficulty.reason)
				}
				SwitchbackConfiguration(goal, (parsedDifficulty as DifficultyDecode.Valid).value)
			}
			else -> return GameSessionIntentDecodeResult.Invalid(
				GameSessionIntentInvalidReason.UNKNOWN_GAME,
			)
		}
		return GameSessionIntentDecodeResult.Decoded(GameSessionCommand.Start(configuration))
	}

	private fun decodeWithoutExtras(
		gameId: String?,
		goalValue: Int?,
		difficulty: String?,
		command: GameSessionCommand,
	): GameSessionIntentDecodeResult =
		if (gameId != null || goalValue != null || difficulty != null) {
			GameSessionIntentDecodeResult.Invalid(
				GameSessionIntentInvalidReason.UNEXPECTED_EXTRAS,
			)
		} else {
			GameSessionIntentDecodeResult.Decoded(command)
		}

	private fun parseRequiredDifficulty(difficulty: String?): DifficultyDecode {
		if (difficulty == null) {
			return DifficultyDecode.Invalid(GameSessionIntentInvalidReason.MISSING_DIFFICULTY)
		}
		val value = MiniGameDifficulty.entries.firstOrNull { it.name == difficulty }
			?: return DifficultyDecode.Invalid(
				GameSessionIntentInvalidReason.UNSUPPORTED_DIFFICULTY,
			)
		return DifficultyDecode.Valid(value)
	}

	private fun unsupportedGoal(): GameSessionIntentDecodeResult.Invalid =
		GameSessionIntentDecodeResult.Invalid(GameSessionIntentInvalidReason.UNSUPPORTED_GOAL)

	private sealed interface DifficultyDecode {
		data class Valid(val value: MiniGameDifficulty) : DifficultyDecode
		data class Invalid(val reason: GameSessionIntentInvalidReason) : DifficultyDecode
	}
}
