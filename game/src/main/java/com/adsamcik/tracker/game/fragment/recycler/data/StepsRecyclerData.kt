package com.adsamcik.tracker.game.fragment.recycler.data

import com.adsamcik.tracker.game.fragment.recycler.GameRecyclerType
import com.adsamcik.tracker.game.fragment.recycler.data.abstraction.GameRecyclerData

/**
 * Recycler data for steps.
 */
data class StepsRecyclerData(
		val stepsToday: Int,
		val stepsThisWeek: Int,
		val goalDay: Int,
		val goalWeek: Int
) : GameRecyclerData {
	override val type: GameRecyclerType
		get() = GameRecyclerType.Steps
}

