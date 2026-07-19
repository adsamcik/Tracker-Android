package com.adsamcik.tracker.game.minigame

internal enum class SwitchbackGoal(
	val turns: Int,
) : MiniGameGoal {
	TURNS_4(4),
	TURNS_6(6),
	TURNS_10(10),
	;

	override val displayValue: Int get() = turns
	override val scoreTarget: Double get() = turns.toDouble()
	override val unit: MiniGameGoalUnit get() = MiniGameGoalUnit.TURNS
}

internal data class SwitchbackConfiguration(
	override val goal: SwitchbackGoal = SwitchbackGoal.TURNS_6,
	override val difficulty: MiniGameDifficulty = MiniGameDifficulty.NORMAL,
) : MiniGameConfiguration {
	override val gameId: String get() = GAME_ID

	companion object {
		const val GAME_ID: String = "switchback"
	}
}
