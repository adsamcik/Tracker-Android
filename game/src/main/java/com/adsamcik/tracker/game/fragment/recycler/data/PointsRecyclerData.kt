package com.adsamcik.tracker.game.fragment.recycler.data

import com.adsamcik.tracker.game.fragment.recycler.GameRecyclerType

/**
 * Recycler data for Points.
 */
class PointsRecyclerData(
		val pointsEarnedToday: Int
) : GameRecyclerData {
	override val type: GameRecyclerType
		get() = GameRecyclerType.Points
}

