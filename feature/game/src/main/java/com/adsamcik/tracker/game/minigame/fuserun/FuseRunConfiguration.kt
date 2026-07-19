package com.adsamcik.tracker.game.minigame

internal enum class FuseRunGoal(
	val charges: Int,
) : MiniGameGoal {
	CHARGES_3(3),
	CHARGES_5(5),
	CHARGES_8(8),
	;

	override val displayValue: Int get() = charges
	override val scoreTarget: Double get() = charges.toDouble()
	override val unit: MiniGameGoalUnit get() = MiniGameGoalUnit.CHARGES
}

internal data class FuseRunConfiguration(
	override val goal: FuseRunGoal = FuseRunGoal.CHARGES_5,
	override val difficulty: MiniGameDifficulty = MiniGameDifficulty.NORMAL,
) : MiniGameConfiguration {
	override val gameId: String get() = GAME_ID

	companion object {
		const val GAME_ID: String = "fuserun"
	}
}
