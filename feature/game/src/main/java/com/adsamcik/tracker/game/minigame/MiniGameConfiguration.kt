package com.adsamcik.tracker.game.minigame

import java.util.Collections

/**
 * A fully validated mini-game setup. Each concrete type only accepts supported
 * enum values, so invalid goal/difficulty combinations cannot be represented.
 */
internal sealed interface MiniGameConfiguration {
	val gameId: String
	val goal: MiniGameGoal
	val difficulty: MiniGameDifficulty?
}

internal sealed interface MiniGameGoal {
	val displayValue: Int
	val scoreTarget: Double
	val unit: MiniGameGoalUnit
}

internal enum class MiniGameGoalUnit {
	METERS,
	CELLS,
	MINUTES,
	CHARGES,
	TURNS,
}

internal enum class MiniGameDifficulty {
	EASY,
	NORMAL,
	HARD,
}

internal enum class OutrunGoal(
	val meters: Int,
) : MiniGameGoal {
	METERS_25(25),
	METERS_50(50),
	METERS_100(100),
	;

	override val displayValue: Int get() = meters
	override val scoreTarget: Double get() = meters.toDouble()
	override val unit: MiniGameGoalUnit get() = MiniGameGoalUnit.METERS
}

internal data class OutrunConfiguration(
	override val goal: OutrunGoal,
	override val difficulty: MiniGameDifficulty,
) : MiniGameConfiguration {
	override val gameId: String get() = GAME_ID

	companion object {
		const val GAME_ID: String = "outrun"
	}
}

internal enum class TerritoryGoal(
	val cells: Int,
) : MiniGameGoal {
	CELLS_5(5),
	CELLS_10(10),
	CELLS_20(20),
	;

	override val displayValue: Int get() = cells
	override val scoreTarget: Double get() = cells.toDouble()
	override val unit: MiniGameGoalUnit get() = MiniGameGoalUnit.CELLS
}

internal data class TerritoryConfiguration(
	override val goal: TerritoryGoal,
) : MiniGameConfiguration {
	override val gameId: String get() = GAME_ID
	override val difficulty: MiniGameDifficulty? get() = null

	companion object {
		const val GAME_ID: String = "territory"
	}
}

internal enum class ZenGoal(
	val minutes: Int,
) : MiniGameGoal {
	MINUTES_5(5),
	MINUTES_10(10),
	MINUTES_20(20),
	;

	override val displayValue: Int get() = minutes
	override val scoreTarget: Double get() = minutes * SECONDS_PER_MINUTE
	override val unit: MiniGameGoalUnit get() = MiniGameGoalUnit.MINUTES

	private companion object {
		private const val SECONDS_PER_MINUTE: Double = 60.0
	}
}

internal data class ZenWalkConfiguration(
	override val goal: ZenGoal,
	override val difficulty: MiniGameDifficulty,
) : MiniGameConfiguration {
	override val gameId: String get() = GAME_ID

	companion object {
		const val GAME_ID: String = "zenwalk"
	}
}

internal object MiniGameConfigurations {
	val OUTRUN: List<OutrunConfiguration> = immutableList(
		OutrunGoal.entries.flatMap { goal ->
			MiniGameDifficulty.entries.map { difficulty ->
				OutrunConfiguration(goal, difficulty)
			}
		}
	)

	val TERRITORY: List<TerritoryConfiguration> =
		immutableList(TerritoryGoal.entries.map(::TerritoryConfiguration))

	val ZEN_WALK: List<ZenWalkConfiguration> = immutableList(
		ZenGoal.entries.flatMap { goal ->
			MiniGameDifficulty.entries.map { difficulty ->
				ZenWalkConfiguration(goal, difficulty)
			}
		}
	)

	val FUSE_RUN: List<FuseRunConfiguration> = immutableList(
		FuseRunGoal.entries.flatMap { goal ->
			MiniGameDifficulty.entries.map { difficulty ->
				FuseRunConfiguration(goal, difficulty)
			}
		}
	)

	val SWITCHBACK: List<SwitchbackConfiguration> = immutableList(
		SwitchbackGoal.entries.flatMap { goal ->
			MiniGameDifficulty.entries.map { difficulty ->
				SwitchbackConfiguration(goal, difficulty)
			}
		}
	)

	val DEFAULT_OUTRUN: OutrunConfiguration =
		OutrunConfiguration(OutrunGoal.METERS_50, MiniGameDifficulty.NORMAL)
	val DEFAULT_TERRITORY: TerritoryConfiguration =
		TerritoryConfiguration(TerritoryGoal.CELLS_10)
	val DEFAULT_ZEN_WALK: ZenWalkConfiguration =
		ZenWalkConfiguration(ZenGoal.MINUTES_10, MiniGameDifficulty.NORMAL)
	val DEFAULT_FUSE_RUN: FuseRunConfiguration =
		FuseRunConfiguration(FuseRunGoal.CHARGES_5, MiniGameDifficulty.NORMAL)
	val DEFAULT_SWITCHBACK: SwitchbackConfiguration =
		SwitchbackConfiguration(SwitchbackGoal.TURNS_6, MiniGameDifficulty.NORMAL)

	fun supportedFor(gameId: String): List<MiniGameConfiguration> = when (gameId) {
		OutrunConfiguration.GAME_ID -> OUTRUN
		TerritoryConfiguration.GAME_ID -> TERRITORY
		ZenWalkConfiguration.GAME_ID -> ZEN_WALK
		FuseRunConfiguration.GAME_ID -> FUSE_RUN
		SwitchbackConfiguration.GAME_ID -> SWITCHBACK
		else -> emptyList()
	}

	fun defaultFor(gameId: String): MiniGameConfiguration? = when (gameId) {
		OutrunConfiguration.GAME_ID -> DEFAULT_OUTRUN
		TerritoryConfiguration.GAME_ID -> DEFAULT_TERRITORY
		ZenWalkConfiguration.GAME_ID -> DEFAULT_ZEN_WALK
		FuseRunConfiguration.GAME_ID -> DEFAULT_FUSE_RUN
		SwitchbackConfiguration.GAME_ID -> DEFAULT_SWITCHBACK
		else -> null
	}

	private fun <T> immutableList(values: Collection<T>): List<T> =
		Collections.unmodifiableList(ArrayList(values))
}
