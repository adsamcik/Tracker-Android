package com.adsamcik.tracker.game.session

import com.adsamcik.tracker.game.minigame.MiniGameConfigurations
import com.adsamcik.tracker.game.minigame.MiniGameDifficulty
import com.adsamcik.tracker.game.minigame.FuseRunConfiguration
import com.adsamcik.tracker.game.minigame.FuseRunGoal
import com.adsamcik.tracker.game.minigame.OutrunConfiguration
import com.adsamcik.tracker.game.minigame.OutrunGoal
import com.adsamcik.tracker.game.minigame.TerritoryConfiguration
import com.adsamcik.tracker.game.minigame.TerritoryGoal
import com.adsamcik.tracker.game.minigame.SwitchbackConfiguration
import com.adsamcik.tracker.game.minigame.SwitchbackGoal
import com.adsamcik.tracker.game.minigame.ZenGoal
import com.adsamcik.tracker.game.minigame.ZenWalkConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class GameSessionContractTest {

	@Test
	fun `start commands round trip every supported configuration through primitive fields`() {
		val configurations = MiniGameConfigurations.OUTRUN +
			MiniGameConfigurations.TERRITORY +
			MiniGameConfigurations.ZEN_WALK +
			MiniGameConfigurations.FUSE_RUN +
			MiniGameConfigurations.SWITCHBACK

		configurations.forEach { configuration ->
			val encoded = GameSessionIntentCodec.encode(GameSessionCommand.Start(configuration))
			val decoded = GameSessionIntentCodec.decode(
				action = encoded.action,
				gameId = encoded.gameId,
				goalValue = encoded.goalValue,
				difficulty = encoded.difficulty,
			)

			assertEquals(
				GameSessionIntentDecodeResult.Decoded(GameSessionCommand.Start(configuration)),
				decoded,
			)
		}
	}

	@Test
	fun `lifecycle commands use explicit actions and no extras`() {
		listOf(
			GameSessionCommand.Pause to GameSessionIntentContract.ACTION_PAUSE,
			GameSessionCommand.Resume to GameSessionIntentContract.ACTION_RESUME,
			GameSessionCommand.Finish to GameSessionIntentContract.ACTION_FINISH,
		).forEach { (command, action) ->
			val encoded = GameSessionIntentCodec.encode(command)
			assertEquals(action, encoded.action)
			assertEquals(null, encoded.gameId)
			assertEquals(null, encoded.goalValue)
			assertEquals(null, encoded.difficulty)
			assertEquals(
				GameSessionIntentDecodeResult.Decoded(command),
				GameSessionIntentCodec.decode(action, null, null, null),
			)
		}
	}

	@Test
	fun `codec rejects unsupported goals missing difficulty and territory difficulty`() {
		assertInstanceOf(
			GameSessionIntentDecodeResult.Invalid::class.java,
			GameSessionIntentCodec.decode(
				action = GameSessionIntentContract.ACTION_START,
				gameId = OutrunConfiguration.GAME_ID,
				goalValue = 30,
				difficulty = MiniGameDifficulty.NORMAL.name,
			),
		)
		assertInstanceOf(
			GameSessionIntentDecodeResult.Invalid::class.java,
			GameSessionIntentCodec.decode(
				action = GameSessionIntentContract.ACTION_START,
				gameId = ZenWalkConfiguration.GAME_ID,
				goalValue = ZenGoal.MINUTES_10.minutes,
				difficulty = null,
			),
		)
		assertInstanceOf(
			GameSessionIntentDecodeResult.Invalid::class.java,
			GameSessionIntentCodec.decode(
				action = GameSessionIntentContract.ACTION_START,
				gameId = TerritoryConfiguration.GAME_ID,
				goalValue = TerritoryGoal.CELLS_10.cells,
				difficulty = MiniGameDifficulty.EASY.name,
			),
		)
		assertInstanceOf(
			GameSessionIntentDecodeResult.Invalid::class.java,
			GameSessionIntentCodec.decode(
				action = GameSessionIntentContract.ACTION_START,
				gameId = FuseRunConfiguration.GAME_ID,
				goalValue = FuseRunGoal.CHARGES_5.charges,
				difficulty = null,
			),
		)
		assertInstanceOf(
			GameSessionIntentDecodeResult.Invalid::class.java,
			GameSessionIntentCodec.decode(
				action = GameSessionIntentContract.ACTION_START,
				gameId = SwitchbackConfiguration.GAME_ID,
				goalValue = SwitchbackGoal.TURNS_6.turns,
				difficulty = "IMPOSSIBLE",
			),
		)
	}

	@Test
	fun `codec decodes the documented default start payload`() {
		val result = GameSessionIntentCodec.decode(
			action = GameSessionIntentContract.ACTION_START,
			gameId = OutrunConfiguration.GAME_ID,
			goalValue = OutrunGoal.METERS_50.meters,
			difficulty = MiniGameDifficulty.NORMAL.name,
		)

		assertEquals(
			GameSessionIntentDecodeResult.Decoded(
				GameSessionCommand.Start(
					OutrunConfiguration(OutrunGoal.METERS_50, MiniGameDifficulty.NORMAL),
				),
			),
			result,
		)
	}
}
