package com.adsamcik.signalcollector.game.fragment.recycler.data

import androidx.annotation.StringRes
import com.adsamcik.signalcollector.game.fragment.recycler.GameRecyclerType

abstract class ListRecyclerData<Adapter>(@StringRes val title: Int, val dataAdapter: Adapter) : GameRecyclerData {
	override val type: GameRecyclerType = GameRecyclerType.List
}