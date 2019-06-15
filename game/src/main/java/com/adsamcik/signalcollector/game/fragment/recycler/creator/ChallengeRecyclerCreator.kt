package com.adsamcik.signalcollector.game.fragment.recycler.creator

import android.view.LayoutInflater
import android.view.ViewGroup
import com.adsamcik.signalcollector.common.recycler.multitype.MultiTypeViewHolder
import com.adsamcik.signalcollector.common.recycler.multitype.MultiTypeViewHolderCreator
import com.adsamcik.signalcollector.game.R
import com.adsamcik.signalcollector.game.fragment.recycler.data.GameRecyclerData
import com.adsamcik.signalcollector.game.fragment.recycler.viewholder.ChallengeRecyclerViewHolder

class ChallengeRecyclerCreator : MultiTypeViewHolderCreator<GameRecyclerData> {
	override fun createViewHolder(parent: ViewGroup): MultiTypeViewHolder<GameRecyclerData> {
		val layoutInflater = LayoutInflater.from(parent.context)
		val rootView = layoutInflater.inflate(R.layout.layout_card_challenge, parent, false)
		@Suppress("unchecked_cast")
		return ChallengeRecyclerViewHolder(rootView, rootView.findViewById(R.id.title), rootView.findViewById(R.id.recycler)) as MultiTypeViewHolder<GameRecyclerData>
	}
}