package com.adsamcik.tracker.game.minigame

import com.adsamcik.tracker.game.minigame.fuserun.FuseRunGame
import com.adsamcik.tracker.game.minigame.outrun.OutrunGame
import com.adsamcik.tracker.game.minigame.switchback.SwitchbackGame
import com.adsamcik.tracker.game.minigame.territory.TerritoryGame
import com.adsamcik.tracker.game.minigame.zenwalk.ZenWalkGame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MiniGameConfigurationContractTest {

	@Test
	fun `supported configurations contain every valid goal and difficulty combination`() {
		assertEquals(9, MiniGameConfigurations.OUTRUN.size)
		assertEquals(
			setOf(25, 50, 100),
			MiniGameConfigurations.OUTRUN.map { it.goal.meters }.toSet(),
		)
		assertEquals(
			MiniGameDifficulty.entries.toSet(),
			MiniGameConfigurations.OUTRUN.map { it.difficulty }.toSet(),
		)

		assertEquals(3, MiniGameConfigurations.TERRITORY.size)
		assertEquals(
			setOf(5, 10, 20),
			MiniGameConfigurations.TERRITORY.map { it.goal.cells }.toSet(),
		)
		assertTrue(MiniGameConfigurations.TERRITORY.all { it.difficulty == null })

		assertEquals(9, MiniGameConfigurations.ZEN_WALK.size)
		assertEquals(
			setOf(5, 10, 20),
			MiniGameConfigurations.ZEN_WALK.map { it.goal.minutes }.toSet(),
		)
		assertEquals(
			MiniGameDifficulty.entries.toSet(),
			MiniGameConfigurations.ZEN_WALK.map { it.difficulty }.toSet(),
		)

		assertEquals(9, MiniGameConfigurations.FUSE_RUN.size)
		assertEquals(
			setOf(3, 5, 8),
			MiniGameConfigurations.FUSE_RUN.map { it.goal.charges }.toSet(),
		)
		assertEquals(
			MiniGameDifficulty.entries.toSet(),
			MiniGameConfigurations.FUSE_RUN.map { it.difficulty }.toSet(),
		)

		assertEquals(9, MiniGameConfigurations.SWITCHBACK.size)
		assertEquals(
			setOf(4, 6, 10),
			MiniGameConfigurations.SWITCHBACK.map { it.goal.turns }.toSet(),
		)
		assertEquals(
			MiniGameDifficulty.entries.toSet(),
			MiniGameConfigurations.SWITCHBACK.map { it.difficulty }.toSet(),
		)
		assertThrows(UnsupportedOperationException::class.java) {
			@Suppress("UNCHECKED_CAST")
			(MiniGameConfigurations.OUTRUN as MutableList<OutrunConfiguration>).clear()
		}
	}

	@Test
	fun `games expose stable defaults score units and presentation tokens`() {
		val outrun = OutrunGame()
		assertEquals(
			OutrunConfiguration(OutrunGoal.METERS_50, MiniGameDifficulty.NORMAL),
			outrun.defaultConfiguration,
		)
		assertEquals(MiniGameScoreUnit.DISTANCE_METERS, outrun.scoreUnit)
		assertEquals(
			MiniGamePresentation(
				icon = MiniGameIcon.GHOST,
				accentRole = MiniGameAccentRole.TERTIARY,
				shapeRole = MiniGameShapeRole.MOMENTUM,
			),
			outrun.presentation,
		)

		val territory = TerritoryGame()
		assertEquals(TerritoryConfiguration(TerritoryGoal.CELLS_10), territory.defaultConfiguration)
		assertEquals(MiniGameScoreUnit.CELL_COUNT, territory.scoreUnit)
		assertEquals(MiniGameAccentRole.PRIMARY, territory.presentation.accentRole)
		assertEquals(MiniGameShapeRole.TERRAIN, territory.presentation.shapeRole)

		val zenWalk = ZenWalkGame()
		assertEquals(
			ZenWalkConfiguration(ZenGoal.MINUTES_10, MiniGameDifficulty.NORMAL),
			zenWalk.defaultConfiguration,
		)
		assertEquals(MiniGameScoreUnit.DURATION_SECONDS, zenWalk.scoreUnit)
		assertEquals(MiniGameAccentRole.SECONDARY, zenWalk.presentation.accentRole)
		assertEquals(MiniGameShapeRole.WAYPOINT, zenWalk.presentation.shapeRole)

		val fuseRun = FuseRunGame()
		assertEquals(
			FuseRunConfiguration(FuseRunGoal.CHARGES_5, MiniGameDifficulty.NORMAL),
			fuseRun.defaultConfiguration,
		)
		assertEquals(MiniGameScoreUnit.DEFUSAL_COUNT, fuseRun.scoreUnit)
		assertEquals(MiniGameIcon.FUSE, fuseRun.presentation.icon)
		assertEquals(MiniGameAccentRole.TERTIARY, fuseRun.presentation.accentRole)
		assertEquals(MiniGameShapeRole.MOMENTUM, fuseRun.presentation.shapeRole)

		val switchback = SwitchbackGame()
		assertEquals(
			SwitchbackConfiguration(SwitchbackGoal.TURNS_6, MiniGameDifficulty.NORMAL),
			switchback.defaultConfiguration,
		)
		assertEquals(MiniGameScoreUnit.TURN_COUNT, switchback.scoreUnit)
		assertEquals(MiniGameIcon.SWITCHBACK, switchback.presentation.icon)
		assertEquals(MiniGameAccentRole.PRIMARY, switchback.presentation.accentRole)
		assertEquals(MiniGameShapeRole.TERRAIN, switchback.presentation.shapeRole)
	}

	@Test
	fun `configured session creation accepts only configurations belonging to the game`() {
		val game = OutrunGame()

		game.createSession(OutrunConfiguration(OutrunGoal.METERS_25, MiniGameDifficulty.EASY))

		assertThrows(IllegalArgumentException::class.java) {
			game.createSession(TerritoryConfiguration(TerritoryGoal.CELLS_5))
		}
	}
}
