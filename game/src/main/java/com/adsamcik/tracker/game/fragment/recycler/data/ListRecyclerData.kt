package com.adsamcik.tracker.game.fragment.recycler.data

import androidx.annotation.StringRes
import com.adsamcik.tracker.game.fragment.recycler.GameRecyclerType

/**
 * Recycler data for list based items in Game Recycler.
 */
abstract class ListRecyclerData<Adapter>(@StringRes val title: Int, val dataAdapter: Adapter) :
		GameRecyclerData {
	override val type: GameRecyclerType = GameRecyclerType.List
}
