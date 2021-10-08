package com.adsamcik.tracker.game.fragment.recycler.data

import com.adsamcik.tracker.game.fragment.recycler.GameRecyclerType
import com.adsamcik.tracker.game.fragment.recycler.data.abstraction.GameRecyclerData

/**
 * Recycler data for Points.
 */
data class PointsRecyclerData(
		val pointsEarnedToday: Int
) : GameRecyclerData {
	override val type: GameRecyclerType
		get() = GameRecyclerType.Points
}

