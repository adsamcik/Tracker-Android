package com.adsamcik.signalcollector.game.fragment.recycler.creator

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.recycler.decoration.SimpleMarginDecoration
import com.adsamcik.signalcollector.common.recycler.multitype.MultiTypeViewHolder
import com.adsamcik.signalcollector.common.recycler.multitype.MultiTypeViewHolderCreator
import com.adsamcik.signalcollector.game.R
import com.adsamcik.signalcollector.game.fragment.recycler.data.GameRecyclerData
import com.adsamcik.signalcollector.game.fragment.recycler.viewholder.ChallengeRecyclerViewHolder

class ChallengeRecyclerCreator : MultiTypeViewHolderCreator<GameRecyclerData> {
	override fun createViewHolder(parent: ViewGroup): MultiTypeViewHolder<GameRecyclerData> {
		val context = parent.context
		val layoutInflater = LayoutInflater.from(context)
		val rootView = layoutInflater.inflate(R.layout.layout_card_challenge, parent, false)

		val recycler = rootView.findViewById<RecyclerView>(R.id.recycler).apply {
			layoutManager = LinearLayoutManager(context)
			addItemDecoration(SimpleMarginDecoration())
		}

		@Suppress("unchecked_cast")
		return ChallengeRecyclerViewHolder(rootView, rootView.findViewById(R.id.title), recycler,
				2) as MultiTypeViewHolder<GameRecyclerData>
	}
}
